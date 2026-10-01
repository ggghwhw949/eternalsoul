package top.kuisland.eternalsoul.network;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S：应用配置文件设置。将玩家的开关状态整体重置为
 * config/eternalsoul-defaults.toml 定义的默认值（用于误操作后恢复初始设置）。
 */
public class ApplyConfigPacket {

    public static void encode(ApplyConfigPacket msg, net.minecraft.network.FriendlyByteBuf buf) {
    }

    public static ApplyConfigPacket decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ApplyConfigPacket();
    }

    public static void handle(ApplyConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            top.kuisland.eternalsoul.DisabledStore.initDefaults(sender,
                    top.kuisland.eternalsoul.EternalSoulConfig.defaults());
            if (top.kuisland.eternalsoul.SimulationManager.isActive(sender)) {
                top.kuisland.eternalsoul.SimulationManager.requestDeactivate(sender, true);
            } else {
                Network.sendSync(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
