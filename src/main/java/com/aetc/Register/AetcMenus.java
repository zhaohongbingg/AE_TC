package com.aetc.Register;

import com.aetc.AeTc;
import com.aetc.content.machine.AutoPartBuilderBlockEntity;
import com.aetc.content.machine.AutoPartBuilderMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 容器（界面）类型注册。 */
public class AetcMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, AeTc.MODID);

    public static final RegistryObject<MenuType<AutoPartBuilderMenu>> AUTO_PART_BUILDER =
            MENUS.register("auto_part_builder", () -> IForgeMenuType.create((id, inventory, buffer) ->
                    new AutoPartBuilderMenu(id, inventory,
                            inventory.player.level().getBlockEntity(buffer.readBlockPos())
                                    instanceof AutoPartBuilderBlockEntity machine ? machine : null)));
}
