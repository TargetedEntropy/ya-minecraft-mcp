package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.Locale;

/** A living entity's AI and stats as the server runs them (goals, brain, navigation, attributes), for entity_inspect. */
public final class EntityAi {
    private EntityAi() {}

    private static final int MAX_VALUE_TEXT = 200;

    /** Server thread. */
    public static JsonObject describe(LivingEntity e) {
        JsonObject o = new JsonObject();
        o.addProperty("health", Json.round(e.getHealth()));
        o.add("attributes", attributes(e));
        JsonArray fx = new JsonArray();
        for (MobEffectInstance i : e.getActiveEffects()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", i.getEffect().getRegisteredName());
            x.addProperty("amplifier", i.getAmplifier());
            x.addProperty("duration", i.getDuration());
            fx.add(x);
        }
        o.add("effects", fx);
        if (e instanceof Mob mob) {
            o.add("goals", goals(mob.goalSelector));
            o.add("target_goals", goals(mob.targetSelector));
            LivingEntity target = mob.getTarget();
            if (target != null) {
                JsonObject t = new JsonObject();
                t.addProperty("entity_id", target.getId());
                t.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
                o.add("target", t);
            }
            o.addProperty("no_ai", mob.isNoAi());
            o.add("navigation", navigation(mob));
        }
        JsonObject brain = brain(e.getBrain());
        if (brain.size() > 0) o.add("brain", brain);
        return o;
    }

    private static JsonArray attributes(LivingEntity e) {
        JsonArray out = new JsonArray();
        Mc.holders(BuiltInRegistries.ATTRIBUTE).forEach(h -> {
            AttributeInstance inst = e.getAttribute(h);
            if (inst == null) return;
            JsonObject x = new JsonObject();
            x.addProperty("id", h.getRegisteredName());
            x.addProperty("base", Json.round(inst.getBaseValue()));
            x.addProperty("value", Json.round(inst.getValue()));
            JsonArray mods = new JsonArray();
            for (AttributeModifier m : inst.getModifiers()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("id", m.id().toString());
                mo.addProperty("amount", m.amount());
                mo.addProperty("operation", m.operation().name().toLowerCase(Locale.ROOT));
                mods.add(mo);
            }
            if (!mods.isEmpty()) x.add("modifiers", mods);
            out.add(x);
        });
        return out;
    }

    private static JsonArray goals(GoalSelector selector) {
        JsonArray out = new JsonArray();
        selector.getAvailableGoals().stream().sorted(Comparator.comparingInt(WrappedGoal::getPriority)).forEach(g -> {
            JsonObject x = new JsonObject();
            x.addProperty("priority", g.getPriority());
            x.addProperty("goal", g.getGoal().getClass().getName());
            x.addProperty("running", g.isRunning());
            out.add(x);
        });
        return out;
    }

    private static JsonObject navigation(Mob mob) {
        JsonObject o = new JsonObject();
        o.addProperty("class", mob.getNavigation().getClass().getSimpleName());
        o.addProperty("in_progress", mob.getNavigation().isInProgress());
        Path p = mob.getNavigation().getPath();
        if (p != null) {
            JsonObject path = new JsonObject();
            if (p.getTarget() != null) path.add("target", Json.pos(p.getTarget()));
            path.addProperty("nodes", p.getNodeCount());
            path.addProperty("next_node", p.getNextNodeIndex());
            path.addProperty("reaches_target", p.canReach());
            path.addProperty("done", p.isDone());
            o.add("path", path);
        }
        return o;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JsonObject brain(Brain<?> brain) {
        JsonObject o = new JsonObject();
        JsonArray activities = new JsonArray();
        brain.getActiveActivities().forEach(a -> activities.add(a.getName()));
        if (!activities.isEmpty()) o.add("activities", activities);
        JsonArray behaviors = new JsonArray();
        brain.getRunningBehaviors().forEach(b -> behaviors.add(b.debugString()));
        if (!behaviors.isEmpty()) o.add("running_behaviors", behaviors);
        JsonObject memories = new JsonObject();
        for (MemoryModuleType<?> type : BuiltInRegistries.MEMORY_MODULE_TYPE) {
            if (!brain.hasMemoryValue(type)) continue;
            Object value = brain.getMemory((MemoryModuleType) type).orElse(null);
            String text = String.valueOf(value);
            memories.addProperty(String.valueOf(BuiltInRegistries.MEMORY_MODULE_TYPE.getKey(type)),
                    text.length() > MAX_VALUE_TEXT ? text.substring(0, MAX_VALUE_TEXT) + "..." : text);
        }
        if (memories.size() > 0) o.add("memories", memories);
        return o;
    }
}
