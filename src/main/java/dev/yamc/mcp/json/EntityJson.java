package dev.yamc.mcp.json;

import dev.yamc.mcp.util.Mc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.yamc.mcp.util.Json;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/** JSON for entities. */
public final class EntityJson {
    private EntityJson() {}

    /** player, item, hostile, passive or other. */
    public static String category(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof ItemEntity) return "item";
        if (e instanceof Enemy) return "hostile";
        if (e instanceof Animal || e instanceof WaterAnimal || e instanceof AbstractVillager || e instanceof AgeableMob) return "passive";
        return "other";
    }

    /** Summary: id, type, name, category, position, distance, health. */
    public static JsonObject summary(Entity e, LocalPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("entity_id", e.getId());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        if (e.hasCustomName()) o.addProperty("custom_name", true);
        o.addProperty("category", category(e));
        o.add("pos", Json.vec(e.position()));
        o.addProperty("distance", Json.round(e.distanceTo(p)));
        if (e instanceof LivingEntity le) {
            o.addProperty("health", Json.round(le.getHealth()));
            o.addProperty("max_health", Json.round(le.getMaxHealth()));
            if (le.isBaby()) o.addProperty("baby", true);
        }
        if (e instanceof ItemEntity ie) o.add("item", Stacks.json(ie.getItem(), false, p.registryAccess()));
        if (e instanceof Mob mob && mob.isAggressive()) o.addProperty("aggressive", true);
        return o;
    }

    /** Summary plus motion, pose, team, vehicle/passengers, equipment and effects. */
    public static JsonObject details(Entity e, LocalPlayer p) {
        JsonObject o = summary(e, p);
        o.addProperty("uuid", e.getUUID().toString());
        o.add("velocity", Json.vec(e.getDeltaMovement()));
        o.addProperty("yaw", Json.round(e.getYRot()));
        o.addProperty("pitch", Json.round(e.getXRot()));
        o.addProperty("pose", e.getPose().name().toLowerCase(Locale.ROOT));
        o.addProperty("on_fire", e.isOnFire());
        o.addProperty("glowing", e.isCurrentlyGlowing());
        o.addProperty("invisible", e.isInvisible());
        if (e.getTeam() != null) o.addProperty("team", e.getTeam().getName());
        if (e.getVehicle() != null) o.addProperty("vehicle_id", e.getVehicle().getId());
        if (!e.getPassengers().isEmpty()) o.add("passenger_ids", Json.array(e.getPassengers(), x -> new JsonPrimitive(x.getId())));
        if (e instanceof LivingEntity le) {
            JsonObject eq = new JsonObject();
            for (EquipmentSlot s : EquipmentSlot.values()) {
                var st = le.getItemBySlot(s);
                if (!st.isEmpty()) eq.add(s.getName(), Stacks.json(st, false, p.registryAccess()));
            }
            o.add("equipment", eq);
            JsonArray fx = new JsonArray();
            for (MobEffectInstance i : le.getActiveEffects()) {
                JsonObject x = new JsonObject();
                x.addProperty("id", i.getEffect().getRegisteredName());
                x.addProperty("amplifier", i.getAmplifier());
                fx.add(x);
            }
            o.add("effects", fx);
            o.addProperty("armor", le.getArmorValue());
        }
        if (e instanceof Player pl) o.addProperty("player_name", Mc.name(pl.getGameProfile()));
        return o;
    }
}
