package dev.yamc.mcp.util;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Inventories {
    private Inventories() {}

    /** Total count per item across the main inventory and offhand (and armor slots if asked). */
    public static Map<Item, Integer> counts(Inventory inv, boolean includeArmor) {
        Map<Item, Integer> m = new HashMap<>();
        for (List<ItemStack> part : includeArmor ? List.of(inv.items, inv.armor, inv.offhand) : List.of(inv.items, inv.offhand))
            for (ItemStack s : part) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        return m;
    }
}
