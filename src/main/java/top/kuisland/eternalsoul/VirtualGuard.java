package top.kuisland.eternalsoul;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 虚拟物品守卫：mixin 与模拟管理器共用的静态登记处。
 * <p>
 * 仅服务端线程访问，无需并发容器；停用时整体清空，不持有冗余拷贝
 * （身份集合只保存处理器内同一实例的引用）。
 */
public final class VirtualGuard {

    /** 身份语义的虚拟堆集合：任何路径试图把虚拟物品交给玩家时直接抹除 */
    private static final Set<ItemStack> VIRTUAL_STACKS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** 玩家 -> 槽位类型 -> [base, added) 虚拟区间 */
    private static final Map<UUID, Map<String, int[]>> RANGES = new HashMap<>();

    private VirtualGuard() {
    }

    public static UUID modifierUuid(String identifier) {
        return UUID.nameUUIDFromBytes(
                ("eternalsoul:" + identifier).getBytes(StandardCharsets.UTF_8));
    }

    public static void register(LivingEntity wearer, Map<String, int[]> ranges) {
        RANGES.put(wearer.getUUID(), new HashMap<>(ranges));
    }

    public static void unregister(LivingEntity wearer) {
        RANGES.remove(wearer.getUUID());
    }

    public static void track(ItemStack stack) {
        VIRTUAL_STACKS.add(stack);
    }

    public static void untrack(ItemStack stack) {
        VIRTUAL_STACKS.remove(stack);
    }

    public static void clearTracked() {
        VIRTUAL_STACKS.clear();
    }

    /** 服务器停止时整体清空（所有已追踪虚拟堆与区间登记一并释放，防跨世界泄漏） */
    public static void clearAll() {
        VIRTUAL_STACKS.clear();
        RANGES.clear();
    }

    public static boolean isVirtual(ItemStack stack) {
        return stack != null && !stack.isEmpty() && VIRTUAL_STACKS.contains(stack);
    }

    public static boolean isVirtualIndex(LivingEntity wearer, String identifier, int index) {
        if (wearer == null) {
            return false;
        }
        Map<String, int[]> ranges = RANGES.get(wearer.getUUID());
        if (ranges == null) {
            return false;
        }
        int[] range = ranges.get(identifier);
        return range != null && index >= range[0] && index < range[0] + range[1];
    }

    // ==================== 存档数据剥离（mixin 调用） ====================

    /**
     * 剥除 ItemStackHandler 格式（{"Items":[{Slot,...}],"Size":N}）中处于
     * 虚拟区间的条目；同时从修饰符列表中移除本模组的槽位修饰符。
     */
    public static void stripHandlerTag(CompoundTag handlerTag, LivingEntity wearer,
                                       String identifier) {
        if (wearer == null) {
            return;
        }
        Map<String, int[]> ranges = RANGES.get(wearer.getUUID());
        int[] range = ranges != null ? ranges.get(identifier) : null;
        if (range != null) {
            stripItemsCompound(handlerTag, "Stacks", range);
            stripItemsCompound(handlerTag, "Cosmetics", range);
        }
        stripModifierList(handlerTag, "CachedModifiers", identifier);
        stripModifierList(handlerTag, "PersistentModifiers", identifier);
    }

    private static void stripItemsCompound(CompoundTag parent, String key, int[] range) {
        CompoundTag stacks = parent.getCompound(key);
        if (stacks.contains("Items", Tag.TAG_LIST)) {
            stripItemsList(stacks.getList("Items", Tag.TAG_COMPOUND), range);
        }
    }

    private static void stripItemsList(ListTag items, int[] range) {
        for (int i = items.size() - 1; i >= 0; i--) {
            CompoundTag entry = items.getCompound(i);
            int slot = entry.getInt("Slot");
            if (slot >= range[0] && slot < range[0] + range[1]) {
                items.remove(i);
            }
        }
    }

    /** 从指定键下的修饰符列表中移除本模组的槽位修饰符条目 */
    public static void stripModifierList(CompoundTag parent, String key,
                                         String identifier) {
        if (!parent.contains(key, Tag.TAG_LIST)) {
            return;
        }
        UUID ours = modifierUuid(identifier);
        ListTag list = parent.getList(key, Tag.TAG_COMPOUND);
        for (int i = list.size() - 1; i >= 0; i--) {
            try {
                if (ours.equals(list.getCompound(i).getUUID("UUID"))) {
                    list.remove(i);
                }
            } catch (Exception ignored) {
                // 无 UUID 的修饰符条目不是我们的
            }
        }
    }

    // ==================== 旧存档净化（readTag 前） ====================

    /**
     * 净化旧版本（v1.0.x）污染的存档数据：若某槽位类型的 CachedModifiers 中
     * 残留本模组的修饰符，则移除该修饰符，并把超过“净化后安全槽数”的
     * 物品条目从数据中删除（虚空抹除，不返还给玩家）。
     */
    public static void sanitizeSavedTag(CompoundTag root) {
        ListTag curios = root.getList("Curios", Tag.TAG_COMPOUND);
        for (int i = 0; i < curios.size(); i++) {
            CompoundTag entry = curios.getCompound(i);
            String identifier = entry.getString("Identifier");
            CompoundTag handler = entry.getCompound("StacksHandler");
            if (!handler.contains("CachedModifiers", Tag.TAG_LIST)) {
                continue;
            }
            ListTag cached = handler.getList("CachedModifiers", Tag.TAG_COMPOUND);
            UUID ours = modifierUuid(identifier);
            boolean hadOurs = false;
            for (int j = cached.size() - 1; j >= 0; j--) {
                try {
                    if (ours.equals(cached.getCompound(j).getUUID("UUID"))) {
                        cached.remove(j);
                        hadOurs = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!hadOurs) {
                continue;
            }
            // 安全面 = 基础槽数 + 其余修饰符的 ADDITION 总和
            int safeSize = handler.getInt("SavedBaseSize");
            safeSize += sumAddition(handler.getList("PersistentModifiers", Tag.TAG_COMPOUND));
            safeSize += sumAddition(cached);
            stripBeyond(handler.getCompound("Stacks"), safeSize);
            stripBeyond(handler.getCompound("Cosmetics"), safeSize);
        }
    }

    private static int sumAddition(ListTag modifiers) {
        int sum = 0;
        for (int i = 0; i < modifiers.size(); i++) {
            CompoundTag m = modifiers.getCompound(i);
            if (m.getInt("Operation") == 0) {
                sum += (int) m.getDouble("Amount");
            }
        }
        return sum;
    }

    private static void stripBeyond(CompoundTag stacks, int safeSize) {
        if (!stacks.contains("Items", Tag.TAG_LIST)) {
            return;
        }
        ListTag items = stacks.getList("Items", Tag.TAG_COMPOUND);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.getCompound(i).getInt("Slot") >= safeSize) {
                items.remove(i);
            }
        }
    }
}
