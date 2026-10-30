package com.aetc.Register;

import com.aetc.AeTc;
import com.aetc.content.machine.AutoPartBuilderBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 方块实体注册。 */
public class AetcBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, AeTc.MODID);

    public static final RegistryObject<BlockEntityType<AutoPartBuilderBlockEntity>> AUTO_PART_BUILDER =
            BLOCK_ENTITIES.register("auto_part_builder", () ->
                    BlockEntityType.Builder.of(
                            AutoPartBuilderBlockEntity::new,
                            AetcBlocks.AUTO_PART_BUILDER.get()
                    ).build(null)
            );
}
