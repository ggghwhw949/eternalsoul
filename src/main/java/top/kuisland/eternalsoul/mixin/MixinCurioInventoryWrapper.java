package top.kuisland.eternalsoul.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.kuisland.eternalsoul.VirtualGuard;

/**
 * 针对 Curios 玩家 curios 处理器（CurioInventoryWrapper）的三重防线：
 * 1. loseInvalidStack —— 虚拟物品永不进入玩家背包/掉落（任何收缩路径的兜底）
 * 2. saveInventory —— 死亡/克隆路径的存档序列化同样剥离虚拟数据
 * 3. readTag —— 载入前净化旧版本（v1.0.x）污染的存档
 */
@Mixin(targets = "top.theillusivec4.curios.common.capability.CurioInventoryCapability$CurioInventoryWrapper", remap = false)
public abstract class MixinCurioInventoryWrapper {

    @Shadow(remap = false)
    public abstract LivingEntity getWearer();

    @Inject(method = "loseInvalidStack", at = @At("HEAD"), cancellable = true, remap = false)
    private void eternalsoul$voidVirtual(ItemStack stack, CallbackInfo ci) {
        if (VirtualGuard.isVirtual(stack)) {
            ci.cancel();
        }
    }

    // 按名称匹配全部三个重载（(Z)、(Z, Predicate)、(Z, BiPredicate)），
    // 前两者最终都会委托到 BiPredicate 版本；剥离操作幂等，多次执行无害。
    // 注：显式 descriptor 匹配在空 refmap + remap=false 组合下无法命中，故用纯名称。
    @Inject(method = "saveInventory", at = @At("TAIL"), remap = false)
    private void eternalsoul$stripSaved(CallbackInfoReturnable<ListTag> cir) {
        LivingEntity wearer = this.getWearer();
        if (wearer == null) {
            return;
        }
        ListTag list = cir.getReturnValue();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String identifier = entry.getString("Identifier");
            VirtualGuard.stripHandlerTag(entry, wearer, identifier);
            // saveInventory 格式：修饰符列表直接挂在条目的 "Modifiers" 键下
            VirtualGuard.stripModifierList(entry, "Modifiers", identifier);
        }
    }

    @Inject(method = "readTag", at = @At("HEAD"), cancellable = true, remap = false)
    private void eternalsoul$sanitize(Tag tag, CallbackInfo ci) {
        if (tag instanceof CompoundTag compound) {
            VirtualGuard.sanitizeSavedTag(compound);
        }
    }
}
