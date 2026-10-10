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
 * 开关配置：config/eternalsoul/ 文件夹
 * <pre>
 * config/eternalsoul/
 * ├─ initial.toml          初始配置：玩家首次进入世界时应用一次的默认开关
 * └─ players/&lt;玩家ID&gt;.toml  个人配置：玩家在 K 界面点"保存配置"生成的
 *                           当前开关快照；"加载配置"时若存在个人配置则
 *                           优先加载个人配置，否则回退初始配置
 * </pre>
 * 每行一个条目，格式 "饰品ID = true/false"。true = 启用模拟，false = 关闭。
 * 饰品ID 对应模组未安装时静默忽略，便于整合包作者预写、跨包复用。
 * 个人配置为完整快照（加载时文件中缺失的条目视为 true）。
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

    /** config/eternalsoul/ 根目录 */
    private static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("eternalsoul");
    }

    /** 玩家个人配置文件路径（玩家ID已消毒，仅安全字符） */
    public static Path personalFile(String playerName) {
        return folder().resolve("players").resolve(sanitize(playerName) + ".toml");
    }

    /** 文件名消毒：只保留字母数字下划线连字符，其余替换为 _，长度上限 64 */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 64; i++) {
            char c = name.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        return sb.length() == 0 ? "player" : sb.toString();
    }

    /** 加载初始配置；不存在时写入带注释与示例的初始文件；自动迁移旧版单文件 */
    public static void loadOrCreate() {
        DEFAULTS.clear();
        Path file = folder().resolve("initial.toml");
        Path legacy = FMLPaths.CONFIGDIR.get().resolve("eternalsoul-defaults.toml");
        try {
            Files.createDirectories(folder());
            if (!Files.exists(file) && Files.exists(legacy)) {
                Files.move(legacy, file);
                LOGGER.info("[EternalSoul] 已迁移旧配置文件至 config/eternalsoul/initial.toml");
            }
            if (!Files.exists(file)) {
                Files.writeString(file, sample(), StandardCharsets.UTF_8);
            }
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(rawLine.trim());
            }
        } catch (IOException e) {
            LOGGER.warn("[EternalSoul] 读取初始配置失败: {}", e.toString());
        }
        LOGGER.info("[EternalSoul] 已加载初始配置，共 {} 个条目", DEFAULTS.size());
    }

    /** 读取玩家个人配置；文件不存在返回 null（调用方回退初始配置） */
    public static Map<ResourceLocation, Boolean> loadPersonal(String playerName) {
        Path file = personalFile(playerName);
        if (!Files.exists(file)) {
            return null;
        }
        Map<ResourceLocation, Boolean> result = new LinkedHashMap<>();
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(line.substring(0, eq).trim());
                String value = line.substring(eq + 1).trim();
                if (id != null
                        && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))) {
                    result.put(id, Boolean.parseBoolean(value));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[EternalSoul] 读取个人配置失败({}): {}", file.getFileName(), e.toString());
            return null;
        }
        return result;
    }

    /** 写入玩家个人配置（完整开关快照），返回是否成功 */
    public static boolean savePersonal(String playerName, Map<ResourceLocation, Boolean> state) {
        Path file = personalFile(playerName);
        StringBuilder sb = new StringBuilder();
        sb.append("# 永恒之魂 个人配置 —— 由游戏内 [保存配置] 按钮生成，可手工编辑\n");
        sb.append("# 玩家: ").append(playerName).append('\n');
        sb.append("# [加载配置] 时若本文件存在则优先于初始配置生效\n\n");
        state.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n'));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[EternalSoul] 写入个人配置失败({}): {}", file.getFileName(), e.toString());
            return false;
        }
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
                # 永恒之魂 (Eternal Soul) —— 初始配置 initial.toml
                #
                # 每行一个饰品条目，格式：  饰品ID = true / false
                #   true  = 该饰品默认启用模拟
                #   false = 该饰品默认关闭模拟
                #
                # * 玩家首次进入世界时应用本文件；之后玩家在游戏内（K 键界面）
                #   的自行调整优先于本文件。
                # * 玩家在 K 界面点 [保存配置] 会生成个人配置
                #   (config/eternalsoul/players/玩家ID.toml)；点 [加载配置] 时
                #   若存在个人配置则优先加载个人配置，否则加载本文件。
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
