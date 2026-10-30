package com.aetc.content.machine;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.common.TinkerTags;

/**
 * AE 版部件制作台的交互界面（参考匠魂原版 {@code PartBuilderContainerMenu} 的槽位布局，
 * 以及 AE 压印器「输入齐了自动加工」的行为）。
 * <p>
 * 槽位：图案物品 (8,43)、材料 (29,43)、产物 (148,42)。图案按钮的点击通过
 * {@link #clickMenuButton(Player, int)} 把索引发到服务端。
 */
public class AutoPartBuilderMenu extends AbstractContainerMenu {

    /** 图案按钮每行/每列的可用数量（界面里 4×4） */
    public static final int PATTERN_COLUMNS = 4;

    private final AutoPartBuilderBlockEntity machine;
    /** 玩家背包（仅用于 return 到背包时的边界判断） */
    private final Inventory playerInventory;

    public AutoPartBuilderMenu(int id, Inventory playerInventory, @Nullable AutoPartBuilderBlockEntity machine) {
        super(com.aetc.Register.AetcMenus.AUTO_PART_BUILDER.get(), id);
        this.playerInventory = playerInventory;
        this.machine = machine;

        Container container = machine != null ? machine.getMenuContainer() : new SimpleContainer(3);

        // 产物槽：只能取，不能放
        this.addSlot(new Slot(container, AutoPartBuilderBlockEntity.OUTPUT_SLOT, 148, 42) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        // 图案物品槽：只收匠魂图案
        this.addSlot(new Slot(container, AutoPartBuilderBlockEntity.PATTERN_SLOT, 8, 43) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(TinkerTags.Items.PATTERNS);
            }
        });
        // 材料槽
        this.addSlot(new Slot(container, AutoPartBuilderBlockEntity.MATERIAL_SLOT, 29, 43));

        // 玩家背包
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 102 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 160));
        }

        // 选中的图案索引：服务端写、客户端读
        if (machine != null) {
            this.addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return machine.getSelectedIndex();
                }

                @Override
                public void set(int value) {
                    machine.setClientSelectedIndex(value);
                }
            });
        }
    }

    @Nullable
    public AutoPartBuilderBlockEntity getMachine() {
        return machine;
    }

    /** 界面里可显示的全部形状（客户端也自己算，配方是同步过的） */
    public java.util.List<AutoPartBuilderBlockEntity.PatternEntry> getPatternEntries() {
        return machine != null ? machine.getPatternEntries() : java.util.List.of();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (machine == null || player.isSpectator()) {
            return false;
        }
        if (id < 0) {
            return true;
        }
        machine.selectIndex(id);
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return machine != null && machine.isUsableByPlayer(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        int machineSlots = 3;
        if (index < machineSlots) {
            // 机器 -> 玩家背包
            if (!this.moveItemStackTo(stack, machineSlots, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 玩家背包 -> 机器：图案物品进图案槽，其余进材料槽
            int target = stack.is(TinkerTags.Items.PATTERNS)
                    ? AutoPartBuilderBlockEntity.PATTERN_SLOT
                    : AutoPartBuilderBlockEntity.MATERIAL_SLOT;
            if (!this.moveItemStackTo(stack, target, target + 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }
}
