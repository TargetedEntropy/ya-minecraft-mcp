package dev.yamc.mcp.util;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Player inventory access that is the same on every version (armor and offhand moved to equipment in 1.21.5). */
public final class Inventories {
    private Inventories() {}

    /** Armor slots in inventory order: feet, legs, chest, head. */
    public static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    /** The 36 main slots (0-8 hotbar, 9-35 main). */
    public static List<ItemStack> main(Inventory inv) {
        //? if >=1.21.5 {
        /*return inv.getNonEquipmentItems();
        *///?} else
        return inv.items;
    }

    /** Armor stacks, feet to head. */
    public static List<ItemStack> armor(Inventory inv) {
        List<ItemStack> out = new ArrayList<>(4);
        for (EquipmentSlot s : ARMOR) out.add(inv.player.getItemBySlot(s));
        return out;
    }

    public static int selected(Inventory inv) {
        //? if >=1.21.5 {
        /*return inv.getSelectedSlot();
        *///?} else
        return inv.selected;
    }

    public static void select(Inventory inv, int slot) {
        //? if >=1.21.5 {
        /*inv.setSelectedSlot(slot);
        *///?} else
        inv.selected = slot;
    }

    /** Total count per item across the main inventory and offhand (and armor slots if asked). */
    public static Map<Item, Integer> counts(Inventory inv, boolean includeArmor) {
        Map<Item, Integer> m = new HashMap<>();
        List<ItemStack> all = new ArrayList<>(main(inv));
        all.add(inv.player.getOffhandItem());
        if (includeArmor) all.addAll(armor(inv));
        for (ItemStack s : all) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        return m;
    }
}
