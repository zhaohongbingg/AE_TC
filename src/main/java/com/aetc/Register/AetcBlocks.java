package com.aetc.Register;

import com.aetc.AeTc;
import com.aetc.content.machine.AutoPartBuilderBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 方块与对应物品的注册。 */
public class AetcBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AeTc.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AeTc.MODID);

    private static <T extends Block> RegistryObject<T> registerBlocks(String name, java.util.function.Supplier<T> block) {
        RegistryObject<T> registered = BLOCKS.register(name, block);
        ITEMS.register(name, () -> new BlockItem(registered.get(), new Item.Properties()));
        return registered;
    }

    /**
     * AE 版部件制作台。界面与槽位布局照匠魂部件制作台，按面 IO 照 AE 压印器。
     */
    public static final RegistryObject<Block> AUTO_PART_BUILDER =
            registerBlocks("auto_part_builder", AutoPartBuilderBlock::new);
}
