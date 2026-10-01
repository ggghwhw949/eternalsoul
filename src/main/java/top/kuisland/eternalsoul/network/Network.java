package top.kuisland.eternalsoul.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import top.kuisland.eternalsoul.DisabledStore;
import top.kuisland.eternalsoul.EternalSoul;
import top.kuisland.eternalsoul.SimulationManager;

public final class Network {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EternalSoul.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Network() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, TogglePacket.class,
                TogglePacket::encode, TogglePacket::decode, TogglePacket::handle);
        CHANNEL.registerMessage(id++, SyncPacket.class,
                SyncPacket::encode, SyncPacket::decode, SyncPacket::handle);
        CHANNEL.registerMessage(id++, BulkTogglePacket.class,
                BulkTogglePacket::encode, BulkTogglePacket::decode, BulkTogglePacket::handle);
    }

    public static void sendSync(ServerPlayer player) {
        Map<String, int[]> ranges = SimulationManager.ranges(player);
        Map<String, int[]> copy = new HashMap<>();
        ranges.forEach((k, v) -> copy.put(k, new int[]{v[0], v[1]}));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncPacket(DisabledStore.load(player), SimulationManager.isActive(player), copy));
    }

    // ==================== C2S：开关某个饰品的模拟 ====================

    public static class TogglePacket {

        private final String itemId;
        private final boolean enable;

        public TogglePacket(String itemId, boolean enable) {
            this.itemId = itemId;
            this.enable = enable;
        }

        public static void encode(TogglePacket msg, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeUtf(msg.itemId, 256);
            buf.writeBoolean(msg.enable);
        }

        public static TogglePacket decode(net.minecraft.network.FriendlyByteBuf buf) {
            return new TogglePacket(buf.readUtf(256), buf.readBoolean());
        }

        public static void handle(TogglePacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null) {
                    return;
                }
                ResourceLocation id = ResourceLocation.tryParse(msg.itemId);
                if (id == null) {
                    return;
                }
                DisabledStore.setEnabled(sender, id, msg.enable);
                if (SimulationManager.isActive(sender)) {
                    SimulationManager.requestDeactivate(sender, true);
                } else {
                    Network.sendSync(sender);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // ==================== S2C：同步关闭集合与虚拟槽区间 ====================

    public static class SyncPacket {

        private final Set<String> disabled;
        private final boolean active;
        private final Map<String, int[]> ranges;

        public SyncPacket(Set<String> disabled, boolean active, Map<String, int[]> ranges) {
            this.disabled = disabled;
            this.active = active;
            this.ranges = ranges;
        }

        public static void encode(SyncPacket msg, net.minecraft.network.FriendlyByteBuf buf) {
            buf.writeVarInt(msg.disabled.size());
            for (String s : msg.disabled) {
                buf.writeUtf(s, 256);
            }
            buf.writeBoolean(msg.active);
            buf.writeVarInt(msg.ranges.size());
            for (Map.Entry<String, int[]> e : msg.ranges.entrySet()) {
                buf.writeUtf(e.getKey(), 128);
                buf.writeVarInt(e.getValue()[0]);
                buf.writeVarInt(e.getValue()[1]);
            }
        }

        public static SyncPacket decode(net.minecraft.network.FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Set<String> disabled = new HashSet<>();
            for (int i = 0; i < n; i++) {
                disabled.add(buf.readUtf(256));
            }
            boolean active = buf.readBoolean();
            int m = buf.readVarInt();
            Map<String, int[]> ranges = new HashMap<>();
            for (int i = 0; i < m; i++) {
                String id = buf.readUtf(128);
                ranges.put(id, new int[]{buf.readVarInt(), buf.readVarInt()});
            }
            return new SyncPacket(disabled, active, ranges);
        }

        public static void handle(SyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    top.kuisland.eternalsoul.client.ClientCache.update(msg.disabled, msg.active, msg.ranges));
            ctx.get().setPacketHandled(true);
        }
    }
}
