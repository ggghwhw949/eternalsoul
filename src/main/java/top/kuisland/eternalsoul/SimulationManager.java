package top.kuisland.eternalsoul;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.server.ServerStartedEvent;
import top.kuisland.eternalsoul.network.Network;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.event.CurioEquipEvent;
import top.theillusivec4.curios.api.event.CurioUnequipEvent;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * 服务端模拟管理器。
 * <p>
 * 原理：佩戴永恒之魂时，通过 Curios 的临时槽位修饰符
 * {@link ICuriosItemHandler#addTransientSlotModifiers(Multimap)} 为各槽类型扩容，
 * 再用 {@link ICuriosItemHandler#setEquippedCurio(String, int, ItemStack)} 把虚拟
 * 饰品堆放入新增的槽位。此后 Curios 自身的 tick 引擎会照常驱动这些饰品：
 * curioTick（主动效果）、属性修饰符（被动效果）、onEquip/onUnequip、
 * CurioChangeEvent、客户端同步、以及其他模组的 isEquipped/findCurios 查询。
 * <p>
 * 防复制：虚拟槽位的取出（CurioUnequipEvent DENY）与放入（CurioEquipEvent DENY）
 * 均被拦截，玩家无法从 GUI 或快捷移动拿走虚拟物品。
 * <p>
 * 卸下流程分两阶段：先清空虚拟槽（由 tick 引擎自动移除属性并发同步包），
 * 下一 tick 再移除槽位修饰符收缩槽位，避免 loseStacks 把虚拟物品掉落成实体。
 * 死亡与登出则立即清理（实体将被丢弃，无需属性清理）。
 */
public final class SimulationManager {

    /** 每个佩戴者的激活状态：各槽位类型的 [base, added) 虚拟区间 */
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private static class Active {
        final Map<String, int[]> ranges = new LinkedHashMap<>();
        boolean cleared;
        boolean reactivate;
        int phaseTick = -1;
    }

    private SimulationManager() {
    }

    // ==================== 激活 / 停用 ====================

    public static boolean isActive(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    public static Map<String, int[]> ranges(Player player) {
        Active a = ACTIVE.get(player.getUUID());
        return a == null ? Map.of() : Map.copyOf(a.ranges);
    }

    private static boolean isWearingSoul(Player player) {
        return CuriosApi.getCuriosInventory(player)
                .map(h -> h.isEquipped(EternalSoul.ETERNAL_SOUL.get()))
                .orElse(false);
    }

    /** 双方通用：判断槽位是否处于虚拟区间（服务端查 ACTIVE，客户端查同步缓存） */
    public static boolean isVirtualSlot(LivingEntity wearer, String identifier, int index) {
        if (wearer == null || identifier == null) {
            return false;
        }
        if (wearer.level().isClientSide) {
            return top.kuisland.eternalsoul.client.ClientCache.isVirtualSlot(identifier, index);
        }
        Active a = ACTIVE.get(wearer.getUUID());
        if (a == null) {
            return false;
        }
        int[] range = a.ranges.get(identifier);
        return range != null && index >= range[0] && index < range[0] + range[1];
    }

    private static UUID uuidFor(String identifier) {
        return UUID.nameUUIDFromBytes(
                ("eternalsoul:" + identifier).getBytes(StandardCharsets.UTF_8));
    }

    /** 佩戴永恒之魂：扩容并放入所有启用的虚拟饰品 */
    static void activate(ServerPlayer player) {
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).resolve().orElse(null);
        if (handler == null || ACTIVE.containsKey(player.getUUID())) {
            return;
        }

        Set<Item> worn = physicallyWorn(handler);
        Map<String, List<Item>> bySlot = new LinkedHashMap<>();
        for (CurioIndex.Entry entry : CurioIndex.get(player)) {
            if (DisabledStore.isDisabled(player, entry.itemId())) {
                continue;
            }
            Item item = itemOf(entry.itemId());
            if (item == null || item == Items.AIR || worn.contains(item)) {
                continue;
            }
            bySlot.computeIfAbsent(entry.slot(), k -> new ArrayList<>()).add(item);
        }

        Active active = new Active();
        Multimap<String, AttributeModifier> modifiers = HashMultimap.create();
        for (Map.Entry<String, List<Item>> e : bySlot.entrySet()) {
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (stacksHandler == null) {
                continue;
            }
            int base = stacksHandler.getSlots();
            int count = e.getValue().size();
            active.ranges.put(e.getKey(), new int[]{base, count});
            modifiers.put(e.getKey(), new AttributeModifier(uuidFor(e.getKey()),
                    "eternalsoul", count, AttributeModifier.Operation.ADDITION));
        }
        if (!modifiers.isEmpty()) {
            handler.addTransientSlotModifiers(modifiers);
        }
        for (Map.Entry<String, List<Item>> e : bySlot.entrySet()) {
            int[] range = active.ranges.get(e.getKey());
            if (range == null) {
                continue;
            }
            int i = 0;
            for (Item item : e.getValue()) {
                handler.setEquippedCurio(e.getKey(), range[0] + i, new ItemStack(item));
                i++;
            }
        }
        ACTIVE.put(player.getUUID(), active);
        Network.sendSync(player);
    }

    /** 请求停用（可携带重新激活标记），供开关变化 / 物理装备变化时重建 */
    public static void requestDeactivate(ServerPlayer player, boolean reactivate) {
        Active a = ACTIVE.get(player.getUUID());
        if (a == null) {
            return;
        }
        if (!a.cleared) {
            clearVirtualStacks(player, a);
            a.cleared = true;
            a.phaseTick = player.tickCount;
        }
        if (reactivate) {
            a.reactivate = true;
        }
    }

    /** 死亡 / 登出等实体即将失效的场景：立即完整清理 */
    private static void immediateClean(ServerPlayer player) {
        Active a = ACTIVE.remove(player.getUUID());
        if (a == null) {
            return;
        }
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).resolve().orElse(null);
        if (handler == null) {
            return;
        }
        clearVirtualStacks(player, a);
        removeModifiers(handler, a);
    }

    private static void clearVirtualStacks(ServerPlayer player, Active a) {
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).resolve().orElse(null);
        if (handler == null) {
            return;
        }
        for (Map.Entry<String, int[]> e : a.ranges.entrySet()) {
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (stacksHandler == null) {
                continue;
            }
            int slots = stacksHandler.getSlots();
            for (int i = e.getValue()[0]; i < e.getValue()[0] + e.getValue()[1]; i++) {
                if (i < slots) {
                    handler.setEquippedCurio(e.getKey(), i, ItemStack.EMPTY);
                }
            }
        }
    }

    private static void removeModifiers(ICuriosItemHandler handler, Active a) {
        Multimap<String, AttributeModifier> modifiers = HashMultimap.create();
        for (String identifier : a.ranges.keySet()) {
            modifiers.put(identifier, new AttributeModifier(uuidFor(identifier),
                    "eternalsoul", 0, AttributeModifier.Operation.ADDITION));
        }
        handler.removeSlotModifiers(modifiers);
    }

    private static Set<Item> physicallyWorn(ICuriosItemHandler handler) {
        Set<Item> worn = new HashSet<>();
        for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
            IDynamicStackHandler stacks = e.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    worn.add(stack.getItem());
                }
            }
        }
        return worn;
    }

    // ==================== 事件 ====================

    @SubscribeEvent
    public static void onCurioChange(CurioChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.level().isClientSide) {
            return;
        }
        // 我们自己放置/清空的虚拟槽变化：忽略，避免自我触发
        if (isVirtualSlot(player, event.getIdentifier(), event.getSlotIndex())) {
            return;
        }
        boolean toSoul = event.getTo().is(EternalSoul.ETERNAL_SOUL.get());
        boolean fromSoul = event.getFrom().is(EternalSoul.ETERNAL_SOUL.get());

        if (toSoul != fromSoul) {
            if (toSoul && !ACTIVE.containsKey(player.getUUID())) {
                activate(player);
            } else if (fromSoul && ACTIVE.containsKey(player.getUUID())
                    && !isWearingSoul(player)) {
                requestDeactivate(player, false);
            }
            return;
        }
        // 佩戴状态下，真实槽位装备了正在模拟的物品 → 重建避免双重效果
        if (ACTIVE.containsKey(player.getUUID()) && !toSoul) {
            Item changed = event.getTo().isEmpty() ? event.getFrom().getItem()
                    : event.getTo().getItem();
            if (isSimulatedItem(player, changed)) {
                requestDeactivate(player, true);
            }
        }
    }

    private static boolean isSimulatedItem(ServerPlayer player, Item item) {
        Active a = ACTIVE.get(player.getUUID());
        if (a == null) {
            return false;
        }
        return CuriosApi.getCuriosInventory(player)
                .map(h -> h.findCurios(item).stream()
                        .anyMatch(r -> isVirtualSlot(player, r.slotContext().identifier(),
                                r.slotContext().index())))
                .orElse(false);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.side != net.minecraftforge.fml.LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        Active a = ACTIVE.get(player.getUUID());
        if (a == null || !a.cleared || a.phaseTick < 0
                || player.tickCount <= a.phaseTick) {
            return;
        }
        // 阶段二：属性已被 tick 引擎移除，现在安全收缩槽位
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).resolve().orElse(null);
        if (handler == null) {
            ACTIVE.remove(player.getUUID());
            return;
        }
        removeModifiers(handler, a);
        boolean reactivate = a.reactivate && isWearingSoul(player);
        ACTIVE.remove(player.getUUID());
        if (reactivate) {
            activate(player);
        } else {
            Network.sendSync(player);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            immediateClean(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            immediateClean(player);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        EternalSoulConfig.loadOrCreate();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!DisabledStore.isInitialized(player)) {
                DisabledStore.initDefaults(player, EternalSoulConfig.defaults());
            }
            Network.sendSync(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isWearingSoul(player)) {
            activate(player);
        }
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        CurioIndex.invalidate();
        EternalSoulConfig.loadOrCreate();
        if (event.getPlayer() == null) {
            if (ServerLifecycleHooks.getCurrentServer() != null) {
                ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()
                        .forEach(p -> requestDeactivate(p, isActive(p)));
            }
        } else if (event.getPlayer() != null) {
            requestDeactivate(event.getPlayer(), isActive(event.getPlayer()));
        }
    }

    // ==================== 防取出 / 防放入 ====================

    @SubscribeEvent
    public static void onCurioUnequip(CurioUnequipEvent event) {
        if (isVirtualSlot(event.getEntity(), event.getSlotContext().identifier(),
                event.getSlotContext().index())) {
            event.setResult(Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void onCurioEquip(CurioEquipEvent event) {
        if (isVirtualSlot(event.getEntity(), event.getSlotContext().identifier(),
                event.getSlotContext().index())) {
            event.setResult(Event.Result.DENY);
        }
    }

    /** 通过注册表 id 取物品 */
    private static Item itemOf(ResourceLocation id) {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
    }
}
