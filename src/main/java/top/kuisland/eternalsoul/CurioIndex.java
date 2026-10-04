package top.kuisland.eternalsoul;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
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

    private static final Logger LOGGER = LogUtils.getLogger();

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
        int registryCount = 0;
        int noSlot = 0;

        for (Item item : ForgeRegistries.ITEMS) {
            if (item == EternalSoul.ETERNAL_SOUL.get()) {
                continue;
            }
            registryCount++;
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
                noSlot++;
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                result.add(new Entry(id, chosen));
            } else {
                noSlot++;
            }
        }
        result.sort(Comparator.comparing(Entry::itemId));
        LOGGER.info("[EternalSoul-DIAG] index build: registry={} included={} noSlot={}",
                registryCount, result.size(), noSlot);
        if (!player.level().isClientSide) {
            dumpIndex(result);
        }
        return result;
    }

    /**
     * 服务端索引快照：写入 logs/eternalsoul-index.txt（每次构建覆盖）。
     * 任何"某饰品没被探测到"的问题均可直接对照此文件核对，
     * 不在文件内 = Curios 规则下放不进玩家饰品栏（或能力判定未通过）。
     */
    private static void dumpIndex(List<Entry> entries) {
        try {
            Path out = FMLPaths.GAMEDIR.get().resolve("logs").resolve("eternalsoul-index.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("# 永恒之魂 饰品索引快照（每次构建覆盖） 共 ").append(entries.size()).append(" 件\n");
            for (Entry e : entries) {
                sb.append(e.itemId()).append(" -> ").append(e.slot()).append('\n');
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 快照写失败不影响功能
        }
    }
}
