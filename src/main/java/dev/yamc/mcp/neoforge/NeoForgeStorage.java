package dev.yamc.mcp.neoforge;

//? if neoforge {
import dev.yamc.mcp.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Block capabilities (item, fluid, energy) read for capabilities_at. */
final class NeoForgeStorage {
    private NeoForgeStorage() {}

    static Platform.Storage read(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        List<Platform.Slot> items = null;
        List<Platform.Tank> fluids = null;
        Platform.Energy energy = null;
        //? if >=1.21.9 {
        /*var ih = level.getCapability(Capabilities.Item.BLOCK, pos, side);
        if (ih != null) {
            items = new ArrayList<>();
            for (int i = 0; i < ih.size(); i++) {
                var r = ih.getResource(i);
                long amount = ih.getAmountAsLong(i);
                items.add(new Platform.Slot(r.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : r.toStack((int) Math.min(amount, Integer.MAX_VALUE)),
                        amount, ih.getCapacityAsLong(i, r)));
            }
        }
        var fh = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        if (fh != null) {
            fluids = new ArrayList<>();
            for (int i = 0; i < fh.size(); i++) {
                var r = fh.getResource(i);
                fluids.add(new Platform.Tank(r.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(r.getFluid()), fh.getAmountAsLong(i), fh.getCapacityAsLong(i, r)));
            }
        }
        var eh = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        if (eh != null) energy = new Platform.Energy(eh.getAmountAsLong(), eh.getCapacityAsLong());
        *///?} else {
        var ih = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (ih != null) {
            items = new ArrayList<>();
            for (int i = 0; i < ih.getSlots(); i++) {
                var s = ih.getStackInSlot(i);
                items.add(new Platform.Slot(s.copy(), s.getCount(), ih.getSlotLimit(i)));
            }
        }
        var fh = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (fh != null) {
            fluids = new ArrayList<>();
            for (int i = 0; i < fh.getTanks(); i++) {
                var f = fh.getFluidInTank(i);
                fluids.add(new Platform.Tank(f.isEmpty() ? null : BuiltInRegistries.FLUID.getKey(f.getFluid()), f.getAmount(), fh.getTankCapacity(i)));
            }
        }
        var eh = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        if (eh != null) energy = new Platform.Energy(eh.getEnergyStored(), eh.getMaxEnergyStored());
        //?}
        return new Platform.Storage(items, fluids, energy, 1000);
    }
}
//?}
