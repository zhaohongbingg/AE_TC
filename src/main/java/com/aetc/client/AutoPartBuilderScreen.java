package com.aetc.client;

import com.aetc.content.machine.AutoPartBuilderBlockEntity;
import com.aetc.content.machine.AutoPartBuilderMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * AE 版部件制作台的界面。
 * <p>
 * 布局照匠魂原版部件制作台：左上图案槽、其右材料槽、右侧产物槽，中间是一整片形状按钮网格。
 * 与匠魂不同的是：<b>所有形状始终都画出来</b>（当前输入做得出的亮、做不出的暗且不可点），
 * 所以中间永远不会是一片空白。按钮图标直接 blit 匠魂的图案贴图。
 */
public class AutoPartBuilderScreen extends AbstractContainerScreen<AutoPartBuilderMenu> {

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 184;

    /** 形状按钮网格：4 列 × 4 行、18px，与匠魂一致 */
    private static final int GRID_LEFT = 50;
    private static final int GRID_TOP = 17;
    private static final int BUTTON_SIZE = 18;
    private static final int COLUMNS = 4;
    private static final int ROWS = 4;
    private static final int VISIBLE = COLUMNS * ROWS;

    private int scrollRow;

    public AutoPartBuilderScreen(AutoPartBuilderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
        this.inventoryLabelY = 92;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.scrollRow = 0;
    }

    private List<AutoPartBuilderBlockEntity.PatternEntry> entries() {
        return this.menu.getPatternEntries();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int hovered = getHoveredIndex(mouseX, mouseY);
        List<AutoPartBuilderBlockEntity.PatternEntry> entries = entries();
        if (hovered >= 0 && hovered < entries.size()) {
            var entry = entries.get(hovered);
            List<Component> tooltip = new java.util.ArrayList<>();
            tooltip.add(entry.pattern().getDisplayName().copy().withStyle(ChatFormatting.WHITE));
            tooltip.add(Component.translatable("gui.ae_tc.auto_part_builder.cost", entry.cost())
                    .withStyle(ChatFormatting.GRAY));
            graphics.renderTooltip(this.font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
        } else {
            this.renderTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // 外框 + 主体
        graphics.fill(x - 2, y - 2, x + imageWidth + 2, y + imageHeight + 2, 0xFF000000);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + 17, 0xFFD8D8D8);

        // 形状区：内凹底衬（比主体略暗，衬托图标）
        graphics.fill(x + 4, y + 4, x + imageWidth - 4, y + 88, 0xFF8B8B8B);
        graphics.fill(x + 5, y + 5, x + imageWidth - 5, y + 87, 0xFF9E9E9E);

        // 机器三个槽位（图案 / 材料 / 产物）
        drawSlotBackground(graphics, x + 7, y + 42);
        drawSlotBackground(graphics, x + 28, y + 42);
        drawSlotBackground(graphics, x + 147, y + 41);

        drawPlayerInventory(graphics, x, y);
        // 图案网格的外框，让 4×4 图标区读起来像一块面板
        graphics.fill(x + GRID_LEFT - 3, y + GRID_TOP - 3,
                x + GRID_LEFT + COLUMNS * BUTTON_SIZE + 2, y + GRID_TOP + ROWS * BUTTON_SIZE + 2, 0xFF6E6E6E);
        graphics.fill(x + GRID_LEFT - 2, y + GRID_TOP - 2,
                x + GRID_LEFT + COLUMNS * BUTTON_SIZE + 1, y + GRID_TOP + ROWS * BUTTON_SIZE + 1, 0xFFB8B8B8);

        drawPatternButtons(graphics, x, y, mouseX, mouseY);
        drawScrollbar(graphics, x, y);
    }

    /**
     * 玩家物品栏：照原版玩家背包的画法——3×9 主背包 + 1×9 快捷栏，
     * 每个槽都是「#373737 上/左内阴影 + #8B8B8B 底 + #FFFFFF 下/右高光」的凹陷格。
     */
    private void drawPlayerInventory(GuiGraphics graphics, int x, int y) {
        // 物品栏与上半部分的分隔线（原版容器界面的那两条横线）
        graphics.fill(x + 4, y + 89, x + imageWidth - 4, y + 90, 0xFF8B8B8B);
        graphics.fill(x + 4, y + 90, x + imageWidth - 4, y + 91, 0xFFFFFFFF);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotBackground(graphics, x + 7 + col * 18, y + 101 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlotBackground(graphics, x + 7 + col * 18, y + 159);
        }
    }

    /** 原版风格的 18×18 凹陷槽位；参数是槽格外框左上角 */
    private void drawSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        graphics.fill(x, y, x + 18, y + 1, 0xFF373737);        // 上阴影
        graphics.fill(x, y, x + 1, y + 18, 0xFF373737);        // 左阴影
        graphics.fill(x + 1, y + 17, x + 18, y + 18, 0xFFFFFFFF); // 下高光
        graphics.fill(x + 17, y + 1, x + 18, y + 17, 0xFFFFFFFF); // 右高光
    }

    private void drawPatternButtons(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<AutoPartBuilderBlockEntity.PatternEntry> entries = entries();
        int selected = this.menu.getMachine() == null ? -1 : this.menu.getMachine().getSelectedIndex();
        int start = scrollRow * COLUMNS;

        for (int i = 0; i < VISIBLE; i++) {
            int index = start + i;
            if (index >= entries.size()) {
                break;
            }
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int bx = x + GRID_LEFT + col * BUTTON_SIZE;
            int by = y + GRID_TOP + row * BUTTON_SIZE;

            boolean hovered = isInButton(mouseX, mouseY, bx, by);
            int border = index == selected ? 0xFF2FA02F : hovered ? 0xFFFFFFFF : 0xFF5A5A5A;
            graphics.fill(bx - 1, by - 1, bx + 16, by + 16, border);
            graphics.fill(bx, by, bx + 15, by + 15, 0xFF7A7A7A);

            var entry = entries.get(index);
            // 匠魂的图案贴图被打进方块图集（BLOCK_ATLAS），不是普通 GUI 贴图，
            // 所以照匠魂 PartBuilderScreen 的做法：从图集取 sprite 再画。
            graphics.blit(bx - 1, by - 1, 100, 16, 16, patternSprite(entry.pattern().getTexture()));

            if (!entry.craftable()) {
                // 做不出来的形状压暗
                graphics.fill(bx - 1, by - 1, bx + 16, by + 16, 0x70101010);
            }
        }

        if (entries.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.ae_tc.auto_part_builder.no_patterns"),
                    x + 12, y + 44, 0x555555, false);
        }
    }

    private boolean isInButton(int mouseX, int mouseY, int bx, int by) {
        return mouseX >= bx - 1 && mouseX < bx + 16 && mouseY >= by - 1 && mouseY < by + 16;
    }

    /** 从方块图集取图案 sprite（匠魂把 tinker_pattern 贴图铺进这张图集） */
    private net.minecraft.client.renderer.texture.TextureAtlasSprite patternSprite(ResourceLocation texture) {
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        return minecraft.getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(texture);
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int y) {
        int rows = maxScrollRow();
        if (rows <= 0) {
            return;
        }
        int barX = x + 132;
        int barTop = y + GRID_TOP;
        int barHeight = ROWS * BUTTON_SIZE;
        graphics.fill(barX, barTop, barX + 6, barTop + barHeight, 0xFF373737);
        int handleHeight = Math.max(8, barHeight * ROWS / (rows + ROWS));
        int handleY = barTop + (barHeight - handleHeight) * scrollRow / rows;
        graphics.fill(barX + 1, handleY, barX + 5, handleY + handleHeight, 0xFFC6C6C6);
    }


    private int maxScrollRow() {
        int count = entries().size();
        int totalRows = (count + COLUMNS - 1) / COLUMNS;
        return Math.max(0, totalRows - ROWS);
    }

    /** 鼠标下的形状索引（不在格子内返回 -1） */
    private int getHoveredIndex(int mouseX, int mouseY) {
        List<AutoPartBuilderBlockEntity.PatternEntry> entries = entries();
        int start = scrollRow * COLUMNS;
        for (int i = 0; i < VISIBLE; i++) {
            int index = start + i;
            if (index >= entries.size()) {
                break;
            }
            int bx = this.leftPos + GRID_LEFT + (i % COLUMNS) * BUTTON_SIZE;
            int by = this.topPos + GRID_TOP + (i / COLUMNS) * BUTTON_SIZE;
            if (isInButton(mouseX, mouseY, bx, by)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int index = getHoveredIndex((int) mouseX, (int) mouseY);
            List<AutoPartBuilderBlockEntity.PatternEntry> entries = entries();
            if (index >= 0 && index < entries.size() && entries.get(index).craftable()) {
                this.minecraft.getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1.0F));
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = maxScrollRow();
        if (max > 0) {
            this.scrollRow = Mth.clamp(this.scrollRow - (int) Math.signum(delta), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                0x404040, false);
    }
}
