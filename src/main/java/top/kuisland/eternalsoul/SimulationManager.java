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
import net.minecraft.world.entity.ai.attributes.Attribute;
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
import net.minecraftforge.event.server.ServerStoppedEvent;
import top.kuisland.eternalsoul.network.Network;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotAttribute;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.event.CurioAttributeModifierEvent;
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

    /** 每 tick 放置/清空的虚拟物品数量上限（近即时装载；仅防极端巨型包的单tick风暴） */
    private static final int BATCH = 2048;
    /** 看门狗校验间隔（tick） */
    private static final int WATCHDOG_INTERVAL = 20;
    /** 就地开关的批量上限（与 BATCH 对齐；防极端巨型包的单tick风暴） */
    private static final int IN_PLACE_MAX = 2048;

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

    /**
     * 该处理器上属于我们的槽位修饰符数量（name="eternalsoul"，ADDITION +N）。
     * 数据源是 Curios 自身的修饰符同步（SPacketSyncModifiers）——修饰符到位与
     * 容器页面重建由同一个网络包驱动，天然原子：不存在"数据晚于页面构建到达"
     * 的竞态，也无需我们主动重建任何页面。双端一致（服务器本地注册、客户端
     * 由 Curios 同步），且与服务端看门狗校验的是同一份修饰符表。
     */
    private static int ourModifierCount(ICurioStacksHandler stacksHandler) {
        int count = 0;
        for (net.minecraft.world.entity.ai.attributes.AttributeModifier mod
                : stacksHandler.getModifiers().values()) {
            if ("eternalsoul".equals(mod.getName())) {
                count += (int) mod.getAmount();
            }
        }
        return count;
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
                int count = ourModifierCount(e.getValue());
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
                ourVisible += ourModifierCount(e.getValue());
            }
        }
        return ourVisible > 0 ? Math.max(0, visible - ourVisible) : visible;
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

        // 计划 = 全部探测条目（预留槽位制）：禁用/真实佩戴中的物品也占位但留空。
        // 任何开关（含反选/全选）因此都是 O(1) 就地腾空/回填，永不触发重建。
        Map<String, List<Item>> bySlot = new LinkedHashMap<>();
        for (CurioIndex.Entry entry : CurioIndex.get(player)) {
            Item item = itemOf(entry.itemId());
            if (item == null || item == Items.AIR) {
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
        buildPending(player, active, handler);
        ACTIVE.put(player.getUUID(), active);
        VirtualGuard.register(player, active.ranges);
        Network.sendSync(player);
    }

    /**
     * 依据计划构建放置队列，跳过已非空与当前已被玩家关闭的槽位
     * （用于断点续装/重锚/清空中断续装；就地关闭的物品必须保持空槽，
     * 否则任何重锚都会把玩家关掉的模拟重新装回）
     */
    private static void buildPending(ServerPlayer player, Active active, ICuriosItemHandler handler) {
        active.pending.clear();
        Set<String> disabledIds = DisabledStore.load(player);
        Set<Item> wornItems = physicallyWorn(handler);
        for (Map.Entry<String, List<Item>> e : active.plan.entrySet()) {
            int[] range = active.ranges.get(e.getKey());
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (range == null || stacksHandler == null) {
                continue;
            }
            IDynamicStackHandler stacks = stacksHandler.getStacks();
            for (int i = 0; i < e.getValue().size(); i++) {
                Item item = e.getValue().get(i);
                // 真实佩戴中：保持空槽防双倍（O(1)装备处理清空的槽不许被续装回填）
                if (wornItems.contains(item)) {
                    continue;
                }
                ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
                if (key != null && disabledIds.contains(key.toString())) {
                    continue;
                }
                int index = range[0] + i;
                if (index < stacks.getSlots() && stacks.getStackInSlot(index).isEmpty()) {
                    active.pending.add(new PlaceOp(e.getKey(), index, item));
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
        buildPending(player, a, handler);
        Network.sendSync(player);
    }

    // ==================== 就地开关（增量更新） ====================

    /**
     * 服务端权威的开关应用入口（单条与批量共用）：先整体写回玩家的持久化
     * 开关状态；佩戴中且全部目标都在本次激活计划内时，就地腾空/回填对应
     * 虚拟槽位——不动槽位修饰符、不重建整套模拟；否则退回整体重建。
     */
    public static void applyBulkToggles(ServerPlayer player, Map<ResourceLocation, Boolean> changes) {
        if (changes.isEmpty()) {
            Network.sendSync(player);
            return;
        }
        Set<String> disabled = DisabledStore.load(player);
        changes.forEach((id, enable) -> {
            if (enable) {
                disabled.remove(id.toString());
            } else {
                disabled.add(id.toString());
            }
        });
        DisabledStore.saveAll(player, disabled);

        Active a = ACTIVE.get(player.getUUID());
        // 大批量（如整表反选）走分批重建路径（每 tick 80 个），
        // 避免单 tick 内数千次槽位变更与同步包风暴
        boolean inPlace = a != null && a.phase == Phase.PLACING
                && changes.size() <= IN_PLACE_MAX;
        if (inPlace) {
            for (ResourceLocation id : changes.keySet()) {
                Item item = itemOf(id);
                if (item == null || !planned(a, item)) {
                    inPlace = false;
                    break;
                }
            }
        }
        if (inPlace) {
            changes.forEach((id, enable) -> toggleInPlace(player, itemOf(id), enable));
            Network.sendSync(player);
        } else {
            requestDeactivate(player, true);
        }
    }

    private static boolean planned(Active a, Item item) {
        for (List<Item> list : a.plan.values()) {
            if (list.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单项就地开关：物品必须已在激活计划中（激活时处于开启状态）。
     * 开=回填其专属槽位，关=腾空其专属槽位；计划布局与虚拟区间保持
     * 不动，因此不触碰任何槽位修饰符。无法就地处理时返回 false，
     * 由调用方退回整体重建（如激活时关闭、后需新增的物品）。
     */
    private static boolean toggleInPlace(ServerPlayer player, Item item, boolean enable) {
        Active a = ACTIVE.get(player.getUUID());
        ICuriosItemHandler handler = handlerOf(player);
        if (a == null || handler == null || a.phase != Phase.PLACING) {
            return false;
        }
        for (Map.Entry<String, List<Item>> e : a.plan.entrySet()) {
            int idx = e.getValue().indexOf(item);
            if (idx < 0) {
                continue;
            }
            int[] range = a.ranges.get(e.getKey());
            ICurioStacksHandler stacksHandler = handler.getCurios().get(e.getKey());
            if (range == null || stacksHandler == null) {
                return false;
            }
            int slot = range[0] + idx;
            // 同步移除尚未装载的队列条目，避免与本操作竞争
            a.pending.removeIf(op -> op.item() == item);
            if (slot >= stacksHandler.getSlots()) {
                return false;
            }
            ItemStack current = stacksHandler.getStacks().getStackInSlot(slot);
            if (enable) {
                if (!current.isEmpty()) {
                    return VirtualGuard.isVirtual(current); // 已在模拟=成功；被外部占用=重建
                }
                // 玩家已关闭该物品时保持空槽（真实卸下的自动回填也尊重开关）
                ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
                if (key != null && DisabledStore.isDisabled(player, key)) {
                    return true;
                }
                // 真实佩戴中（可能换了槽位）不回填，防双倍效果
                if (physicallyWorn(handler).contains(item)) {
                    return true;
                }
                ItemStack stack = new ItemStack(item);
                VirtualGuard.track(stack);
                handler.setEquippedCurio(e.getKey(), slot, stack);
                return true;
            }
            if (current.isEmpty()) {
                return true; // 本就未装载
            }
            if (!VirtualGuard.isVirtual(current)) {
                return false; // 被外部物品占用 → 重建
            }
            VirtualGuard.untrack(current);
            handler.setEquippedCurio(e.getKey(), slot, ItemStack.EMPTY);
            return true;
        }
        return false;
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
                // 立即触发延迟收缩：访问一次 getStacks() 让 update() 生效，
                // 避免 RANGES 注销后处理器仍处于扩容状态的窗口
                for (String id : toRemove.keySet()) {
                    ICurioStacksHandler sh = handler.getCurios().get(id);
                    if (sh != null) {
                        sh.getStacks();
                    }
                }
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
                buildPending(player, a, handler);
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

    /** 真实佩戴的物品种类（不含虚拟堆；用于激活过滤与续装防双倍） */
    private static Set<Item> physicallyWorn(ICuriosItemHandler handler) {
        Set<Item> worn = new HashSet<>();
        for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
            IDynamicStackHandler stacks = e.getValue().getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (!stack.isEmpty() && !VirtualGuard.isVirtual(stack)) {
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
                    && stacksHandler.getSlots() == range[0] + range[1]; // ③ 恰为区间末尾（尾部锚定，含外部加槽）
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
        Active a = ACTIVE.get(player.getUUID());
        // 佩戴状态下的真实装备变化 → O(1) 就地腾空/回填该物品的虚拟槽位，
        // 不再整表重建（覆盖面大时重建是秒级风暴，也是闪烁的主因）
        if (a != null && a.phase == Phase.PLACING) {
            if (!event.getTo().isEmpty()) {
                Item changed = event.getTo().getItem();
                if (planned(a, changed)) {
                    toggleInPlace(player, changed, false);
                }
            } else if (!event.getFrom().isEmpty()) {
                Item changed = event.getFrom().getItem();
                if (planned(a, changed)) {
                    toggleInPlace(player, changed, true);
                }
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

    /**
     * 虚拟堆的槽位增益屏蔽：Curios 计算饰品属性时，若该堆是我们的虚拟堆，
     * 移除其全部槽位增益（SlotAttribute，"+N 某类槽位"）条目。
     * 理由：此类增益会随虚拟堆的装载/腾空/重锚换位被反复增删，与尾部
     * 锚定互踩形成震荡。屏蔽后物品其余效果照常模拟，真实佩戴时槽位
     * 增益照常生效（真实堆非虚拟堆，不经过此屏蔽）。
     * 服务端装备/卸载路径与 Curios 内部的 cached 清理均经由此事件计算，
     * 三处口径一致，不会产生残留修饰符。
     */
    @SubscribeEvent
    public static void onCurioAttributeModifiers(CurioAttributeModifierEvent event) {
        if (VirtualGuard.isVirtual(event.getItemStack())) {
            for (Attribute attribute : event.getOriginalModifiers().keySet()) {
                if (attribute instanceof SlotAttribute) {
                    event.removeAttribute(attribute);
                }
            }
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
    public static void onServerStopped(ServerStoppedEvent event) {
        // 服务器完全停止（所有存档已落盘、玩家已全部登出）：
        // 清空静态登记，防止单机换世界/多次 /reload 累积泄漏
        // （追踪集内强引用的失联虚拟堆、区间登记与陈旧索引）
        ACTIVE.clear();
        VirtualGuard.clearAll();
        CurioIndex.invalidate();
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
