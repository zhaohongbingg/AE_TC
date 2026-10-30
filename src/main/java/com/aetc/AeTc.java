package com.aetc;

import com.aetc.Register.AetcBlockEntities;
import com.aetc.Register.AetcBlocks;
import com.aetc.Register.AetcMenus;
import com.aetc.Register.AetcTabs;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * AE × 匠魂：自动化部件制作台。
 * <p>
 * 提供 {@code ae_tc:auto_part_builder}：界面照匠魂部件制作台（图案按钮选形状），
 * 按面 IO 与"输入齐了自动加工"照 AE 压印器，产物自动外推，并注册成 AE 的
 * {@code ICraftingMachine} 以便样板供应器整单推料。
 */
@Mod(AeTc.MODID)
public class AeTc {

    public static final String MODID = "ae_tc";

    public AeTc() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        AetcBlocks.BLOCKS.register(modBus);
        AetcBlocks.ITEMS.register(modBus);
        AetcBlockEntities.BLOCK_ENTITIES.register(modBus);
        AetcMenus.MENUS.register(modBus);
        AetcTabs.TABS.register(modBus);
        modBus.addListener(AeTc::clientSetup);
    }

    /** 客户端：注册界面（纯客户端类型只在这个方法里出现） */
    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                AetcMenus.AUTO_PART_BUILDER.get(),
                com.aetc.client.AutoPartBuilderScreen::new));
    }
}
