package dev.yamc.mcp.tools;

import com.google.gson.JsonObject;
import dev.yamc.mcp.json.EntityJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Paging;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/** Entities loaded on the client. */
final class EntityTools {
    private EntityTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("nearby_entities").title("Nearby entities")
                .desc("Entities near the player, nearest first: type, name, entity_id, position, distance, health, hostility. Filter by type id, category (hostile, passive, player, item, other) or name/type substring.")
                .schema(Schema.of().number("radius", "Search radius (default 32, max 256).")
                        .str("type", "Entity type id, e.g. minecraft:zombie.")
                        .enumStr("category", "Entity category filter.", false, "all", "hostile", "passive", "player", "item", "other")
                        .str("query", "Substring of type id or name.")
                        .paging(50))
                .handler(a -> ToolResult.json(nearby(a))));
        r.add(Tool.builder("entity_info").title("Entity details")
                .desc("Details of a single entity by entity_id (from nearby_entities / look_target): equipment, effects, health, attributes, passengers, vehicle, pose.")
                .schema(Schema.of().reqInt("entity_id", "Numeric entity id."))
                .handler(a -> ToolResult.json(entityInfo(a.reqInt("entity_id")))));
    }

    static JsonObject nearby(Args a) {
        LocalPlayer p = Ctx.player();
        ClientLevel l = Ctx.level();
        double r = Math.min(256, Math.max(1, a.dbl("radius", 32)));
        String type = a.has("type") ? Args.parseId(a.str("type", "")).toString() : null;
        String cat = a.str("category", "all");
        IdFilter f = IdFilter.from(a, "-", "query", "-");
        List<Entity> list = l.getEntities(p, new AABB(p.position(), p.position()).inflate(r), e -> {
            if (e.distanceTo(p) > r) return false;
            String tid = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (type != null && !type.equals(tid)) return false;
            if (!"all".equals(cat) && !cat.equals(EntityJson.category(e))) return false;
            return f.test(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), () -> e.getName().getString());
        });
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return Paging.page(list, a, e -> EntityJson.summary(e, p));
    }

    static JsonObject entityInfo(int id) {
        Entity e = Ctx.level().getEntity(id);
        if (e == null) throw new ToolException("No entity with id " + id + " is loaded on the client.");
        return EntityJson.details(e, Ctx.player());
    }
}
