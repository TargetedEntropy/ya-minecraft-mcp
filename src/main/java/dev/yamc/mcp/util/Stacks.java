package dev.yamc.mcp.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.component.DataComponents;

public final class Stacks {
    private Stacks() {}

    /** Compact item stack JSON. With full=true, the non-default data components are included (codec-encoded). */
    public static JsonObject json(ItemStack stack, boolean full, HolderLookup.Provider regs) {
        JsonObject o = new JsonObject();
        if (stack.isEmpty()) {
            o.addProperty("id", "minecraft:air");
            o.addProperty("count", 0);
            return o;
        }
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("max_damage", stack.getMaxDamage());
        }
        if (stack.getMaxStackSize() != 64) o.addProperty("max_stack", stack.getMaxStackSize());
        ItemEnchantments ench = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (!ench.isEmpty() || !stored.isEmpty()) {
            JsonObject e = new JsonObject();
            for (var en : ench.entrySet()) e.addProperty(en.getKey().getRegisteredName(), en.getIntValue());
            for (var en : stored.entrySet()) e.addProperty(en.getKey().getRegisteredName(), en.getIntValue());
            o.add("enchantments", e);
        }
        if (stack.has(DataComponents.CUSTOM_NAME)) o.addProperty("custom_name", true);
        DataComponentPatch patch = stack.getComponentsPatch();
        if (full && !patch.isEmpty()) {
            o.add("components", Json.encode(DataComponentPatch.CODEC, patch, regs));
        } else if (!patch.isEmpty()) {
            JsonArray keys = new JsonArray();
            for (var en : patch.entrySet()) {
                var k = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(en.getKey());
                keys.add((en.getValue().isPresent() ? "" : "!") + k);
            }
            o.add("component_keys", keys);
        }
        return o;
    }
}
