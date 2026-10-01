package top.kuisland.eternalsoul.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import top.kuisland.eternalsoul.EternalSoul;

@Mod.EventBusSubscriber(modid = EternalSoul.MODID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class ClientSetup {

    /** 默认 K 键打开饰品列表 */
    public static final KeyMapping OPEN_LIST = new KeyMapping(
            "key.eternalsoul.open_list",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.eternalsoul");

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_LIST);
    }
}
