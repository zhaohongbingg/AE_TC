# 自动化部件制作台（AE2 联动）实现规格

本文件是实现规格，不是讨论稿。所有签名均已对照真实源码核实，实现时必须按此写；发现与本文件不符的地方先停下来记录，不要改用记忆里的写法。

## 0. 目标与已定决策

- 用户口径：**样板编写是 AE 自带的功能**，不要在 AE 终端里加页签、不要写自定义 AE 样板物品、不要 mixin AE。
- 因此 AE 侧走 AE 原生的**处理样板**：输入 = 【匠魂图案物品 + 材料物品】，输出 = 【带材质 NBT 的部件】。
- 匠魂部件制作台原生禁止一切自动化 IO（`ConfigurableInvWrapperCapability(this, false, false)`，见 `PartBuilderBlockEntity.java:63`），产出走的是不进 capability 的 `LazyResultContainer`。所以本模组自带一个可自动化的制作台方块。
- **形状（shape）不在匠魂图案物品里**：`patterns/default` 只有 `tconstruct:pattern`，`patterns/reusable` 只有 `#casts/gold` + `minecraft:elytra`。形状只能来自别处。本设计的取法是：**从 AE 推来的样板输出反推形状**——部件物品 id 与匠魂 `Pattern` id 一一对应（`Pattern.fromItem(ItemLike)` 就是为此存在的）。
- 材料判定完全用匠魂语义（`MaterialRecipeCache` + `IMaterialItem.canUseMaterial` + `IMaterialValue.getItemsUsed`），机器对任何合法材料都接受，所以「材料替换」在机器侧天然成立。

## 1. 已核实的 API（不得改写）

匠魂（TConstruct 3.10.2.92，源码 `D:\game\mc\_refs\tcon-src`）：

| 用途 | 签名 | 位置 |
|---|---|---|
| 配方类型 | `TinkerRecipeTypes.PART_BUILDER.get()` | `library/recipe/TinkerRecipeTypes.java` |
| 取全部配方 | **用 `level.getRecipeManager().getAllRecipesFor(TinkerRecipeTypes.PART_BUILDER.get())`** → `List<IPartBuilderRecipe>` | `net.minecraft.world.item.crafting.RecipeManager`（javap 实测 public 的只有 `getAllRecipesFor` / `getRecipesFor`） |
| 配方接口 | `partialMatch(IPartBuilderContainer)`、`matches(IPartBuilderContainer, Level)`、`getPatterns(IPartBuilderContainer)` → `Stream<Pattern>`、`getPattern()`、`getCost()`、`getItemsUsed(IPartBuilderContainer)`、`assemble(IPartBuilderContainer, RegistryAccess, Pattern)` → `ItemStack`、`getLeftover(IPartBuilderContainer, Pattern)` → `ItemStack`、`allowUncraftable()` | `library/recipe/partbuilder/IPartBuilderRecipe.java` |
| 容器接口 | `getStack()`（材料槽）、`getPatternStack()`（图案槽）、`getMaterial()` → `@Nullable IMaterialValue` | `library/recipe/partbuilder/IPartBuilderContainer.java` |
| 形状 | `new Pattern(ResourceLocation)`、`Pattern.tryParse(String)`、`Pattern.fromItem(ItemLike)`、`getDisplayName()`、`getTexture()`、`equals/hashCode` | `library/recipe/partbuilder/Pattern.java` |
| 材料解析（推荐） | `MaterialRecipeCache.findRecipe(ItemStack)` → `MaterialRecipe`，无匹配返回 `MaterialRecipe.EMPTY` | `library/recipe/material/MaterialRecipeCache.java:105` |
| 工具部件材料 | `stack.is(TinkerTags.Items.TOOL_PARTS)` → `new MaterialValue(IMaterialItem.getMaterialFromStack(stack), MaterialCastingLookup.getItemCost(stack.getItem()))`；cost==0 或材质 UNKNOWN 视为无材料 | `tables/block/entity/inventory/PartBuilderContainerWrapper.java:60-67` |
| 材料值 | `IMaterialValue.getMaterial()` → `MaterialVariant`、`getValue()`、`getNeeded()`、`getItemsUsed(int cost)`、`getLeftover(int cost)`、`hasLeftover()` | `library/recipe/material/IMaterialValue.java` |
| 部件材质 | `IMaterialItem.getMaterial(ItemStack)`、`canUseMaterial(MaterialId)`、`withMaterial(MaterialVariantId)`、`withMaterialForDisplay(MaterialVariantId)` | `library/tools/part/IMaterialItem.java` |
| 标签 | `TinkerTags.Items.REUSABLE_PATTERNS`（图案不消耗）、`TinkerTags.Items.TOOL_PARTS`、`TinkerTags.Items.DEFAULT_PATTERNS` | `common/TinkerTags.java:321-324` |

AE2（15.4.12，源码 `D:\game\mc\_refs\ae2-src`）：

| 用途 | 签名 | 位置 |
|---|---|---|
| 机器接口 | `ICraftingMachine`：`getCraftingMachineInfo()` → `PatternContainerGroup`、`pushPattern(IPatternDetails, KeyCounter[], Direction)` → boolean、`acceptsPlans()` → boolean | `api/implementations/blockentities/ICraftingMachine.java` |
| 能力 key | `appeng.capabilities.Capabilities.CRAFTING_MACHINE` | `ICraftingMachine.java:56` 的用法 |
| 分组 | `new PatternContainerGroup(@Nullable AEItemKey icon, Component name, List<Component> tooltip)` | `api/implementations/blockentities/PatternContainerGroup.java:34` |
| 样板输入 | `IPatternDetails.getInputs()` → `IInput[]`；`IInput.getPossibleInputs()`、`getMultiplier()`、`isValid(AEKey, Level)` | `api/crafting/IPatternDetails.java:94-120` |
| 样板输出 | `IPatternDetails.getOutputs()` → `GenericStack[]`（第 0 个是主输出） | 同上 |
| 键 | `KeyCounter` 迭代得到 `AEKey`/数量；`AEKey` 需 `instanceof AEItemKey` 才能 `.toStack()` | `api/stacks/KeyCounter.java` |

参考实现（照 AE 自己的写法）：`appeng/blockentity/crafting/MolecularAssemblerBlockEntity.java` —— `pushPattern` 里先检查自身空闲、再 `instanceof` 目标样板类型、最后填充内部库存；`getCraftingMachineInfo()` 用方块物品做 icon + 方块名做 name。

## 2. 文件清单与职责

新增（包路径按项目现状 `com.galtc2.*`）：

1. `src/main/java/com/galtc2/content/machine/AutoPartBuilderBlock.java`
   - 方块本体。`extends Block` 或 `BaseEntityBlock`（**不提供 GUI，也不注册 Menu**）。
   - 属性：`LIT`（boolean，制作中亮起，参考 `OreGeneratorBlock.LIT` 的用法）。
   - `newBlockEntity` 返回本模组 BE；`getTicker` 返回 `AutoPartBuilderBlockEntity::tickServer`（仅服务端）。
   - 破坏时 `Containers.dropContents` 掉出三个槽位内容（参考 `OreGeneratorBlock` 现有写法）。

2. `src/main/java/com/galtc2/content/machine/AutoPartBuilderBlockEntity.java`
   - `extends BlockEntity implements IPartBuilderContainer`。
   - 槽位 3 个：`MATERIAL_SLOT = 0`、`PATTERN_SLOT = 1`、`OUTPUT_SLOT = 2`（与匠魂保持一致，材料在前）。
   - 状态：`@Nullable Pattern selectedPattern`（NBT 键 `pattern`，存 id 字符串）、材料缓存 + `materialDirty` 标记。
   - `getMaterial()`：先判 `TOOL_PARTS` 分支，否则 `MaterialRecipeCache.findRecipe(stack)`，`EMPTY` → `null`；缓存策略照 `PartBuilderContainerWrapper`（`refreshMaterial()` 置脏）。
   - 核心单一出口：`@Nullable CraftPlan resolveCraft(List<ItemStack> materialStacks, ItemStack patternItem, @Nullable Pattern shapeHint, ItemStack expectedOutput)`，返回 record `CraftPlan(IPartBuilderRecipe recipe, Pattern shape, ItemStack result, int materialItemsUsed, int patternItemsUsed, ItemStack leftover)`。
     查找顺序：遍历 `getAllRecipesFor(PART_BUILDER)` → `partialMatch` → `getPatterns(...)` 展开 → 若给了 `shapeHint` 只保留 `equals(shapeHint)` 的形状（否则全部）→ `matches(container, level)` → `assemble(container, registryAccess, shape)`（若给了 `expectedOutput` 必须与 `result` 的 item + NBT 一致，数量不比）→ 返回第一个命中。
   - `tickServer()`：输出槽有空位且 `selectedPattern != null` → `resolveCraft` 成功就 `performCraft`（消耗材料槽 `materialItemsUsed`、图案槽按 `TinkerTags.Items.REUSABLE_PATTERNS` 决定是否消耗 1、剩余物 `getLeftover` 塞进输出槽或掉落）；失败则清空 `selectedPattern` 并同步 `LIT=false`。
   - NBT：`inv`（`ItemStackHandler.serializeNBT()` 的整体，便于 `data merge block` 造测试数据）、`pattern`（形状 id）、`lit`。
   - Capability：`ForgeCapabilities.ITEM_HANDLER` → 自定义 handler（0/1 槽只允许插入、2 槽只允许抽出）；AE 的 `Capabilities.CRAFTING_MACHINE` 只在 AE2 存在时提供，经由第 4 个文件的桥接方法拿，禁止在本类里 import AE 类。

3. `src/main/java/com/galtc2/compat/ae2/AutoPartBuilderCraftingMachine.java`
   - `implements ICraftingMachine`，构造参数是 `AutoPartBuilderBlockEntity`。
   - `acceptsPlans()`：自身三个槽位为空（或输出槽可容纳）时才 true。
   - `pushPattern(details, inputs, ejection)`：
     1. 把 `KeyCounter[]` 摊平成 `List<ItemStack>`（`AEKey instanceof AEItemKey` 才转栈；其它类型直接 `return false`）。
     2. 分出图案物品（`stack.is(TinkerTags.Items.DEFAULT_PATTERNS)` 或 `TinkerTags.Items.PATTERNS`）与材料物品。
     3. `expectedOutput = details.getOutputs()[0]`，必须是 `AEItemKey` → `.toStack()`。
     4. `shapeHint = Pattern.fromItem(expectedOutput.getItem())`，再调 `resolveCraft(materials, patternItem, shapeHint, expectedOutput)`；命中后 `performCraft`。
     5. 结果与 `leftover` 按 `ejection` 方向 `insertItem` 到邻居（先用 simulate 预检容量，容量不够就整体 `return false` 不落子）。
   - `getCraftingMachineInfo()`：`new PatternContainerGroup(AEItemKey.of(本方块物品), 方块显示名, List.of())`。

4. `src/main/java/com/galtc2/compat/ae2/Ae2Compat.java`
   - 桥接：`static boolean isLoaded()`（`ModList.get().isLoaded("ae2")`）、`static @Nullable <T> LazyOptional<T> craftingMachine(AutoPartBuilderBlockEntity be, Direction side)`，**AE 类只在本文件及其内部类里出现**。
   - 调用方（BE）通过 `Ae2Compat` 拿 capability，未装 AE 时返回 `LazyOptional.empty()`。禁止 `@Mod.EventBusSubscriber`。

5. 注册与资源
   - `Register/galtcBlock.java`：`registerBlocks("auto_part_builder", AutoPartBuilderBlock::new)`（按现有 `ORE_GENERATOR` 的写法）。
   - `Register/galtcBlockEntity.java`：`BLOCK_ENTITIES.register("auto_part_builder", ...)`，`BlockEntityType.Builder.of(AutoPartBuilderBlockEntity::new, galtcBlock.AUTO_PART_BUILDER.get()).build(null)`。
   - `Galtc2.java` 的 `addCreative(...)` 里加进创造栏。
   - 资源（照 `ore_generator` 的现成文件抄结构）：`blockstates/auto_part_builder.json`、`models/block/auto_part_builder.json`、`models/item/auto_part_builder.json`、`loot_tables/blocks/auto_part_builder.json`、`lang/en_us.json` + 现有其它语言文件的条目。
   - **必须**补 `data/minecraft/tags/blocks/mineable/pickaxe.json`（项目已知坑：`requiresCorrectToolForDrops` 的方块忘了补就直接不掉落）。

## 3. 验证步骤（实现完必须跑）

1. 构建：`pwsh` 里 `$env:JAVA_HOME='C:\Program Files\Java\jdk-17'` 后 `& .\gradlew.bat build`，必须 BUILD SUCCESSFUL。
2. 起服：`& .\gradlew.bat runServer`（`run/config/fml.toml` 的 `maxThreads = 1` 已设，别动）。
3. 用探针（端口 39001）执行：
   - `setblock` 放方块 → `data get block` 确认 BE 存在；
   - `data merge block <x> <y> <z> {inv:{Size:3b,Items:[{Slot:0b,id:"tconstruct:iron_block",Count:1b},{Slot:1b,id:"tconstruct:pattern",Count:1b}]},pattern:"tconstruct:rod"}`；
   - 等若干 tick → `data get block <x> <y> <z> inv` 检查槽 2 出现 `tconstruct:tool_rod` 且带材质 NBT（`Material` 标签），槽 0/1 已被正确消耗。
4. 反向用例：把 `pattern` 改成不匹配的形状（如 `tconstruct:head` 配 `tconstruct:pattern` 但材料给非材料物品）→ 必须不出产物、不吞物品。

## 4. 已排除的方案（不要再试，都验证过）

- **不写自定义 AE 样板物品、不碰 AE 终端**：用户明确「样板编写是 AE 自带的功能」。所以没有 `galtc2:part_pattern`、没有 `PatternDetailsHelper.registerDecoder`、没有 mixin。
- **`RecipeManager.byType(...)` 不能直接用**：javap 实测 1.20.1 映射里 public 的只有 `getAllRecipesFor(RecipeType)` / `getRecipesFor(...)`。AE2 与匠魂能调 `byType` 是因为它们自带 access transformer，本项目没有也不为此加。
- **不能继承 `PartBuilderBlockEntity`**：其构造函数硬编码 `TinkerTables.partBuilderTile.get()`，BE 类型在父类构造里就定死了。
- **不能复用 `PartBuilderContainerWrapper`**：构造函数要 `PartBuilderBlockEntity` 实例。自己实现 `IPartBuilderContainer` 只有 3 个方法（`getStack` / `getPatternStack` / `getMaterial`）。
- **跨材料替换在 AE 里做不到**：AE 的合成记账要求「输入物品 / 产物物品 / 产出数量」三者严格对应，`IPatternDetails` 必须给出确定输出。详见 §5。

## 5. 已知限制（要如实告知，不要假装解决）

- AE 原生处理样板每个「形状 + 材料物品」要编一个样板；AE 的合成计算按样板里钉死的那个物品算，不会因为缺货改喂同材质的其它形态（锭/块/粒）。机器侧对这些形态一律接受，但 AE 的规划不会自动替换。要真正自动替换，得写自定义 AE 样板并在 `IInput.getPossibleInputs()` 里列同材质物品，那是后续独立议题。
- 方块现在有界面（§7），形状可以在界面里点图案按钮选；AE 推来的样板仍然会自动把形状绑好，两条路径共用同一份 `selectedPattern`。
- 形状提示用 `new Pattern(物品登记名)`（3.10.2.92 里没有 `Pattern.fromItem`），并对产物做 `isSameItemSameTags` 二次校验，不满足约定时退回「不限形状 + 产物比对」。
- 材料必须是 `craftable: true` 的匠魂材料。**实测 `tconstruct:iron` 的 `craftable` 是 false**（铁部件靠浇筑），拿铁锭测会一直"找不到配方"，别误判成代码 bug。

## 6. 实现与验证记录（2026-10-30）

已落地文件：

| 文件 | 作用 |
|---|---|
| `content/machine/AutoPartBuilderBlock.java` | 方块本体：LIT 状态、ticker、右键查看状态（潜行右键清形状）、破坏掉落三个槽位 |
| `content/machine/AutoPartBuilderBlockEntity.java` | 三槽（0 材料 / 1 图案物品 / 2 产物）、`IPartBuilderContainer` 实现、`findPlan`/`craftFor`、AE 推料支持、**产物自动弹出**（DOWN 优先推给相邻 `IItemHandler`） |
| `compat/ae2/AutoPartBuilderCraftingMachine.java` | AE `ICraftingMachine`：摊平 `KeyCounter[]` → 从样板声明产物反推形状 → 原子扣料产出 → 按推出方向弹出（多余材料一并退还） |
| `compat/ae2/Ae2Compat.java` | AE2 软联动门禁（`ModList` + capability 桥接），未装 AE2 不触碰任何 appeng 类型 |
| `Register/galtcBlock.java` / `galtcBlockEntity.java` / `galtctag.java` | 注册方块、方块实体、创造栏条目 |
| 资源 | blockstates、两个方块模型、物品模型、战利品表、`en_us`/`zh_cn` 语言、`mineable/pickaxe` 标签 |

实测证据（dev 服务端，`runServer`）：

1. 物品栏/tick 路径：`setblock` + `data merge block` 塞入 3 flint + 1 `tconstruct:pattern` + `pattern:"tconstruct:tool_binding"`，随后 `data get block` 得到
   `Slot 0 = flint ×2`、`Slot 2 = tconstruct:tool_binding ×1 tag:{Material:"tconstruct:flint"}`（空白图案已被消耗）。
2. 自动弹出：把箱子放在机器正下方，产物自动进入箱子（`10,99,10` 的箱子 NBT 里出现带材质 NBT 的部件）。
3. AE 推料路径：用临时调试命令构造真实 `AEProcessingPattern`（`PatternDetailsHelper.encodeProcessingPattern`）→ `PatternDetailsHelper.decodePattern` → `ICraftingMachine.pushPattern(..., Direction.UP)`，返回 **true**；机器内部完成扣料与产出，材料槽被清空（多余材料按推出方向退还），产物落在输出槽。调试命令与相关临时注册行已删除，`gradlew build` 通过。

未覆盖：
- 没有在真实样板供应器（Pattern Provider）旁做端到端测试——需要玩家放置供应器并放入样板，dev 环境无玩家。`pushPattern` 的输入/输出契约已按 AE 的调用约定逐项验证。
- 客户端未做视觉验证（模型用的是原版 smithing_table 贴图，LIT 状态共用同一模型）。


## 7. 可交互界面 + 按面 IO（2026-10-30 追加）

用户要求：界面照匠魂原版部件制作台，IO 照 AE 压印器。实现如下。

### 7.1 界面（`AutoPartBuilderMenu` + `client/AutoPartBuilderScreen`）

- 槽位布局照匠魂：图案物品槽 (8,43)、材料槽 (29,43)、产物槽 (148,42)，界面 176×184，玩家背包从 y=102 起。
- 中间是 4×4 的图案按钮网格（18px，左上 (51,15)），按钮多时滚轮翻页、右侧有滚动条；按钮图标直接 blit 匠魂的图案贴图
  `tconstruct:textures/gui/tinker_pattern/<pattern 路径>.png`（即 `Pattern.getTexture()`）。
- 点按钮 → `handleInventoryButtonClick(containerId, index)` → 服务端 `clickMenuButton` → `be.selectIndex(index)`；
  选中项用 `DataSlot` 同步到客户端高亮（绿色边框）。
- 底部显示材料信息：材料变体 + 每件花费（`IMaterialValue.getItemsUsed(1)`），无材料时红字提示。
- 界面不依赖任何外部 GUI 贴图，底板/槽位/按钮全部用 `GuiGraphics.fill` 画（槽位带原版风格凹陷边框）。
- 注册：`Register/galtcMenu`（`MenuType`）+ `Galtc2.ClientModEvents` 里 `MenuScreens.register`，方块 `use()` 走
  `NetworkHooks.openScreen(serverPlayer, be, pos)`，菜单工厂从 buffer 读 `BlockPos` 取 BE。

### 7.2 按面 IO（照 AE 压印器）

| 面 | 槽位 | 行为 |
|---|---|---|
| UP | 图案物品 | 只进不出（`isItemValid` 限定 `tinker:patterns` 标签） |
| DOWN | 材料 | 只进不出 |
| 四侧 | 产物 | 只出不进 |
| 无面 | 0/1 可进、2 可出 | 内部/未指定面的查询 |

每面一个缓存的 `LazyOptional<IItemHandler>`（AE 与漏斗会缓存能力，不能每次新建），`invalidateCaps` 时全部失效。
产物另外每 tick 自动推向相邻容器（DOWN 优先），AE 样板推料路径仍走 `pushPattern` 并按推出方向弹出。

### 7.3 验证

- 服务器：`setblock` + `data merge block` 喂 flint + `tconstruct:pattern` + `pattern:"tconstruct:tool_binding"`，
  产物 `tconstruct:tool_binding`（`Material: tconstruct:flint`）自动进入下方箱子。
- 客户端（dev 客户端 + mcdev 探针）：`/setblock 0 100 2 galtc2:auto_part_builder` 后右键，客户端屏幕切换为
  `com.galtc2.client.AutoPartBuilderScreen`，截图确认标题「自动部件制作台」、三个槽位、材料红字提示、物品栏都正常渲染。
- 未验证：装满图案后的按钮图标绘制、滚轮翻页、点按钮选中——需要在装有图案物品的界面里人眼确认。

### 7.4 图案图标为什么必须走图集（踩过的坑）

匠魂的 `tinker_pattern` 贴图**不是普通 GUI 贴图加载**的：它们被打进方块图集
`minecraft:block_atlas`。匠魂自己的 `PartBuilderScreen#drawRecipesItems` 就是
`minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(pattern.getTexture())` 再
`graphics.blit(x, y, 100, 16, 16, sprite)`。

反例（已修复）：直接 `graphics.blit(pattern.getTexture(), ...)`（普通贴图路径）会每帧抛
`ResourceProvider.getResourceOrThrow` 异常，界面变成 4×4 的紫黑棋盘格（缺失贴图），并刷满日志。

### 7.5 参考实现：ExtendedAE 这类 AE 附属怎么做扩展

读了 GlodBlock/ExtendedAE（`D:\game\mc\_refs\extendedae-src`）后确认的几条惯例：

1. **自定义机器 = 实现 `ICraftingMachine`**：`TileExMolecularAssembler implements ... ICraftingMachine`
   只提供 `getCraftingMachineInfo` / `pushPattern` / `acceptsPlans` 三个方法，和 AE 本体
   `MolecularAssemblerBlockEntity` 完全一致 —— 本项目 `AutoPartBuilderCraftingMachine` 就是同一套写法。
2. **菜单/界面 = 自己的 Container + Screen**：`ContainerExPatternTerminal extends PatternAccessTermMenu`、
   `GuiExPatternTerminal extends AEBaseScreen`。也就是说：**AE 附属想加编码相关的功能，是自带一个终端/菜单，
   而不是往 AE 现有终端里塞页签**（AE 的 4 种编码模式是硬编码枚举）。这反过来印证了本项目不做
   AE 终端 mixin 的选择。
3. 注册用 `DeferredRegister` / `Registry.register` 都行（ExtendedAE 是老式 `EAERegistryHandler`），
   能力用 Forge 的 capability 事件；必要时才上 mixin（它有 `mixins/` 目录，但都是针对 AE 内部类的小补丁）。
4. 配方/编码相关的公共 API 面很小：`PatternDetailsHelper`、`IPatternDetails`、`ICraftingMachine`，
   和我们在用的完全重合。

### 7.6 方块贴图（自制，混匠魂 + AE 画风）

原先用的是原版 `smithing_table` 贴图，画风不符。现在改为自制 16×16 贴图，配色**取自真实 jar 取样**：

| 来源 | 取样色 |
|---|---|
| 匠魂 `part_builder_top.png` / `side.png` | `#67502C #776031 #967441 #9F844D #AF8F55 #B8945F #C29D62` |
| AE2 `inscriber.png` / `inscriber_inside.png` | 浅灰机体 `#BEBEBE #B1B1B1`，深灰 `#666666 #595959 #4D4D4D #393939`（其侧面本就带木色 `#423B31`） |

四张图（`assets/galtc2/textures/block/`）：

- `auto_part_builder_top.png`：暖木台面 + 四周 AE 深灰机框 + 四角铆钉 + 中间**刻出的镐头形部件轮廓**（暗木色刻痕），两侧小能量指示点。
- `auto_part_builder_top_on.png`：工作态（`LIT=true`）：同样台面，刻痕填 AE 青蓝 `#58D3E8` 并给上/左沿加高光 `#A8EEF7`，指示点点亮。
- `auto_part_builder_side.png`：上沿浅灰机身高光 + 中间木板（两道深色木纹）+ 下沿深灰底座与铆钉 + 机体顶部青蓝能量灯。
- `auto_part_builder_bottom.png`：深灰金属底板 + 内凹方板 + 铆钉 + 中心能量点。

模型仍是 `cube_bottom_top`：`auto_part_builder.json` 用 top/side/bottom；`auto_part_builder_on.json` 顶面换 `top_on`；两者都显式声明 `particle`（侧贴图）。

生成脚本：`D:\game\mc\_refs\gen_auto_part_builder_textures.py`（Pillow，固定调色板 + 固定随机种子可复现；同时输出放大 12 倍的预览图 `_refs\auto_part_builder_preview.png`）。

### 7.7 界面细节：玩家物品栏照原版画

之前物品栏区域只是整块灰底、槽位几乎看不见。现在按原版容器界面的画法补齐：

- 每个槽位都是 18×18 的原版凹陷格：`#8B8B8B` 底 + `#373737` 上/左内阴影 + `#FFFFFF` 下/右高光（机器 3 个槽与玩家 36 个槽统一用同一套 `drawSlotBackground`）。
- 玩家区：3×9 主背包（起点 `(7,101)`，与菜单槽位 `(8,102)` 对齐）+ 1×9 快捷栏（`(7,159)` / 槽位 `(8,160)`）。
- 上半部分与物品栏之间加原版那两条分隔线（`#8B8B8B` + `#FFFFFF`，y=89/90），`物品栏` 标题放在 y=92。
- 做不出来的形状不再压到几乎全黑（`0xA0` → `0x70` 的暗罩），并给 4×4 图案网格加了一圈外框，读起来像一块面板。
