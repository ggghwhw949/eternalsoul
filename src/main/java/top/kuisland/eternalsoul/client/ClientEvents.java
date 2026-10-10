package top.kuisland.eternalsoul.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.kuisland.eternalsoul.CurioIndex;
import top.kuisland.eternalsoul.EternalSoul;

@Mod.EventBusSubscriber(modid = EternalSoul.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    /** 彩虹名：相邻字符的色相间隔（0~1 环绕） */
    private static final float HUE_STEP = 0.09F;
    /** 彩虹名：色相相位步进周期（毫秒），越小流动越快 */
    private static final long PHASE_MILLIS = 80L;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        while (ClientSetup.OPEN_LIST.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new CuriosListScreen());
            }
        }
    }

    /**
     * 彩虹渐变名字：tooltip 构建时把首行（物品名）替换为逐字符 HSV 着色、
     * 相位随时间流动的组件。纯客户端、替换式实现——不触碰物品公共类，
     * 不会产生额外行；tooltip 每帧重建，动画随渲染自然流动。
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() != EternalSoul.ETERNAL_SOUL.get()) {
            return;
        }
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) {
            return;
        }
        String plain = lines.get(0).getString();
        MutableComponent rainbow = Component.empty();
        long phase = System.currentTimeMillis() / PHASE_MILLIS;
        for (int i = 0; i < plain.length(); i++) {
            int rgb = Mth.hsvToRgb(((phase + i) * HUE_STEP) % 1.0F, 0.75F, 1.0F);
            rainbow.append(Component.literal(String.valueOf(plain.charAt(i)))
                    .withStyle(s -> s.withItalic(true).withColor(TextColor.fromRgb(rgb))));
        }
        lines.set(0, rainbow);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 回到标题界面：清掉客户端镜像与本地索引，避免跨世界残留
        ClientCache.clear();
        CurioIndex.invalidate();
    }
}
