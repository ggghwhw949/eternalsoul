package top.kuisland.eternalsoul.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * C2S：批量开关（带作用域）。
 * mode 0 = 全选（启用列表内全部），mode 1 = 反选（翻转列表内全部）。
 * ids 为客户端选定的作用域（搜索过滤后的条目，或全部条目）。
 */
public class BulkTogglePacket {

    public static final int SELECT_ALL = 0;
    public static final int INVERT = 1;

    private final int mode;
    private final List<String> ids;

    public BulkTogglePacket(int mode, List<String> ids) {
        this.mode = mode;
        this.ids = ids;
    }

    public static void encode(BulkTogglePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.mode);
        buf.writeVarInt(msg.ids.size());
        for (String id : msg.ids) {
            buf.writeUtf(id, 256);
        }
    }

    public static BulkTogglePacket decode(FriendlyByteBuf buf) {
        int mode = buf.readVarInt();
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf(256));
        }
        return new BulkTogglePacket(mode, ids);
    }

    public static void handle(BulkTogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            for (String raw : msg.ids) {
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id == null) {
                    continue;
                }
                boolean enable = msg.mode == SELECT_ALL
                        || top.kuisland.eternalsoul.DisabledStore.isDisabled(sender, id);
                top.kuisland.eternalsoul.DisabledStore.setEnabled(sender, id, enable);
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
