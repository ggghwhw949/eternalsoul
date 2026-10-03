package top.kuisland.eternalsoul.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kuisland.eternalsoul.VirtualGuard;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.common.inventory.CurioStacksHandler;

/**
 * 存档防线：CurioStacksHandler.serializeNBT 是 writeTag（玩家存档）的底层
 * 序列化路径。在结果标签中剥离虚拟物品与本模组的槽位修饰符，
 * 保证任何时刻写入磁盘的 NBT 都是"从未佩戴过永恒之魂"的干净状态。
 * 注意：getSyncTag（客户端同步）不走此路径，虚拟物品照常同步。
 */
@Mixin(CurioStacksHandler.class)
public abstract class MixinCurioStacksHandler {

    @Shadow(remap = false)
    private ICuriosItemHandler itemHandler;

    @Shadow(remap = false)
    private String identifier;

    @Inject(method = "serializeNBT", at = @At("TAIL"), remap = false)
    private void eternalsoul$stripVirtual(CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        LivingEntity wearer = this.itemHandler != null ? this.itemHandler.getWearer() : null;
        if (wearer != null) {
            VirtualGuard.stripHandlerTag(tag, wearer, this.identifier);
        }
    }
}
