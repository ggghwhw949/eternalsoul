package top.kuisland.eternalsoul.network;

import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S：加载配置。将玩家的开关状态整体重置：
 * 存在个人配置（config/eternalsoul/players/玩家ID.toml）→ 应用个人配置
 * （完整快照，文件中缺失的条目视为启用）；否则回退初始配置
 * （config/eternalsoul/initial.toml，即误操作后恢复初始设置）。
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
            // 个人配置优先；initDefaults 语义为"map 中 false 条目禁用、缺席条目启用"，
            // 个人快照与初始配置均可直接套用
            java.util.Map<net.minecraft.resources.ResourceLocation, Boolean> personal =
                    top.kuisland.eternalsoul.EternalSoulConfig.loadPersonal(
                            sender.getGameProfile().getName());
            top.kuisland.eternalsoul.DisabledStore.initDefaults(sender,
                    personal != null ? personal
                            : top.kuisland.eternalsoul.EternalSoulConfig.defaults());
            if (top.kuisland.eternalsoul.SimulationManager.isActive(sender)) {
                top.kuisland.eternalsoul.SimulationManager.requestDeactivate(sender, true);
            } else {
                Network.sendSync(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
