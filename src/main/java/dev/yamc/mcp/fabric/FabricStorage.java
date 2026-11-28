package dev.yamc.mcp.fabric;

//? if fabric {
/*import dev.yamc.mcp.platform.Platform;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// Block storages through the Fabric Transfer API (only loaded when the API is), plus Team Reborn Energy if installed.
final class FabricStorage {
    private FabricStorage() {}

    static Platform.Storage read(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        List<Platform.Slot> items = null;
        Storage<ItemVariant> is = ItemStorage.SIDED.find(level, pos, side);
        if (is != null) {
            items = new ArrayList<>();
            for (StorageView<ItemVariant> v : is) {
                ItemStack stack = v.isResourceBlank() ? ItemStack.EMPTY : v.getResource().toStack((int) Math.min(v.getAmount(), Integer.MAX_VALUE));
                items.add(new Platform.Slot(stack, v.getAmount(), v.getCapacity()));
            }
        }
        List<Platform.Tank> fluids = null;
        Storage<FluidVariant> fs = FluidStorage.SIDED.find(level, pos, side);
        if (fs != null) {
            fluids = new ArrayList<>();
            for (StorageView<FluidVariant> v : fs)
                fluids.add(new Platform.Tank(v.isResourceBlank() ? null : BuiltInRegistries.FLUID.getKey(v.getResource().getFluid()), v.getAmount(), v.getCapacity()));
        }
        return new Platform.Storage(items, fluids, energy(level, pos, side), FluidConstants.BUCKET);
    }

    // Team Reborn Energy's EnergyStorage.SIDED, the de facto Fabric energy API, when a mod ships it.
    @SuppressWarnings("unchecked")
    private static Platform.@Nullable Energy energy(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        try {
            Class<?> api = Class.forName("team.reborn.energy.api.EnergyStorage");
            var lookup = (BlockApiLookup<Object, Direction>) api.getField("SIDED").get(null);
            Object e = lookup.find(level, pos, side);
            if (e == null) return null;
            return new Platform.Energy((long) api.getMethod("getAmount").invoke(e), (long) api.getMethod("getCapacity").invoke(e));
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
*///?}
