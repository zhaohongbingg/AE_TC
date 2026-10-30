package com.aetc.compat.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.aetc.Register.AetcBlocks;
import com.aetc.content.machine.AutoPartBuilderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.recipe.partbuilder.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AE 版部件制作台注册成 AE 的合成机器（{@link ICraftingMachine}）。
 * <p>
 * AE 的样板供应器会把一单样板（图案物品 + 材料）整单推进来，本类负责：
 * <ol>
 *   <li>把 {@link KeyCounter} 摊平成真实物品；</li>
 *   <li>从样板声明的产物反推匠魂形状（部件物品 id 与 {@code Pattern} id 一致）；</li>
 *   <li>押入本地槽位、执行一次匠魂配方、把产物按推出方向弹出。</li>
 * </ol>
 * 任何一步不满足都整体回滚并返回 false —— AE 只给一次机会，不能半途而废。
 */
public class AutoPartBuilderCraftingMachine implements ICraftingMachine {

    private final AutoPartBuilderBlockEntity machine;

    public AutoPartBuilderCraftingMachine(AutoPartBuilderBlockEntity machine) {
        this.machine = machine;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        var block = AetcBlocks.AUTO_PART_BUILDER.get();
        return new PatternContainerGroup(AEItemKey.of(block), block.getName(), List.of());
    }

    @Override
    public boolean acceptsPlans() {
        return machine.canAcceptPush();
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] table, Direction ejectionDirection) {
        Level level = machine.getLevel();
        if (level == null || level.isClientSide || !machine.canAcceptPush()) {
            return false;
        }

        // 1. 摊平输入：一个图案物品 + 一种材料
        ItemStack patternItem = ItemStack.EMPTY;
        ItemStack material = ItemStack.EMPTY;
        for (KeyCounter counter : table) {
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey key)) {
                    return false; // 只支持物品输入
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                int maxStack = Math.max(key.toStack().getMaxStackSize(), 1);
                ItemStack stack = key.toStack((int) Math.min(amount, maxStack));
                if (stack.is(TinkerTags.Items.PATTERNS)) {
                    if (!patternItem.isEmpty()) {
                        return false; // 一单只接受一个图案物品
                    }
                    patternItem = stack;
                } else {
                    if (!material.isEmpty() && material.getItem() != stack.getItem()) {
                        return false; // 材料必须同种
                    }
                    if (material.isEmpty()) {
                        material = stack;
                    } else {
                        material.grow(stack.getCount());
                    }
                }
            }
        }
        if (patternItem.isEmpty() || material.isEmpty()) {
            return false;
        }

        // 2. 从样板声明的产物反推形状
        var outputs = details.getOutputs();
        if (outputs.length == 0 || !(outputs[0].what() instanceof AEItemKey outputKey)) {
            return false;
        }
        ItemStack expected = outputKey.toStack();
        // 匠魂的约定是「形状 id == 部件物品 id」，先用它当提示；不成立时退回不限形状（产物仍会被逐项校验）
        Pattern shape = new Pattern(BuiltInRegistries.ITEM.getKey(expected.getItem()));

        // 3. 押入本地槽位（可复用图案已经在位时不重复放，改为随产物一起退还）
        ItemStackHandler inventory = machine.getInventory();
        ItemStack inPattern = inventory.getStackInSlot(AutoPartBuilderBlockEntity.PATTERN_SLOT);
        boolean patternAlreadyPlaced = !inPattern.isEmpty()
                && ItemStack.isSameItemSameTags(inPattern, patternItem)
                && inPattern.is(TinkerTags.Items.REUSABLE_PATTERNS);

        List<ItemStack> returnItems = new ArrayList<>();
        boolean insertedMaterial = false;
        boolean insertedPattern = false;

        if (!patternAlreadyPlaced) {
            ItemStack patternRest = inventory.insertItem(AutoPartBuilderBlockEntity.PATTERN_SLOT, patternItem.copy(), false);
            if (!patternRest.isEmpty()) {
                return false;
            }
            insertedPattern = true;
        } else {
            returnItems.add(patternItem.copy());
        }

        ItemStack materialRest = inventory.insertItem(AutoPartBuilderBlockEntity.MATERIAL_SLOT, material.copy(), false);
        if (!materialRest.isEmpty()) {
            rollback(inventory, insertedPattern, insertedMaterial, patternItem, material);
            return false;
        }
        insertedMaterial = true;

        // 4. 执行匠魂配方（产物必须与样板声明一致）
        if (!machine.craftFor(shape, expected) && !machine.craftFor(null, expected)) {
            rollback(inventory, insertedPattern, insertedMaterial, patternItem, material);
            return false;
        }
        machine.onMachineCraft(shape);

        // 5. 弹出产物与退还物：优先推出方向，推不出去的留在本机输出槽
        ItemStack produced = inventory.extractItem(AutoPartBuilderBlockEntity.OUTPUT_SLOT, 64, false);
        List<ItemStack> toEject = new ArrayList<>();
        if (!produced.isEmpty()) {
            toEject.add(produced);
        }
        // 推多出来的材料必须还回去，否则材料槽一直被占，下一单会被 acceptsPlans() 拒掉
        ItemStack surplusMaterial = inventory.extractItem(AutoPartBuilderBlockEntity.MATERIAL_SLOT, 64, false);
        if (!surplusMaterial.isEmpty()) {
            toEject.add(surplusMaterial);
        }
        toEject.addAll(returnItems);

        IItemHandler target = neighborHandler(level, machine.getBlockPos(), ejectionDirection);
        for (ItemStack stack : toEject) {
            ItemStack rest = stack;
            if (target != null) {
                rest = insertAll(target, stack, false);
            }
            if (!rest.isEmpty()) {
                ItemStack back = inventory.insertItem(AutoPartBuilderBlockEntity.OUTPUT_SLOT, rest, false);
                if (!back.isEmpty()) {
                    machine.dropOutput(back);
                }
            }
        }
        return true;
    }

    private static void rollback(ItemStackHandler inventory, boolean insertedPattern, boolean insertedMaterial,
                                 ItemStack patternItem, ItemStack material) {
        if (insertedMaterial) {
            inventory.extractItem(AutoPartBuilderBlockEntity.MATERIAL_SLOT, material.getCount(), false);
        }
        if (insertedPattern) {
            inventory.extractItem(AutoPartBuilderBlockEntity.PATTERN_SLOT, patternItem.getCount(), false);
        }
    }

    @Nullable
    private static IItemHandler neighborHandler(Level level, BlockPos pos, @Nullable Direction direction) {
        if (direction == null) {
            return null;
        }
        BlockEntity neighbor = level.getBlockEntity(pos.relative(direction));
        if (neighbor == null) {
            return null;
        }
        return neighbor.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).orElse(null);
    }

    /** 往一个 IItemHandler 里塞物品，返回剩下的部分 */
    private static ItemStack insertAll(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
            rest = handler.insertItem(slot, rest, simulate);
        }
        return rest;
    }

    /** 供调试/提示用的简短描述 */
    public Component describe() {
        var pattern = machine.getSelectedPattern();
        return Component.literal("AutoPartBuilder[" + (pattern == null ? "no-shape" : pattern.toString()) + "]");
    }
}
