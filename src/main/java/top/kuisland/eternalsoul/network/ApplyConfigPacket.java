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
            java.util.Map<net.minecraft.resources.ResourceLocation, Boolean> source;
            String sourceKey;
            if (personal != null) {
                source = personal;
                sourceKey = "eternalsoul.gui.config_loaded_personal";
            } else {
                source = top.kuisland.eternalsoul.EternalSoulConfig.defaults();
                sourceKey = "eternalsoul.gui.config_loaded_initial";
            }
            top.kuisland.eternalsoul.DisabledStore.initDefaults(sender, source);
            // 立即同步：状态已在 NBT 落定，界面此刻就应刷新；
            // 后台重建链（清空→重锚→重激活）收尾还会再同步一次，冗余无害
            Network.sendSync(sender);
            if (top.kuisland.eternalsoul.SimulationManager.isActive(sender)) {
                top.kuisland.eternalsoul.SimulationManager.requestDeactivate(sender, true);
            }
            long disabledCount = source.values().stream().filter(v -> !v).count();
            sender.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    sourceKey, disabledCount));
        });
        ctx.get().setPacketHandled(true);
    }
}
