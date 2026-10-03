package top.kuisland.eternalsoul;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
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
import net.minecraftforge.fml.LogicalSide;
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
 * 服务端模拟管理器（分批状态机版）。
 * <p>
 * 原理：通过 Curios 临时槽位修饰符扩容，把虚拟饰品堆放入真实处理器，
 * 由 Curios 原生引擎驱动全部效果（tick、属性、事件、同步、其他模组查询）。
 * <p>
 * 安全防线（配合 VirtualGuard 与 mixin）：
 * - 存档序列化时剥离虚拟数据（serializeNBT / saveInventory）
 * - 虚拟物品永不进入玩家背包（loseInvalidStack 虚空抹除）
 * - 旧版污染存档载入前净化（readTag）
 * - 收缩槽位必须等待没有任何容器界面打开（防客户端槽位越界崩溃）
 * - 每 20 tick 看门狗自愈（其他模组破坏状态时安全重建）
 * <p>
 * 性能：装载/清空均分批进行（每 tick BATCH 个），摊平事件与网络风暴。
 * 状态机：PLACING（分批放入） ⇄ CLEARING（分批清空） → 终结（收缩/重建）。
 */
public final class SimulationManager {

    /** 每 tick 放置/清空的虚拟物品数量上限 */
    private static final int BATCH = 80;
    /** 看门狗校验间隔（tick） */
    private static final int WATCHDOG_INTERVAL = 20;

    private enum Phase {PLACING, CLEARING}

    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    /** 放置操作：槽位类型 + 槽位索引 + 目标物品 */
    private record PlaceOp(String identifier, int index, Item item) {
    }

    private static class Active {
        final Map<String, int[]> ranges = new LinkedHashMap<>();
        final Map<String, List<Item>> plan = new LinkedHashMap<>();
        final List<PlaceOp> pending = new ArrayList<>();
        Phase phase = Phase.PLACING;
        boolean reactivate;
    }

    private SimulationManager() {
    }

    // ==================== 状态查询 ====================

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

    /** 双端通用：判断槽位是否处于虚拟区间 */
    public static boolean isVirtualSlot(LivingEntity wearer, String identifier, int index) {
        if (wearer == null || identifier == null) {
            return false;
        }
        if (wearer.level().isClientSide) {
            return top.kuisland.eternalsoul.client.ClientCache.isVirtualSlot(identifier, index);
        }
        return VirtualGuard.isVirtualIndex(wearer, identifier, index);
    }

    private static UUID uuidFor(String identifier) {
        return VirtualGuard.modifierUuid(identifier);
    }

    private static ICuriosItemHandler handlerOf(Player player) {
        return CuriosApi.getCuriosInventory(player).resolve().orElse(null);
    }

    private static Item itemOf(ResourceLocation id) {
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
    }

    // ==================== 激活 ====================

    static void activate(ServerPlayer player) {
        ICuriosItemHandler handler = handlerOf(player);
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
            active.ranges.put(e.getKey(), new int[]{base, e.getValue().size()});
            active.plan.put(e.getKey(), e.getValue());
            modifiers.put(e.getKey(), new AttributeModifier(uuidFor(e.getKey()),
                    "eternalsoul", e.getValue().size(), AttributeModifier.Operation.ADDITION));
        }
        if (!modifiers.isEmpty()) {
            handler.addTransientSlotModifiers(modifiers);
        }
        buildPending(active, handler);
        ACTIVE.put(player.getUUID(), active);
        VirtualGuard.register(player, active.ranges);
        Network.sendSync(player);
    }

    /** 依据计划构建放置队列，跳过已非空的槽位（用于断点续装） */
    private static void buildPending(Active active, ICuriosItemHandler handler) {
        active.pending.clear();
        for (Map.Entry<String, List<Item>> e : active.plan.entrySet()) {
            int[] range = active.ranges.get(e.getKey());
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (range == null || stacksHandler == null) {
                continue;
            }
            IDynamicStackHandler stacks = stacksHandler.getStacks();
            for (int i = 0; i < e.getValue().size(); i++) {
                int index = range[0] + i;
                if (index < stacks.getSlots() && stacks.getStackInSlot(index).isEmpty()) {
                    active.pending.add(new PlaceOp(e.getKey(), index, e.getValue().get(i)));
                }
            }
        }
    }

    // ==================== 停用 / 重建 ====================

    public static void requestDeactivate(ServerPlayer player, boolean reactivate) {
        Active a = ACTIVE.get(player.getUUID());
        if (a == null) {
            return;
        }
        if (a.phase == Phase.PLACING) {
            a.phase = Phase.CLEARING;
            buildClearQueue(a);
        }
        if (reactivate) {
            a.reactivate = true;
        }
    }

    /** 构建清空队列：虚拟区间内的全部槽位 */
    private static void buildClearQueue(Active a) {
        a.pending.clear();
        for (Map.Entry<String, List<Item>> e : a.plan.entrySet()) {
            int[] range = a.ranges.get(e.getKey());
            if (range == null) {
                continue;
            }
            for (int i = 0; i < e.getValue().size(); i++) {
                a.pending.add(new PlaceOp(e.getKey(), range[0] + i, e.getValue().get(i)));
            }
        }
    }

    /** 清空途中重新佩戴：取消清空，恢复续装 */
    private static void resumePlacing(ServerPlayer player) {
        Active a = ACTIVE.get(player.getUUID());
        ICuriosItemHandler handler = handlerOf(player);
        if (a == null || handler == null || a.phase != Phase.CLEARING) {
            return;
        }
        a.phase = Phase.PLACING;
        a.reactivate = false;
        buildPending(a, handler);
        Network.sendSync(player);
    }

    /** 死亡 / 登出等实体即将失效的场景：立即完整清理 */
    private static void immediateClean(ServerPlayer player) {
        Active a = ACTIVE.remove(player.getUUID());
        if (a == null) {
            return;
        }
        ICuriosItemHandler handler = handlerOf(player);
        VirtualGuard.unregister(player);
        if (handler == null) {
            return;
        }
        clearAllVirtualStacks(player, a, handler);
        removeModifiers(handler, a);
    }

    private static void clearAllVirtualStacks(ServerPlayer player, Active a,
                                              ICuriosItemHandler handler) {
        for (Map.Entry<String, int[]> e : a.ranges.entrySet()) {
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (stacksHandler == null) {
                continue;
            }
            IDynamicStackHandler stacks = stacksHandler.getStacks();
            int slots = stacks.getSlots();
            for (int i = e.getValue()[0]; i < e.getValue()[0] + e.getValue()[1]; i++) {
                if (i < slots) {
                    VirtualGuard.untrack(stacks.getStackInSlot(i));
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

    // ==================== 每 tick 推进 ====================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        Active a = ACTIVE.get(player.getUUID());
        if (a == null) {
            return;
        }
        ICuriosItemHandler handler = handlerOf(player);
        if (handler == null) {
            ACTIVE.remove(player.getUUID());
            VirtualGuard.unregister(player);
            return;
        }

        if (a.phase == Phase.PLACING) {
            placeBatch(player, a, handler);
            // 看门狗：装载完成后周期性自检
            if (a.pending.isEmpty() && player.tickCount % WATCHDOG_INTERVAL == 0) {
                verify(player, a, handler);
            }
            return;
        }

        // CLEARING：分批清空
        clearBatch(player, a, handler);
        if (!a.pending.isEmpty()) {
            return;
        }
        // 等待没有任何容器界面打开，避免客户端槽位收缩导致越界崩溃
        if (player.containerMenu != player.inventoryMenu) {
            return;
        }
        removeModifiers(handler, a);
        boolean reactivate = a.reactivate && isWearingSoul(player);
        ACTIVE.remove(player.getUUID());
        VirtualGuard.unregister(player);
        if (reactivate) {
            activate(player);
        } else {
            Network.sendSync(player);
        }
    }

    private static void placeBatch(ServerPlayer player, Active a, ICuriosItemHandler handler) {
        int budget = BATCH;
        while (budget-- > 0 && !a.pending.isEmpty()) {
            PlaceOp op = a.pending.remove(a.pending.size() - 1);
            ICurioStacksHandler stacksHandler = handler.getCurios().get(op.identifier());
            if (stacksHandler == null) {
                continue;
            }
            if (op.index() >= stacksHandler.getSlots()) {
                continue; // 槽位被外部收缩，跳过（看门狗会重建）
            }
            ItemStack stack = new ItemStack(op.item());
            VirtualGuard.track(stack);
            handler.setEquippedCurio(op.identifier(), op.index(), stack);
        }
    }

    private static void clearBatch(ServerPlayer player, Active a, ICuriosItemHandler handler) {
        int budget = BATCH;
        while (budget-- > 0 && !a.pending.isEmpty()) {
            PlaceOp op = a.pending.remove(a.pending.size() - 1);
            ICurioStacksHandler stacksHandler = handler.getCurios().get(op.identifier());
            if (stacksHandler == null) {
                continue;
            }
            IDynamicStackHandler stacks = stacksHandler.getStacks();
            if (op.index() < stacks.getSlots()) {
                VirtualGuard.untrack(stacks.getStackInSlot(op.index()));
                handler.setEquippedCurio(op.identifier(), op.index(), ItemStack.EMPTY);
            }
        }
    }

    // ==================== 看门狗 ====================

    private static void verify(ServerPlayer player, Active a, ICuriosItemHandler handler) {
        for (Map.Entry<String, int[]> e : a.ranges.entrySet()) {
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            int[] range = e.getValue();
            boolean healthy = stacksHandler != null
                    && stacksHandler.getModifiers().containsKey(uuidFor(e.getKey()))
                    && stacksHandler.getSlots() >= range[0] + range[1];
            if (healthy) {
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                for (int i = range[0]; i < range[0] + range[1] && healthy; i++) {
                    if (i < stacks.getSlots() && !stacks.getStackInSlot(i).isEmpty()
                            && !VirtualGuard.isVirtual(stacks.getStackInSlot(i))) {
                        healthy = false; // 槽位被外部塞入了真实物品
                    }
                }
            }
            if (!healthy) {
                hardReset(player);
                return;
            }
        }
    }

    /** 状态被外部破坏：虚空清理全部虚拟槽并重建 */
    private static void hardReset(ServerPlayer player) {
        Active a = ACTIVE.get(player.getUUID());
        ICuriosItemHandler handler = handlerOf(player);
        if (a == null || handler == null) {
            return;
        }
        clearAllVirtualStacks(player, a, handler);
        removeModifiers(handler, a);
        ACTIVE.remove(player.getUUID());
        VirtualGuard.unregister(player);
        if (isWearingSoul(player)) {
            activate(player);
        } else {
            Network.sendSync(player);
        }
    }

    // ==================== 事件 ====================

    @SubscribeEvent
    public static void onCurioChange(CurioChangeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.level().isClientSide) {
            return;
        }
        if (isVirtualSlot(player, event.getIdentifier(), event.getSlotIndex())) {
            return; // 我们自己的放置/清空变化
        }
        boolean toSoul = event.getTo().is(EternalSoul.ETERNAL_SOUL.get());
        boolean fromSoul = event.getFrom().is(EternalSoul.ETERNAL_SOUL.get());

        if (toSoul) {
            if (!ACTIVE.containsKey(player.getUUID())) {
                activate(player);
            } else if (ACTIVE.get(player.getUUID()).phase == Phase.CLEARING) {
                resumePlacing(player); // 清空途中戴回：取消清空继续装
            }
            return;
        }
        if (fromSoul && ACTIVE.containsKey(player.getUUID()) && !isWearingSoul(player)) {
            requestDeactivate(player, false);
            return;
        }
        // 佩戴状态下，真实槽位装备了正在模拟的物品 → 重建避免双重效果
        if (ACTIVE.containsKey(player.getUUID()) && !event.getTo().isEmpty()) {
            Item changed = event.getTo().getItem();
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
        for (Map.Entry<String, List<Item>> e : a.plan.entrySet()) {
            if (e.getValue().contains(item)
                    && VirtualGuard.isVirtualIndex(player, e.getKey(), 0)) {
                return true;
            }
        }
        return false;
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
    public static void onServerStarted(ServerStartedEvent event) {
        EternalSoulConfig.loadOrCreate();
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
}
