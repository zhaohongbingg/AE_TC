package com.aetc.content.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * AE 版部件制作台方块。
 * <p>
 * 两面借鉴：界面与槽位布局照匠魂原版部件制作台（{@link AutoPartBuilderMenu}），按面 IO 与
 * 「输入齐了自动加工」照 AE 压印器。产物自动推出，也能被抽走或被 AE 样板供应器推料驱动。
 */
public class AutoPartBuilderBlock extends BaseEntityBlock {

    /** 是否正在制作（模型切换用） */
    public static final BooleanProperty LIT = BooleanProperty.create("lit");

    public AutoPartBuilderBlock() {
        super(Properties.copy(Blocks.SMITHING_TABLE)
                .strength(3.0F, 6.0F)
                .sound(SoundType.WOOD)
                .requiresCorrectToolForDrops()
                .noOcclusion()
        );
        this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutoPartBuilderBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof AutoPartBuilderBlockEntity machine) {
                machine.tickServer();
            }
        };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof AutoPartBuilderBlockEntity machine) {
            NetworkHooks.openScreen(serverPlayer, machine, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof AutoPartBuilderBlockEntity machine) {
            var inv = machine.getInventory();
            Containers.dropContents(level, pos, new SimpleContainer(
                    inv.getStackInSlot(AutoPartBuilderBlockEntity.MATERIAL_SLOT),
                    inv.getStackInSlot(AutoPartBuilderBlockEntity.PATTERN_SLOT),
                    inv.getStackInSlot(AutoPartBuilderBlockEntity.OUTPUT_SLOT)));
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
