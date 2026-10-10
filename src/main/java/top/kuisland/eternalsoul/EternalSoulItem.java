package top.kuisland.eternalsoul;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * 永恒之魂：可放入任意饰品栏（通过 curios:curio 通用物品标签实现），
 * 外观使用原版下界之星贴图。名字呈彩虹渐变（逐字符HSV着色，相位随时间推移）。
 */
public class EternalSoulItem extends Item implements ICurioItem {

    /** 相邻字符的色相间隔（0~1 环绕） */
    private static final float HUE_STEP = 0.09F;
    /** 色相相位步进周期（毫秒）：数值越小流动越快 */
    private static final long PHASE_MILLIS = 80L;

    public EternalSoulItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canEquipFromUse(SlotContext slotContext, ItemStack stack) {
        return true;
    }

    /** 彩虹渐变名字：逐字符按时间相位取 HSV 色相，保持史诗品质的斜体 */
    @Override
    public Component getName(ItemStack stack) {
        String plain = super.getName(stack).getString();
        MutableComponent name = Component.empty();
        long phase = System.currentTimeMillis() / PHASE_MILLIS;
        for (int i = 0; i < plain.length(); i++) {
            int rgb = Mth.hsvToRgb(((phase + i) * HUE_STEP) % 1.0F, 0.75F, 1.0F);
            name.append(Component.literal(String.valueOf(plain.charAt(i)))
                    .withStyle(s -> s.withItalic(true).withColor(TextColor.fromRgb(rgb))));
        }
        return name;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("eternalsoul.tooltip.divinity")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("eternalsoul.tooltip.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("eternalsoul.tooltip.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("eternalsoul.tooltip.3",
                Component.keybind("key.eternalsoul.open_list")).withStyle(ChatFormatting.DARK_GRAY));
    }
}
