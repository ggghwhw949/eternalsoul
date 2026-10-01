package top.kuisland.eternalsoul.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import top.kuisland.eternalsoul.CurioIndex;

/**
 * 客户端缓存：服务端同步来的关闭集合 / 激活状态 / 虚拟槽区间，
 * 以及客户端本地构建的饰品索引。
 */
public final class ClientCache {

    private static volatile Set<String> disabled = Set.of();
    private static volatile boolean active;
    private static volatile Map<String, int[]> ranges = Map.of();
    private static volatile List<CurioIndex.Entry> index;

    private ClientCache() {
    }

    public static void update(Set<String> newDisabled, boolean newActive,
                              Map<String, int[]> newRanges) {
        disabled = Set.copyOf(newDisabled);
        active = newActive;
        ranges = Map.copyOf(newRanges);
    }

    public static boolean isVirtualSlot(String identifier, int index) {
        int[] range = ranges.get(identifier);
        return range != null && index >= range[0] && index < range[0] + range[1];
    }

    public static boolean isEnabled(ResourceLocation itemId) {
        return !disabled.contains(itemId.toString());
    }

    public static void setLocalEnabled(ResourceLocation itemId, boolean enable) {
        Set<String> copy = new HashSet<>(disabled);
        if (enable) {
            copy.remove(itemId.toString());
        } else {
            copy.add(itemId.toString());
        }
        disabled = Set.copyOf(copy);
    }

    public static boolean isActive() {
        return active;
    }

    public static List<CurioIndex.Entry> index() {
        List<CurioIndex.Entry> result = index;
        if (result == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return List.of();
            }
            result = CurioIndex.get(mc.player);
            index = result;
        }
        return result;
    }

    public static List<ResourceLocation> enabledIds() {
        List<ResourceLocation> list = new ArrayList<>();
        for (CurioIndex.Entry entry : index()) {
            if (isEnabled(entry.itemId())) {
                list.add(entry.itemId());
            }
        }
        return list;
    }
}
