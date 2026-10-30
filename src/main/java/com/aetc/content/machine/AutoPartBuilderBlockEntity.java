package com.aetc.content.machine;

import com.aetc.Register.AetcBlockEntities;
import com.aetc.compat.ae2.Ae2Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.recipe.TinkerRecipeTypes;
import slimeknights.tconstruct.library.recipe.casting.material.MaterialCastingLookup;
import slimeknights.tconstruct.library.recipe.material.IMaterialValue;
import slimeknights.tconstruct.library.recipe.material.MaterialRecipe;
import slimeknights.tconstruct.library.recipe.material.MaterialRecipeCache;
import slimeknights.tconstruct.library.recipe.material.MaterialValue;
import slimeknights.tconstruct.library.recipe.partbuilder.IPartBuilderContainer;
import slimeknights.tconstruct.library.recipe.partbuilder.IPartBuilderRecipe;
import slimeknights.tconstruct.library.recipe.partbuilder.Pattern;
import slimeknights.tconstruct.library.tools.part.IMaterialItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * AE 版部件制作台的方块实体。
 * <p>
 * 匠魂原版部件制作台把 {@code IItemHandler} 的插入/抽出全部禁用了
 * （{@code ConfigurableInvWrapperCapability(this, false, false)}），产出还放在不进 capability 的
 * {@code LazyResultContainer} 里，所以任何自动化都喂不进去。这个方块实体是同一套配方逻辑的
 * 自动化 + 可交互版本：
 * <ul>
 *   <li>有和匠魂原版一样的界面：图案槽 + 材料槽 + 产物槽 + 图案按钮选择形状；</li>
 *   <li>有 AE 压印器那样的按面 IO：顶面插图案物品、底面插材料、侧面抽产物；</li>
 *   <li>输入齐了自动加工，产物可被抽出（也支持 AE 样板推料）。</li>
 * </ul>
 */
public class AutoPartBuilderBlockEntity extends BlockEntity implements IPartBuilderContainer, MenuProvider {

    /** 材料槽 */
    public static final int MATERIAL_SLOT = 0;
    /** 图案物品槽（匠魂的 pattern / 模具） */
    public static final int PATTERN_SLOT = 1;
    /** 产物槽，只出不进 */
    public static final int OUTPUT_SLOT = 2;
    /** 槽位总数 */
    public static final int SLOT_COUNT = 3;

    private static final String TAG_INVENTORY = "inv";
    private static final String TAG_PATTERN = "pattern";

    /** 三槽内部库存：内部代码（含 AE 推料）可自由读写 */
    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            materialDirty = true;
            patternsDirty = true;
            setChanged();
        }
    };

    /** 界面（{@link Container}）用的视图：和内部库存同一份数据 */
    private final Container menuContainer = new Container() {
        @Override
        public int getContainerSize() {
            return SLOT_COUNT;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                if (!inventory.getStackInSlot(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack extracted = inventory.extractItem(slot, amount, false);
            if (!extracted.isEmpty()) {
                setChanged();
            }
            return extracted;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack stack = inventory.getStackInSlot(slot);
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inventory.setStackInSlot(slot, stack);
        }

        @Override
        public void setChanged() {
            AutoPartBuilderBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return isUsableByPlayer(player);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot != OUTPUT_SLOT;
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
    };

    /**
     * 对外能力：按面区分（照 AE 压印器的做法）。
     * <ul>
     *   <li>UP → 图案物品槽，只进不出</li>
     *   <li>DOWN → 材料槽，只进不出</li>
     *   <li>水平四侧 → 产物槽，只出不进</li>
     *   <li>无面（内部查询）→ 0/1 可进、2 可出</li>
     * </ul>
     */
    private final Map<Direction, IItemHandler> sideHandlers = createSideHandlers();
    /** 每面一个 LazyOptional，避免每次能力查询都新建（AE / 漏斗会缓存它） */
    private final Map<Direction, LazyOptional<IItemHandler>> sideHandlerCaps = createSideCaps();

    private final LazyOptional<IItemHandler> nullSideHandler = LazyOptional.of(() -> new BasicHandler(-1));

    /** 当前选中的图案；与 {@link #selectedIndex} 同步 */
    @Nullable
    private Pattern selectedPattern;
    /** 当前选中的图案索引（图案列表里的位置，-1 表示未选） */
    private int selectedIndex = -1;
    /** 客户端同步过来的索引（服务端只认 selectedPattern） */
    private int clientSelectedIndex = -1;

    @Nullable
    private IMaterialValue material;
    private boolean materialDirty = true;

    @Nullable
    private List<Pattern> cachedPatterns;
    @Nullable
    private List<PatternEntry> cachedEntries;
    private boolean patternsDirty = true;

    public AutoPartBuilderBlockEntity(BlockPos pos, BlockState state) {
        super(AetcBlockEntities.AUTO_PART_BUILDER.get(), pos, state);
    }

    // ===== 匠魂容器接口 =====

    @Override
    public ItemStack getStack() {
        return inventory.getStackInSlot(MATERIAL_SLOT);
    }

    @Override
    public ItemStack getPatternStack() {
        return inventory.getStackInSlot(PATTERN_SLOT);
    }

    @Nullable
    @Override
    public IMaterialValue getMaterial() {
        if (materialDirty) {
            materialDirty = false;
            material = resolveMaterial(getStack());
        }
        return material;
    }

    /**
     * 与匠魂 {@code PartBuilderContainerWrapper#getMaterial()} 同一套判定：
     * 工具部件按物品价值取材质，其它物品走 MATERIAL 配方解析。
     */
    @Nullable
    public static IMaterialValue resolveMaterial(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.is(TinkerTags.Items.TOOL_PARTS)) {
            MaterialVariantId variant = IMaterialItem.getMaterialFromStack(stack);
            int cost = MaterialCastingLookup.getItemCost(stack.getItem());
            if (cost == 0 || variant == null) {
                return null;
            }
            return new MaterialValue(variant, cost);
        }
        MaterialRecipe recipe = MaterialRecipeCache.findRecipe(stack);
        return recipe == MaterialRecipe.EMPTY ? null : recipe;
    }

    // ===== 图案列表 / 选择（界面用） =====

    /**
     * 一个形状条目：图标永远是形状本身，{@code craftable} 表示当前输入下这个形状能不能做。
     * 这样界面上永远有一整片形状图标（暗的只是暂时做不了），不会出现空面板。
     */
    public record PatternEntry(Pattern pattern, int cost, boolean craftable) {
    }

    /**
     * 全部部件形状（不分输入是否齐备），按 cost、id 排序。
     * {@code craftable} 用匠魂自己的 {@code partialMatch} 判定当前输入。
     */
    public List<PatternEntry> getPatternEntries() {
        if (!patternsDirty && cachedEntries != null) {
            return cachedEntries;
        }
        patternsDirty = false;
        if (level == null) {
            cachedEntries = List.of();
            cachedPatterns = List.of();
            return cachedEntries;
        }
        record Match(Pattern pattern, IPartBuilderRecipe recipe, boolean craftable) {
        }
        Map<Pattern, Match> unique = new java.util.LinkedHashMap<>();
        for (IPartBuilderRecipe recipe : level.getRecipeManager()
                .getAllRecipesFor(TinkerRecipeTypes.PART_BUILDER.get())) {
            boolean craftable;
            try {
                craftable = recipe.partialMatch(this);
            } catch (RuntimeException ignored) {
                craftable = false;
            }
            for (Pattern pattern : recipe.getPatterns(this).toList()) {
                // 同一形状保留最便宜/最先命中的那个配方
                unique.putIfAbsent(pattern, new Match(pattern, recipe, craftable));
            }
        }
        cachedEntries = unique.values().stream()
                .sorted(Comparator.<Match>comparingInt(m -> m.recipe().getCost())
                        .thenComparing(m -> m.pattern()))
                .map(m -> new PatternEntry(m.pattern(), m.recipe().getCost(), m.craftable()))
                .toList();
        cachedPatterns = cachedEntries.stream()
                .filter(PatternEntry::craftable)
                .map(PatternEntry::pattern)
                .toList();
        return cachedEntries;
    }

    /** 当前输入下可用的图案（只有这些能点击选中） */
    public List<Pattern> getAvailablePatterns() {
        getPatternEntries();
        return cachedPatterns == null ? List.of() : cachedPatterns;
    }

    public int getSelectedIndex() {
        if (selectedPattern != null) {
            List<PatternEntry> entries = getPatternEntries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).pattern().equals(selectedPattern)) {
                    selectedIndex = i;
                    return i;
                }
            }
        }
        return clientSelectedIndex >= 0 ? clientSelectedIndex : selectedIndex;
    }

    /** 客户端从菜单同步索引时调用 */
    public void setClientSelectedIndex(int index) {
        this.clientSelectedIndex = index;
    }

    /** 点击图案按钮：索引是「全部形状」列表里的位置，只有当前做得出的才允许选中 */
    public void selectIndex(int index) {
        List<PatternEntry> entries = getPatternEntries();
        if (index >= 0 && index < entries.size() && entries.get(index).craftable()) {
            selectedPattern = entries.get(index).pattern();
            selectedIndex = index;
        } else {
            selectedPattern = null;
            selectedIndex = -1;
        }
        setChanged();
    }

    @Nullable
    public Pattern getSelectedPattern() {
        return selectedPattern;
    }

    public void setSelectedPattern(@Nullable Pattern pattern) {
        if (pattern == null ? selectedPattern != null : !pattern.equals(selectedPattern)) {
            selectedPattern = pattern;
            selectedIndex = -1;
            if (pattern != null) {
                List<PatternEntry> entries = getPatternEntries();
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).pattern().equals(pattern)) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
            setChanged();
        }
    }

    // ===== 配方解析 =====

    /** 一次可执行的制作计划 */
    public record CraftPlan(IPartBuilderRecipe recipe, Pattern shape, ItemStack result,
                            int materialItems, boolean consumePattern, ItemStack leftover) {
    }

    /**
     * 在给定容器上找出第一个可执行、且产物与 {@code expectedOutput} 对得上的配方。
     */
    @Nullable
    public static CraftPlan findPlan(Level level, IPartBuilderContainer container,
                                     @Nullable Pattern shapeHint, ItemStack expectedOutput) {
        for (IPartBuilderRecipe recipe : level.getRecipeManager()
                .getAllRecipesFor(TinkerRecipeTypes.PART_BUILDER.get())) {
            if (!recipe.partialMatch(container)) {
                continue;
            }
            for (Pattern shape : recipe.getPatterns(container).toList()) {
                if (shapeHint != null && !shapeHint.equals(shape)) {
                    continue;
                }
                if (!recipe.matches(container, level)) {
                    continue;
                }
                ItemStack result = recipe.assemble(container, level.registryAccess(), shape);
                if (result.isEmpty()) {
                    continue;
                }
                if (!expectedOutput.isEmpty() && !ItemStack.isSameItemSameTags(result, expectedOutput)) {
                    continue;
                }
                int materialItems = Math.max(recipe.getItemsUsed(container), 0);
                boolean consumePattern = !container.getPatternStack().is(TinkerTags.Items.REUSABLE_PATTERNS);
                ItemStack leftover = recipe.getLeftover(container, shape);
                return new CraftPlan(recipe, shape, result, materialItems, consumePattern, leftover);
            }
        }
        return null;
    }

    // ===== Tick =====

    public void tickServer() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (selectedPattern == null) {
            // 界面里选过的话，索引同步回图案
            List<Pattern> patterns = getAvailablePatterns();
            if (selectedIndex >= 0 && selectedIndex < patterns.size()) {
                selectedPattern = patterns.get(selectedIndex);
            }
        }
        if (selectedPattern == null || getStack().isEmpty() || getPatternStack().isEmpty()) {
            setLit(false);
            autoEject();
            return;
        }
        setLit(true);
        craftFor(selectedPattern, ItemStack.EMPTY);
        setLit(false);
        autoEject();
    }

    /**
     * 按指定形状制作一次，并用 {@code expectedOutput} 二次校验产物（AE 路径防做错形状）。
     *
     * @param expectedOutput 空栈表示不校验
     */
    public boolean craftFor(@Nullable Pattern shape, ItemStack expectedOutput) {
        if (level == null || level.isClientSide) {
            return false;
        }
        CraftPlan plan = findPlan(level, this, shape, expectedOutput);
        if (plan == null || !performCraft(plan)) {
            return false;
        }
        setChanged();
        return true;
    }

    /** 在本地三槽上真正执行一次制作：先预检输出空间，再扣料 */
    private boolean performCraft(CraftPlan plan) {
        ItemStack result = plan.result().copy();
        if (!inventory.insertItem(OUTPUT_SLOT, result.copy(), true).isEmpty()) {
            return false;
        }
        if (plan.materialItems() > 0) {
            if (inventory.getStackInSlot(MATERIAL_SLOT).getCount() < plan.materialItems()) {
                return false;
            }
            inventory.extractItem(MATERIAL_SLOT, plan.materialItems(), false);
        }
        if (plan.consumePattern()) {
            inventory.extractItem(PATTERN_SLOT, 1, false);
        }
        inventory.insertItem(OUTPUT_SLOT, result, false);
        if (!plan.leftover().isEmpty()) {
            ItemStack rest = inventory.insertItem(OUTPUT_SLOT, plan.leftover().copy(), false);
            if (!rest.isEmpty()) {
                dropAtBlock(rest);
            }
        }
        return true;
    }

    // ===== 自动弹出 =====

    private static final Direction[] EJECT_ORDER = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /** 把产物槽的内容自动推进相邻容器 */
    private void autoEject() {
        ItemStack output = inventory.getStackInSlot(OUTPUT_SLOT);
        if (output.isEmpty() || level == null) {
            return;
        }
        for (Direction direction : EJECT_ORDER) {
            BlockEntity neighbor = level.getBlockEntity(worldPosition.relative(direction));
            if (neighbor == null) {
                continue;
            }
            IItemHandler handler = neighbor
                    .getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite())
                    .orElse(null);
            if (handler == null) {
                continue;
            }
            ItemStack rest = output.copy();
            for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) {
                rest = handler.insertItem(slot, rest, false);
            }
            int moved = output.getCount() - rest.getCount();
            if (moved > 0) {
                inventory.extractItem(OUTPUT_SLOT, moved, false);
                setChanged();
                return;
            }
        }
    }

    // ===== AE 推料路径（由 compat/ae2 调用） =====

    /** AE 机器是否还能吃下一单：材料槽空着、图案槽空着或已是要用的可复用图案 */
    public boolean canAcceptPush() {
        if (level == null || level.isClientSide) {
            return false;
        }
        if (!inventory.getStackInSlot(MATERIAL_SLOT).isEmpty()) {
            return false;
        }
        ItemStack inPattern = inventory.getStackInSlot(PATTERN_SLOT);
        return inPattern.isEmpty() || inPattern.is(TinkerTags.Items.REUSABLE_PATTERNS);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    /** AE 单次推料完成后同步状态（扣料/产出由推料方完成） */
    public void onMachineCraft(Pattern shape) {
        setSelectedPattern(shape);
        setLit(false);
        setChanged();
    }

    // ===== 界面 =====

    public Container getMenuContainer() {
        return menuContainer;
    }

    public boolean isUsableByPlayer(Player player) {
        return level != null
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ae_tc.auto_part_builder");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        return new AutoPartBuilderMenu(id, playerInventory, this);
    }

    // ===== 杂项 =====

    private void dropAtBlock(ItemStack stack) {
        if (level instanceof ServerLevel serverLevel) {
            Containers.dropItemStack(serverLevel, worldPosition.getX() + 0.5,
                    worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, stack);
        }
    }

    /** 产物塞不回去时丢到方块上方（AE 路径用） */
    public void dropOutput(ItemStack stack) {
        if (!stack.isEmpty()) {
            dropAtBlock(stack);
        }
    }

    private void setLit(boolean lit) {
        if (level == null) {
            return;
        }
        BlockState state = level.getBlockState(worldPosition);
        if (state.hasProperty(AutoPartBuilderBlock.LIT) && state.getValue(AutoPartBuilderBlock.LIT) != lit) {
            level.setBlock(worldPosition, state.setValue(AutoPartBuilderBlock.LIT, lit), 3);
        }
    }

    // ===== NBT =====

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(TAG_INVENTORY, inventory.serializeNBT());
        if (selectedPattern != null) {
            tag.putString(TAG_PATTERN, selectedPattern.toString());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(TAG_INVENTORY)) {
            inventory.deserializeNBT(tag.getCompound(TAG_INVENTORY));
        }
        if (tag.contains(TAG_PATTERN)) {
            selectedPattern = Pattern.tryParse(tag.getString(TAG_PATTERN));
        } else {
            selectedPattern = null;
        }
        materialDirty = true;
        patternsDirty = true;
    }

    // ===== Capability =====

    private Map<Direction, IItemHandler> createSideHandlers() {
        Map<Direction, IItemHandler> handlers = new java.util.EnumMap<>(Direction.class);
        handlers.put(Direction.UP, new BasicHandler(PATTERN_SLOT));
        handlers.put(Direction.DOWN, new BasicHandler(MATERIAL_SLOT));
        for (Direction direction : new Direction[] { Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST }) {
            handlers.put(direction, new BasicHandler(OUTPUT_SLOT));
        }
        return handlers;
    }

    private Map<Direction, LazyOptional<IItemHandler>> createSideCaps() {
        Map<Direction, LazyOptional<IItemHandler>> caps = new java.util.EnumMap<>(Direction.class);
        sideHandlers.forEach((direction, handler) -> caps.put(direction, LazyOptional.of(() -> handler)));
        return caps;
    }

    /**
     * 单槽处理器：按面决定这个槽是「只进」还是「只出」。
     * {@code slot < 0} 表示无面查询，此时 0/1 可进、2 可出。
     */
    private class BasicHandler implements IItemHandler {
        /** 固定槽位；-1 表示全部槽位可见 */
        private final int fixedSlot;

        private BasicHandler(int fixedSlot) {
            this.fixedSlot = fixedSlot;
        }

        @Override
        public int getSlots() {
            return fixedSlot < 0 ? SLOT_COUNT : 1;
        }

        private int realSlot(int slot) {
            return fixedSlot < 0 ? slot : fixedSlot;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            int real = realSlot(slot);
            return real >= 0 && real < SLOT_COUNT ? inventory.getStackInSlot(real) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            int real = realSlot(slot);
            if (real == OUTPUT_SLOT || real < 0) {
                return stack;
            }
            return inventory.insertItem(real, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            int real = realSlot(slot);
            if (real != OUTPUT_SLOT) {
                return ItemStack.EMPTY;
            }
            return inventory.extractItem(real, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            int real = realSlot(slot);
            return real >= 0 && real < SLOT_COUNT ? inventory.getSlotLimit(real) : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            int real = realSlot(slot);
            if (real < 0 || real >= SLOT_COUNT) {
                return false;
            }
            if (real == OUTPUT_SLOT) {
                return false;
            }
            if (real == PATTERN_SLOT) {
                return stack.is(TinkerTags.Items.PATTERNS);
            }
            return true;
        }
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) {
                return nullSideHandler.cast();
            }
            LazyOptional<IItemHandler> optional = sideHandlerCaps.get(side);
            return optional == null ? nullSideHandler.cast() : optional.cast();
        }
        if (Ae2Compat.isLoaded() && Ae2Compat.isCraftingMachineCapability(cap)) {
            return Ae2Compat.craftingMachine(this, side).cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        nullSideHandler.invalidate();
        sideHandlerCaps.values().forEach(LazyOptional::invalidate);
        if (Ae2Compat.isLoaded()) {
            Ae2Compat.invalidate(this);
        }
    }

    /** 供 AE 路径构造的临时容器：把一次推进来的物品当成库存来解析配方 */
    public static class TempContainer implements IPartBuilderContainer {
        private final ItemStack patternStack;
        private final List<ItemStack> materialStacks;
        @Nullable
        private IMaterialValue material;
        private boolean resolved;

        public TempContainer(ItemStack patternStack, List<ItemStack> materialStacks) {
            this.patternStack = patternStack;
            this.materialStacks = new ArrayList<>(materialStacks);
        }

        @Override
        public ItemStack getStack() {
            if (materialStacks.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack first = materialStacks.get(0).copy();
            int total = 0;
            for (ItemStack stack : materialStacks) {
                if (stack.getItem() == first.getItem()) {
                    total += stack.getCount();
                }
            }
            first.setCount(Math.max(total, 1));
            return first;
        }

        @Override
        public ItemStack getPatternStack() {
            return patternStack;
        }

        @Nullable
        @Override
        public IMaterialValue getMaterial() {
            if (!resolved) {
                resolved = true;
                material = resolveMaterial(getStack());
            }
            return material;
        }
    }
}
