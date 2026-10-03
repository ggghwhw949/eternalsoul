package top.kuisland.eternalsoul.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.kuisland.eternalsoul.SimulationManager;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import top.theillusivec4.curios.common.inventory.container.CuriosContainerV2;

/**
 * 界面浓缩：Curios V2 容器的 setPage 布局循环看不到虚拟槽位，
 * 界面呈现"所有临时饰品栏全部浓缩在永恒之魂上"的效果。
 * 只影响显示层，数据层（tick/属性/事件/同步/capability 查询）不受影响。
 * require = 0：若 Curios 内部结构变化则静默回退为可见，不崩溃。
 */
@Mixin(CuriosContainerV2.class)
public abstract class MixinCuriosContainerV2 {

    @Redirect(method = "setPage", at = @At(value = "INVOKE",
            target = "Ltop/theillusivec4/curios/api/type/inventory/IDynamicStackHandler;getSlots()I"),
            remap = false, require = 0)
    private int eternalsoul$clampLoopBound(IDynamicStackHandler stacks) {
        CuriosContainerV2 self = (CuriosContainerV2) (Object) this;
        return SimulationManager.clampSlotsView(self.player, stacks, stacks.getSlots());
    }

    @Redirect(method = "setPage", at = @At(value = "INVOKE",
            target = "Ltop/theillusivec4/curios/api/type/capability/ICuriosItemHandler;getVisibleSlots()I"),
            remap = false, require = 0)
    private int eternalsoul$clampVisibleTotal(ICuriosItemHandler handler) {
        CuriosContainerV2 self = (CuriosContainerV2) (Object) this;
        return SimulationManager.clampVisibleView(self.player, handler);
    }
}
