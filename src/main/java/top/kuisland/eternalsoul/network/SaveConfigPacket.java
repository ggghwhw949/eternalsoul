package top.kuisland.eternalsoul.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.kuisland.eternalsoul.CurioIndex;
import top.kuisland.eternalsoul.DisabledStore;
import top.kuisland.eternalsoul.EternalSoulConfig;

/**
 * C2S：保存配置。把玩家当前的完整开关状态写入
 * config/eternalsoul/players/玩家ID.toml（个人配置）。
 * 此后 [加载配置] 将优先加载该个人配置而非初始配置。
 */
public class SaveConfigPacket {

    public static void encode(SaveConfigPacket msg, net.minecraft.network.FriendlyByteBuf buf) {
    }

    public static SaveConfigPacket decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SaveConfigPacket();
    }

    public static void handle(SaveConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            // 当前完整开关快照：探测索引内每件饰品 enable = 未被玩家关闭
            Set<String> disabled = DisabledStore.load(sender);
            Map<net.minecraft.resources.ResourceLocation, Boolean> state = new LinkedHashMap<>();
            for (CurioIndex.Entry entry : CurioIndex.get(sender)) {
                state.put(entry.itemId(), !disabled.contains(entry.itemId().toString()));
            }
            boolean ok = EternalSoulConfig.savePersonal(
                    sender.getGameProfile().getName(), state);
            sender.sendSystemMessage(Component.translatable(ok
                    ? "eternalsoul.gui.config_saved"
                    : "eternalsoul.gui.config_save_failed"));
        });
        ctx.get().setPacketHandled(true);
    }
}
