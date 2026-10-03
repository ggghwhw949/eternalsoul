package top.kuisland.eternalsoul.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import top.kuisland.eternalsoul.CurioIndex;

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
        // 每个条目编码后至少占 1 字节：以剩余可读字节数钳制包内声明的数量，
        // 防止恶意包声明超大 n 触发 ArrayList 预分配直接 OOM
        n = Math.min(n, buf.readableBytes());
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
            if (msg.mode != SELECT_ALL && msg.mode != INVERT) {
                return; // 非法模式直接丢弃
            }
            Set<String> disabled = top.kuisland.eternalsoul.DisabledStore.load(sender);
            Map<ResourceLocation, Boolean> changes = new LinkedHashMap<>();
            for (String raw : msg.ids) {
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id == null || !CurioIndex.isKnown(id, sender)) {
                    continue; // 白名单：只接受当前探测索引内的饰品ID，防垃圾ID注入
                }
                boolean enable = msg.mode == SELECT_ALL || !disabled.contains(id.toString());
                changes.put(id, enable);
            }
            top.kuisland.eternalsoul.SimulationManager.applyBulkToggles(sender, changes);
        });
        ctx.get().setPacketHandled(true);
    }
}
