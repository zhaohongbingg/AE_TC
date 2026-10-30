package com.aetc.Register;

import com.aetc.AeTc;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 创造栏：本模组的物品都放这里，不去污染原版分类。 */
public class AetcTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AeTc.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.ae_tc.main"))
            .icon(() -> new ItemStack(AetcBlocks.AUTO_PART_BUILDER.get()))
            .displayItems((parameters, output) -> output.accept(AetcBlocks.AUTO_PART_BUILDER.get()))
            .build());
}
