package top.kuisland.eternalsoul;

import java.util.ArrayList;
import java.util.Comparator;
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

    private CurioIndex() {
    }

    public static void invalidate() {
        cached = null;
    }

    public static List<Entry> get(Player player) {
        List<Entry> list = cached;
        if (list == null) {
            synchronized (CurioIndex.class) {
                if (cached == null) {
                    cached = build(player);
                }
                list = cached;
            }
        }
        return list;
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
            } else if (handlerIds.contains("curio")
                    && CuriosApi.isStackValid(genericCurioCtx, stack)) {
                // 泛用 curio 槽的回退判定（标签 / 谓词 / capability）
                chosen = "curio";
            }
            if (chosen != null) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    result.add(new Entry(id, chosen));
                }
            }
        }
        result.sort(Comparator.comparing(Entry::itemId));
        return result;
    }
}
