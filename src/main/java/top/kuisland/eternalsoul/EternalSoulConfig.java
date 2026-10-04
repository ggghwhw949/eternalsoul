package top.kuisland.eternalsoul;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * 默认开关配置：config/eternalsoul-defaults.toml
 * <p>
 * 每行一个条目，格式为 "饰品ID = true/false"。true = 默认启用模拟，
 * false = 默认关闭模拟。饰品ID 对应的模组未安装时该行被静默忽略，
 * 不产生任何报错，便于整合包作者预写配置、跨包复用。
 * <p>
 * 配置在玩家首次进入世界时应用一次；此后玩家在游戏内（K 键界面）
 * 的自行调整优先于本文件。执行 /reload 或重启服务端会重新加载。
 */
public final class EternalSoulConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceLocation, Boolean> DEFAULTS = new LinkedHashMap<>();

    /** 宽松槽位回退开关：声明槽位玩家不具备的物品是否经泛用 curio 槽参与模拟 */
    private static volatile boolean looseSlotFallback = true;

    private EternalSoulConfig() {
    }

    public static boolean looseSlotFallback() {
        return looseSlotFallback;
    }

    public static Map<ResourceLocation, Boolean> defaults() {
        return Map.copyOf(DEFAULTS);
    }

    /** 加载配置文件，不存在时写入带注释与示例的初始文件 */
    public static void loadOrCreate() {
        DEFAULTS.clear();
        Path file = FMLPaths.CONFIGDIR.get().resolve("eternalsoul-defaults.toml");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, sample(), StandardCharsets.UTF_8);
            }
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(rawLine.trim());
            }
        } catch (IOException e) {
            LOGGER.warn("[EternalSoul] 读取默认开关配置失败: {}", e.toString());
        }
        LOGGER.info("[EternalSoul] 已加载默认开关配置，共 {} 个条目", DEFAULTS.size());
    }

    private static void parseLine(String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            LOGGER.debug("[EternalSoul] 忽略无法解析的配置行: {}", line);
            return;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        // 系统开关（非饰品条目）
        if (key.equalsIgnoreCase("looseSlotFallback")) {
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                looseSlotFallback = Boolean.parseBoolean(value);
            }
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null || !(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
            LOGGER.debug("[EternalSoul] 忽略无法解析的配置行: {}", line);
            return;
        }
        DEFAULTS.put(id, Boolean.parseBoolean(value));
    }

    private static String sample() {
        return """
                # ================================================================
                # 永恒之魂 (Eternal Soul) —— 默认开关配置
                #
                # 每行一个饰品条目，格式：  饰品ID = true / false
                #   true  = 该饰品默认启用模拟
                #   false = 该饰品默认关闭模拟
                #
                # * 玩家首次进入世界时应用本配置，之后玩家在游戏内（K 键界面）
                #   的自行调整优先于本文件。
                # * 饰品ID 对应的模组未安装时该行自动忽略，不会报错，
                #   可以放心预写。可按行/按块自由添加。
                #
                # ------------------ 系统开关 ------------------
                # looseSlotFallback：宽松槽位回退。
                #   true  = 声明了玩家不具备的槽位类型的物品，也经泛用 curio 槽
                #           参与模拟（覆盖最大化）
                #   false = 此类物品不参与模拟（如遇效果闪烁等异常可用于对照
                #           测试；修改后 /reload 或重进世界生效）
                # 示例（可自行修改或删除）：
                #   # 某模组的戒指
                #   example:impossible_ring = false
                #   example:nonexistent_amulet = true
                #
                # ================================================================

                looseSlotFallback = true
                example:some_mod_item_not_installed_a = false
                example:this_item_never_exists_b = true
                example:impossible_curio_id_c = false
                """;
    }
}
