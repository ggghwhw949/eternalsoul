package top.kuisland.eternalsoul.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import top.kuisland.eternalsoul.CurioIndex;
import top.kuisland.eternalsoul.EternalSoul;
import top.theillusivec4.curios.api.CuriosApi;

@Mod.EventBusSubscriber(modid = EternalSoul.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

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
        // 客户端持有审计：每5秒记录客户端饰品处理器中的非空堆计数与样本，
        // 用于判定"服务端已清槽但客户端仍持有"类同步问题（如眼罩遮屏不消失）
        LocalPlayer player = mc.player;
        if (player != null && player.tickCount % 100 == 0) {
            int total = 0;
            List<String> sample = new ArrayList<>();
            var resolved = CuriosApi.getCuriosInventory(player);
            if (resolved.isPresent()) {
                for (var e : resolved.resolve().orElseThrow().getCurios().entrySet()) {
                    var stacks = e.getValue().getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        ItemStack s = stacks.getStackInSlot(i);
                        if (!s.isEmpty()) {
                            total++;
                            if (sample.size() < 8) {
                                sample.add(s.getItem() + "@" + e.getKey() + ":" + i);
                            }
                        }
                    }
                }
            }
            LOGGER.info("[EternalSoul-DIAG] client curios audit: total={} sample={}",
                    total, sample);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // 回到标题界面：清掉客户端镜像与本地索引，避免跨世界残留
        ClientCache.clear();
        CurioIndex.invalidate();
    }
}
