package top.kuisland.eternalsoul;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import top.kuisland.eternalsoul.network.Network;

@Mod(EternalSoul.MODID)
public class EternalSoul {

    public static final String MODID = "eternalsoul";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<Item> ETERNAL_SOUL = ITEMS.register("eternal_soul",
            () -> new EternalSoulItem(new Item.Properties()
                    .stacksTo(1)
                    .fireResistant()
                    .rarity(Rarity.EPIC)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.eternalsoul"))
                    .icon(() -> new ItemStack(ETERNAL_SOUL.get()))
                    .displayItems((parameters, output) -> output.accept(ETERNAL_SOUL.get()))
                    .build());

    public EternalSoul() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(SimulationManager.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Network::register);
    }
}
