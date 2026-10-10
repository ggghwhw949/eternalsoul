package top.kuisland.eternalsoul;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
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
            if (ids == null) {
                // 兜底：get() 命中缓存时不会重算 cachedIds，从缓存条目派生，
                // 消除 invalidate()/build() 竞态下白名单误杀全部ID的窗口
                List<Entry> list = get(player);
                Set<ResourceLocation> derived = new HashSet<>();
                for (Entry entry : list) {
                    derived.add(entry.itemId());
                }
                ids = derived;
            }
        }
        return ids.contains(id);
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
                } else if (EternalSoulConfig.looseSlotFallback()
                        && handlerIds.contains("curio")
                        && CuriosApi.isStackValid(genericCurioCtx, stack)) {
                    // 宽松回退（可经 looseSlotFallback 配置关闭）：声明槽位玩家
                    // 不具备的物品经泛用 curio 槽参与模拟
                    chosen = "curio";
                }
            } else if (handlerIds.contains("curio")
                    && CuriosApi.isStackValid(genericCurioCtx, stack)) {
                // 未声明槽位：标签/谓词/能力回退（固定行为，不受开关影响）
                chosen = "curio";
            }
            if (chosen == null) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                result.add(new Entry(id, chosen));
            }
        }
        result.sort(Comparator.comparing(Entry::itemId));
        return result;
    }
}
