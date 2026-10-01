package top.kuisland.eternalsoul.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S：批量开关。mode 0 = 全选（全部启用），mode 1 = 反选（全部翻转）。
 * 应用于服务端探测到的全部饰品条目。
 */
public class BulkTogglePacket {

    public static final int SELECT_ALL = 0;
    public static final int INVERT = 1;

    private final int mode;

    public BulkTogglePacket(int mode) {
        this.mode = mode;
    }

    public static void encode(BulkTogglePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.mode);
    }

    public static BulkTogglePacket decode(FriendlyByteBuf buf) {
        return new BulkTogglePacket(buf.readVarInt());
    }

    public static void handle(BulkTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            for (top.kuisland.eternalsoul.CurioIndex.Entry entry
                    : top.kuisland.eternalsoul.CurioIndex.get(sender)) {
                boolean enable = msg.mode == SELECT_ALL
                        || top.kuisland.eternalsoul.DisabledStore.isDisabled(sender, entry.itemId());
                top.kuisland.eternalsoul.DisabledStore.setEnabled(sender, entry.itemId(), enable);
            }
            if (top.kuisland.eternalsoul.SimulationManager.isActive(sender)) {
                top.kuisland.eternalsoul.SimulationManager.requestDeactivate(sender, true);
            } else {
                Network.sendSync(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
