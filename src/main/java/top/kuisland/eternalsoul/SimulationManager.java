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
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.event.server.ServerStartedEvent;
import top.kuisland.eternalsoul.network.Network;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.event.CurioEquipEvent;
import top.theillusivec4.curios.api.event.CurioUnequipEvent;
import top.theillusivec4.curios.api.event.SlotModifiersUpdatedEvent;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * 服务端模拟管理器（尾部锚定版）。
 * <p>
 * 原理：通过 Curios 临时槽位修饰符扩容，把虚拟饰品堆放入真实处理器的
 * 尾部区间，由 Curios 原生引擎驱动全部效果（tick、属性、事件、同步、
 * 其他模组查询）。
 * <p>
 * 尾部锚定不变量：我们的虚拟区间 [base, base+N) 恒为该槽位类型的最后
 * N 个槽。外部模组扩容（如 +N 槽饰品）触发 SlotModifiersUpdatedEvent
 * 后，通过 retarget 搬迁外部堆并重新锚定——外部饰品槽与模拟互不影响。
 * <p>
 * 显示浓缩：CuriosContainerV2 布局层通过 clampSlotsView / clampVisibleView
 * 钳制显示槽数，界面只呈现真实饰品（永恒之魂本体在真实槽位，自然可见）。
 * <p>
 * 安全防线（VirtualGuard + mixin）：存档序列化剥离虚拟数据、虚拟物品
 * 永不进入玩家背包、旧档载入净化、收缩等待界面关闭、看门狗自愈。
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
        boolean reanchorRequested;
        boolean selfMutating;
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

    /** 双端通用：显示层钳制——隐藏我们尾部的虚拟槽数（CuriosContainerV2 mixin 调用） */
    public static int clampSlotsView(LivingEntity wearer, IDynamicStackHandler stacksInstance,
                                     int real) {
        if (wearer == null) {
            return real;
        }
        ICuriosItemHandler handler = CuriosApi.getCuriosInventory(wearer).resolve().orElse(null);
        if (handler == null) {
            return real;
        }
        for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
            if (e.getValue().getStacks() == stacksInstance) {
                int count = tailCountFor(wearer, e.getKey());
                return count > 0 ? Math.max(0, real - count) : real;
            }
        }
        return real;
    }

    /** 双端通用：显示层钳制——总可见槽数扣除可见类型的虚拟槽数 */
    public static int clampVisibleView(LivingEntity wearer, ICuriosItemHandler handler) {
        int visible = handler.getVisibleSlots();
        if (wearer == null) {
            return visible;
        }
        int ourVisible = 0;
        for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
            if (e.getValue().isVisible()) {
                ourVisible += tailCountFor(wearer, e.getKey());
            }
        }
        return ourVisible > 0 ? Math.max(0, visible - ourVisible) : visible;
    }

    private static int tailCountFor(LivingEntity wearer, String identifier) {
        return wearer.level().isClientSide
                ? top.kuisland.eternalsoul.client.ClientCache.countFor(identifier)
                : VirtualGuard.countFor(wearer, identifier);
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
        active.selfMutating = true;
        try {
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
        } finally {
            active.selfMutating = false;
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

    // ==================== 停用 / 重建 / 重锚 ====================

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

    /**
     * 核心搬迁原语：清空我们的虚拟堆；把区间内被外部塞入的真实堆退还玩家；
     * 把区间之外（外部扩容槽位里）的真实堆先抬起，收缩后放回正确位置。
     * regrow = true 时重新扩容并进入续装阶段（重锚）。
     */
    private static void retarget(ServerPlayer player, Active a, ICuriosItemHandler handler,
                                 boolean regrow) {
        a.selfMutating = true;
        try {
            Multimap<String, AttributeModifier> toRemove = HashMultimap.create();
            Map<String, Map<Integer, ItemStack>> reseat = new LinkedHashMap<>();
            List<ItemStack> giveBack = new ArrayList<>();

            for (Map.Entry<String, int[]> e : a.ranges.entrySet()) {
                String id = e.getKey();
                int[] range = e.getValue();
                ICurioStacksHandler stacksHandler = handler.getCurios().get(id);
                if (stacksHandler == null) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                int size = stacks.getSlots();
                int ourEnd = range[0] + range[1];
                for (int i = range[0]; i < size; i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    if (VirtualGuard.isVirtual(stack)) {
                        VirtualGuard.untrack(stack);
                    } else if (i < ourEnd) {
                        giveBack.add(stack); // 外部塞进我们区间的真实堆：退还
                    } else {
                        reseat.computeIfAbsent(id, k -> new LinkedHashMap<>())
                                .put(i - range[1], stack); // 外部扩容槽：收缩后下移归位
                    }
                    handler.setEquippedCurio(id, i, ItemStack.EMPTY);
                }
                toRemove.put(id, new AttributeModifier(uuidFor(id),
                        "eternalsoul", 0, AttributeModifier.Operation.ADDITION));
            }
            if (!toRemove.isEmpty()) {
                handler.removeSlotModifiers(toRemove);
            }
            for (Map.Entry<String, Map<Integer, ItemStack>> e : reseat.entrySet()) {
                ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
                if (stacksHandler == null) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                for (Map.Entry<Integer, ItemStack> s : e.getValue().entrySet()) {
                    if (s.getKey() < stacks.getSlots()) {
                        handler.setEquippedCurio(e.getKey(), s.getKey(), s.getValue());
                    } else {
                        giveBack.add(s.getValue());
                    }
                }
            }
            giveBack.forEach(stack -> ItemHandlerHelper.giveItemToPlayer(player, stack));

            if (regrow) {
                Multimap<String, AttributeModifier> toAdd = HashMultimap.create();
                for (Map.Entry<String, List<Item>> e : a.plan.entrySet()) {
                    if (!a.ranges.containsKey(e.getKey())) {
                        continue;
                    }
                    ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
                    if (stacksHandler == null) {
                        continue;
                    }
                    int base = stacksHandler.getSlots();
                    a.ranges.put(e.getKey(), new int[]{base, e.getValue().size()});
                    toAdd.put(e.getKey(), new AttributeModifier(uuidFor(e.getKey()),
                            "eternalsoul", e.getValue().size(), AttributeModifier.Operation.ADDITION));
                }
                if (!toAdd.isEmpty()) {
                    handler.addTransientSlotModifiers(toAdd);
                }
                a.phase = Phase.PLACING;
                buildPending(a, handler);
                VirtualGuard.register(player, a.ranges);
                Network.sendSync(player);
            }
        } finally {
            a.selfMutating = false;
        }
    }

    /** 检查尾部锚定不变量：所有区间是否仍为各自处理器的队尾 */
    private static boolean isTailAnchored(Active a, ICuriosItemHandler handler) {
        for (Map.Entry<String, int[]> e : a.ranges.entrySet()) {
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (stacksHandler == null) {
                return false;
            }
            if (stacksHandler.getSlots() != e.getValue()[0] + e.getValue()[1]) {
                return false;
            }
        }
        return true;
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
        retarget(player, a, handler, false);
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
            // 外部槽位数量变化 → 重锚或重建
            if (a.reanchorRequested && !a.selfMutating) {
                a.reanchorRequested = false;
                if (!isTailAnchored(a, handler)) {
                    retarget(player, a, handler, true);
                    return;
                }
            }
            placeBatch(a, handler);
            if (a.pending.isEmpty() && player.tickCount % WATCHDOG_INTERVAL == 0) {
                verify(player, a, handler);
            }
            return;
        }

        // CLEARING：分批清空
        clearBatch(a, handler);
        if (!a.pending.isEmpty()) {
            return;
        }
        // 等待没有任何容器界面打开，避免客户端槽位收缩导致越界崩溃
        if (player.containerMenu != player.inventoryMenu) {
            return;
        }
        retarget(player, a, handler, false);
        boolean reactivate = a.reactivate && isWearingSoul(player);
        ACTIVE.remove(player.getUUID());
        VirtualGuard.unregister(player);
        if (reactivate) {
            activate(player);
        } else {
            Network.sendSync(player);
        }
    }

    private static void placeBatch(Active a, ICuriosItemHandler handler) {
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

    private static void clearBatch(Active a, ICuriosItemHandler handler) {
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
                    if (i < stacks.getSlots()) {
                        ItemStack stack = stacks.getStackInSlot(i);
                        if (!stack.isEmpty() && !VirtualGuard.isVirtual(stack)) {
                            healthy = false; // 槽位被外部塞入了真实物品
                        }
                    }
                }
            }
            if (!healthy) {
                hardReset(player);
                return;
            }
        }
    }

    /** 状态被外部破坏：搬迁清理全部虚拟槽并重建 */
    private static void hardReset(ServerPlayer player) {
        Active a = ACTIVE.get(player.getUUID());
        ICuriosItemHandler handler = handlerOf(player);
        if (a == null || handler == null) {
            return;
        }
        retarget(player, a, handler, false);
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

    /** 外部（其他模组）改变了任一槽位类型的数量 → 下一 tick 重锚 */
    @SubscribeEvent
    public static void onSlotModifiersUpdated(SlotModifiersUpdatedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Active a = ACTIVE.get(player.getUUID());
        if (a == null || a.selfMutating) {
            return;
        }
        a.reanchorRequested = true;
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
        } else {
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
