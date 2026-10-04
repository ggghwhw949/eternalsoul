package top.kuisland.eternalsoul;

import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotAttribute;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.ISlotType;

/**
 * 饰品索引：扫描整个物品注册表，依据 Curios 自己的槽位有效性规则
 * （getItemStackSlots / isStackValid）判定“能否放入饰品栏”，并为每个
 * 可装备物品选定一个目标槽位类型。客户端与服务端各自独立构建。
 */
public final class CurioIndex {

    /** itemId -> 选定的槽位标识符 */
    public record Entry(ResourceLocation itemId, String slot) {
    }

    private static volatile List<Entry> cached;
    private static volatile Set<ResourceLocation> cachedIds;

    /** 槽位增益探测用的占位修饰符UUID（结果只读后即弃，不落任何状态） */
    private static final UUID PROBE_UUID =
            UUID.nameUUIDFromBytes("eternalsoul:index-probe".getBytes());

    private CurioIndex() {
    }

    public static void invalidate() {
        cached = null;
        cachedIds = null;
    }

    public static List<Entry> get(Player player) {
        List<Entry> list = cached;
        if (list == null) {
            synchronized (CurioIndex.class) {
                if (cached == null) {
                    List<Entry> built = build(player);
                    Set<ResourceLocation> ids = new HashSet<>();
                    for (Entry entry : built) {
                        ids.add(entry.itemId());
                    }
                    // 先写 ids 再写 list：读到非空 cached 的线程必然能看到 ids
                    cachedIds = ids;
                    cached = built;
                }
                list = cached;
            }
        }
        return list;
    }

    /** 网络包白名单校验：该物品ID是否属于当前探测索引（防垃圾ID注入玩家NBT） */
    public static boolean isKnown(ResourceLocation id, Player player) {
        Set<ResourceLocation> ids = cachedIds;
        if (ids == null) {
            get(player);
            ids = cachedIds;
        }
        return ids != null && ids.contains(id);
    }

    private static List<Entry> build(Player player) {
        List<Entry> result = new ArrayList<>();
        Set<String> handlerIds = CuriosApi.getCuriosInventory(player)
                .map(handler -> Set.copyOf(handler.getCurios().keySet()))
                .orElse(Set.of());
        SlotContext genericCurioCtx = new SlotContext("curio", player, 0, false, true);

        for (Item item : ForgeRegistries.ITEMS) {
            if (item == EternalSoul.ETERNAL_SOUL.get()) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            Map<String, ISlotType> slots = CuriosApi.getItemStackSlots(stack, player);
            String chosen = null;
            if (!slots.isEmpty()) {
                // 选定顺序：字典序，保证确定性
                TreeSet<String> candidates = new TreeSet<>(slots.keySet());
                candidates.retainAll(handlerIds);
                if (!candidates.isEmpty()) {
                    chosen = candidates.first();
                }
                // 注意：声明了槽位类型但玩家不具备时【不】回退到泛用 curio 槽。
                // 整合包常存在声明未注册槽位类型（artifact_*/boot/halo 等）的
                // 大量物品，回退会把它们全部灌入 curio 计划，导致巨型重建
                // 风暴与客户端同步竞态（v1.2.1~1.2.2 的闪烁/显形/开关回退根源）。
            } else if (handlerIds.contains("curio")
                    && CuriosApi.isStackValid(genericCurioCtx, stack)) {
                // 完全未声明槽位的物品才走泛用 curio 槽回退（标签/谓词判定）
                chosen = "curio";
            }
            if (chosen != null) {
                // 槽位增益类饰品（自带 +N 槽位效果）不参与模拟：其增益会随虚拟堆
                // 的装载/腾空/重锚换位被反复增删，与尾部锚定互踩形成震荡
                // （表现为 buff 闪烁、饰品界面出现多余空槽与宽度变化）。
                // 真实佩戴此类饰品时效果照常生效。
                if (grantsSlotBonus(chosen, player, stack)) {
                    continue;
                }
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    result.add(new Entry(id, chosen));
                }
            }
        }
        result.sort(Comparator.comparing(Entry::itemId));
        return result;
    }

    /** 该物品的属性修饰符中是否含有槽位增益（SlotAttribute，即 "+N 某类槽位"） */
    private static boolean grantsSlotBonus(String chosen, Player player, ItemStack stack) {
        try {
            Multimap<Attribute, AttributeModifier> attrs = CuriosApi.getAttributeModifiers(
                    new SlotContext(chosen, player, 0, false, true), PROBE_UUID, stack);
            for (Attribute attribute : attrs.keySet()) {
                if (attribute instanceof SlotAttribute) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 无法判定时不排除（保守取向）
        }
        return false;
    }
}
