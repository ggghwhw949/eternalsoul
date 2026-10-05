package top.kuisland.eternalsoul;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

/**
 * 每个玩家“已关闭模拟”的饰品集合，存放在 Forge 的 PlayerPersisted 持久 NBT 中，
 * 自动跨死亡与重登保留。
 */
public final class DisabledStore {

    private static final String KEY = "eternalsoul_disabled";
    private static final String INIT_KEY = "eternalsoul_initialized";

    private static final Logger LOGGER = LogUtils.getLogger();

    private DisabledStore() {
    }

    private static CompoundTag root(Player player) {
        CompoundTag persistent = player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG);
        return persistent;
    }

    public static Set<String> load(Player player) {
        Set<String> set = new HashSet<>();
        ListTag list = root(player).getList(KEY, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            set.add(list.getString(i));
        }
        return set;
    }

    /** 批量写回整个开关集合（避免逐条 setEnabled 造成 O(n²) NBT 重建） */
    public static void saveAll(Player player, Set<String> set) {
        CompoundTag persistent = player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = new ListTag();
        for (String s : set) {
            list.add(net.minecraft.nbt.StringTag.valueOf(s));
        }
        persistent.put(KEY, list);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persistent);
        // 无节流探针：写入后立即回读验证，并记录玩家实例指纹
        int verify = load(player).size();
        LOGGER.info("[EternalSoul-DIAG] saveAll: player@{} write={} verify={}",
                System.identityHashCode(player), set.size(), verify);
        if (verify != set.size()) {
            LOGGER.warn("[EternalSoul-DIAG] saveAll MISMATCH: wrote {} but read back {} "
                    + "on player@{}", set.size(), verify, System.identityHashCode(player));
        }
    }

    public static boolean isDisabled(Player player, ResourceLocation itemId) {
        return load(player).contains(itemId.toString());
    }

    public static void setEnabled(Player player, ResourceLocation itemId, boolean enabled) {
        Set<String> set = load(player);
        if (enabled) {
            set.remove(itemId.toString());
        } else {
            set.add(itemId.toString());
        }
        saveAll(player, set);
    }

    /** 玩家是否已应用过默认配置（首次进服时应用一次，之后以玩家自选为准） */
    public static boolean isInitialized(Player player) {
        return root(player).getBoolean(INIT_KEY);
    }

    /** 用配置文件的默认开关初始化该玩家的状态并打上初始化标记 */
    public static void initDefaults(Player player, Map<ResourceLocation, Boolean> defaults) {
        Set<String> disabled = new HashSet<>();
        defaults.forEach((id, enable) -> {
            if (!enable) {
                disabled.add(id.toString());
            }
        });
        // 无节流探针：initDefaults 会整体覆盖玩家自选开关——记录调用者栈，
        // 任何"开关被重置回配置默认"的现象都可由此定位触发源
        LOGGER.info("[EternalSoul-DIAG] initDefaults: player@{} reset to {} disabled",
                System.identityHashCode(player), disabled.size(),
                new IllegalStateException("initDefaults call site"));
        saveAll(player, disabled);
        CompoundTag persistent = player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG);
        persistent.putBoolean(INIT_KEY, true);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persistent);
    }
}
