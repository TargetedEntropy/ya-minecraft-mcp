package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.json.Stacks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

final class PlayerTools {
    private PlayerTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("player_status").title("Player status")
                .desc("Current player state (with game_time/timestamp_ms so you can tell when coordinates are stale; re-read before acting on positions): position, rotation, dimension, biome, health, absorption, food, saturation, air, XP, armor, game mode, active effects, movement flags, vehicle and held items.")
                .handler(a -> ToolResult.json(status())));

        r.add(Tool.builder("player_inventory").title("Player inventory")
                .desc("Full player inventory: hotbar (slots 0-8), main (9-35), armor, offhand, selected slot, plus the carried (cursor) stack. Set include_components for complete item data components (enchantments, custom data, modded components).")
                .schema(Schema.of()
                        .bool("include_components", "Include full encoded data components for each stack (larger output). Default false.")
                        .bool("include_empty", "Include empty slots. Default false."))
                .handler(a -> ToolResult.json(inventory(a.bool("include_components", false), a.bool("include_empty", false)))));
    }

    static JsonObject status() {
        Minecraft mc = Ctx.mc();
        LocalPlayer p = Ctx.player();
        JsonObject o = new JsonObject();
        o.addProperty("game_time", p.level().getGameTime());
        o.addProperty("timestamp_ms", System.currentTimeMillis());
        o.addProperty("name", p.getGameProfile().getName());
        o.addProperty("uuid", p.getUUID().toString());
        o.add("position", Json.vec(p.position()));
        o.add("block_position", Json.pos(p.blockPosition()));
        o.addProperty("yaw", Json.round(net.minecraft.util.Mth.wrapDegrees(p.getYRot())));
        o.addProperty("pitch", Json.round(p.getXRot()));
        o.addProperty("facing", p.getDirection().getName());
        o.addProperty("dimension", p.level().dimension().location().toString());
        o.addProperty("biome", p.level().getBiome(p.blockPosition()).getRegisteredName());
        o.addProperty("health", p.getHealth());
        o.addProperty("max_health", p.getMaxHealth());
        o.addProperty("absorption", p.getAbsorptionAmount());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", Json.round(p.getFoodData().getSaturationLevel()));
        o.addProperty("air", p.getAirSupply());
        o.addProperty("max_air", p.getMaxAirSupply());
        o.addProperty("xp_level", p.experienceLevel);
        o.addProperty("xp_progress", Json.round(p.experienceProgress));
        o.addProperty("xp_total", p.totalExperience);
        o.addProperty("armor", p.getArmorValue());
        AttributeInstance tough = p.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (tough != null) o.addProperty("armor_toughness", Json.round(tough.getValue()));
        o.addProperty("gamemode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName());
        JsonObject flags = new JsonObject();
        flags.addProperty("on_ground", p.onGround());
        flags.addProperty("in_water", p.isInWater());
        flags.addProperty("in_lava", p.isInLava());
        flags.addProperty("underwater", p.isUnderWater());
        flags.addProperty("sprinting", p.isSprinting());
        flags.addProperty("sneaking", p.isShiftKeyDown());
        flags.addProperty("swimming", p.isSwimming());
        flags.addProperty("flying", p.getAbilities().flying);
        flags.addProperty("fall_flying", p.isFallFlying());
        flags.addProperty("sleeping", p.isSleeping());
        flags.addProperty("on_fire", p.isOnFire());
        flags.addProperty("freezing", p.isFullyFrozen());
        flags.addProperty("dead", p.isDeadOrDying());
        o.add("flags", flags);
        o.add("velocity", Json.vec(p.getDeltaMovement()));
        if (p.getVehicle() != null) {
            JsonObject v = new JsonObject();
            v.addProperty("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType()).toString());
            v.addProperty("entity_id", p.getVehicle().getId());
            o.add("vehicle", v);
        }
        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getEffect().getRegisteredName());
            eo.addProperty("name", e.getEffect().value().getDisplayName().getString());
            eo.addProperty("amplifier", e.getAmplifier());
            eo.addProperty("level", e.getAmplifier() + 1);
            eo.addProperty("duration_ticks", e.getDuration());
            eo.addProperty("infinite", e.isInfiniteDuration());
            eo.addProperty("ambient", e.isAmbient());
            effects.add(eo);
        }
        o.add("effects", effects);
        o.addProperty("selected_slot", p.getInventory().selected);
        o.add("main_hand", Stacks.json(p.getMainHandItem(), false, p.registryAccess()));
        o.add("off_hand", Stacks.json(p.getOffhandItem(), false, p.registryAccess()));
        return o;
    }

    static JsonObject inventory(boolean full, boolean includeEmpty) {
        LocalPlayer p = Ctx.player();
        Inventory inv = p.getInventory();
        JsonObject o = new JsonObject();
        o.addProperty("selected_slot", inv.selected);
        JsonArray hotbar = new JsonArray(), main = new JsonArray();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() && !includeEmpty) continue;
            JsonObject so = Stacks.json(s, full, p.registryAccess());
            so.addProperty("slot", i);
            (i < 9 ? hotbar : main).add(so);
        }
        o.add("hotbar", hotbar);
        o.add("main", main);
        JsonObject armor = new JsonObject();
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < inv.armor.size() && i < 4; i++) {
            ItemStack s = inv.armor.get(i);
            if (s.isEmpty() && !includeEmpty) continue;
            armor.add(slots[i].getName(), Stacks.json(s, full, p.registryAccess()));
        }
        o.add("armor", armor);
        o.add("offhand", Stacks.json(inv.offhand.get(0), full, p.registryAccess()));
        ItemStack carried = p.containerMenu.getCarried();
        if (!carried.isEmpty()) o.add("carried", Stacks.json(carried, full, p.registryAccess()));
        int used = 0;
        for (ItemStack s : inv.items) if (!s.isEmpty()) used++;
        o.addProperty("used_slots", used);
        o.addProperty("free_slots", inv.items.size() - used);
        return o;
    }
}
