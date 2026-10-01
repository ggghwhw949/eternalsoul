package top.kuisland.eternalsoul;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * 永恒之魂：可放入任意饰品栏（通过 curios:curio 通用物品标签实现），
 * 外观使用原版下界之星贴图。
 */
public class EternalSoulItem extends Item implements ICurioItem {

    public EternalSoulItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("eternalsoul.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("eternalsoul.tooltip.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("eternalsoul.tooltip.3",
                Component.keybind("key.eternalsoul.open_list")).withStyle(ChatFormatting.DARK_GRAY));
    }
}
