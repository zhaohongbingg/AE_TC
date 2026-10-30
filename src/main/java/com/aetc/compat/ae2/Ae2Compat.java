package com.aetc.compat.ae2;

import appeng.capabilities.Capabilities;
import com.aetc.content.machine.AutoPartBuilderBlockEntity;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * AE2 软联动的唯一入口。
 * <p>
 * 所有 appeng 类型只允许出现在本类与 {@link AutoPartBuilderCraftingMachine} 里；方块实体通过本类
 * 判断/获取 AE 能力，未安装 AE2 时不会触碰任何 AE 类（{@link ModList#isLoaded(String)} 短路在前）。
 */
public final class Ae2Compat {

    private static final Map<AutoPartBuilderBlockEntity, LazyOptional<AutoPartBuilderCraftingMachine>> MACHINES =
            new WeakHashMap<>();

    private Ae2Compat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("ae2");
    }

    /** 传入的 capability 是否是 AE 的 {@code ICraftingMachine} */
    public static boolean isCraftingMachineCapability(Capability<?> cap) {
        return cap == Capabilities.CRAFTING_MACHINE;
    }

    /** 每个方块实体一个懒加载的机器实例（AE 会缓存 capability，不要每次新建） */
    public static synchronized LazyOptional<AutoPartBuilderCraftingMachine> craftingMachine(
            AutoPartBuilderBlockEntity machine, @Nullable Direction side) {
        return MACHINES.computeIfAbsent(machine,
                be -> LazyOptional.of(() -> new AutoPartBuilderCraftingMachine(be)));
    }

    public static synchronized void invalidate(AutoPartBuilderBlockEntity machine) {
        LazyOptional<AutoPartBuilderCraftingMachine> optional = MACHINES.remove(machine);
        if (optional != null) {
            optional.invalidate();
        }
    }
}
