package dev.yamc.mcp.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.util.IntegratedServer;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Checks a namespace's blocks, items and entity types against the loaded resource packs (and, in singleplayer, the
 * server's loot tables) for the problems that make them look broken in game. Reads the resource files directly, so it
 * reports what is missing rather than relying on the game's fallbacks. Client thread.
 */
public final class AssetAudit {
    private AssetAudit() {}

    /** Parents provided by the game itself rather than by a model file. */
    private static final String BUILTIN = "builtin/";

    private final ResourceManager resources = Minecraft.getInstance().getResourceManager();
    private final Language lang = Language.getInstance();
    private final @Nullable MinecraftServer server = IntegratedServer.get();
    /** Problems found in each model and its parents, by model id: each model is read once. */
    private final Map<ResourceLocation, List<String>> modelProblems = new HashMap<>();

    public static JsonObject run(String namespace, int limit) {
        if (namespace.isEmpty()) throw new ToolException("Give a namespace (mod id).");
        AssetAudit audit = new AssetAudit();
        JsonObject checked = new JsonObject();
        List<JsonObject> problems = new ArrayList<>();
        checked.addProperty("blocks", audit.each(BuiltInRegistries.BLOCK, namespace, "block", audit::block, problems));
        checked.addProperty("items", audit.each(BuiltInRegistries.ITEM, namespace, "item", audit::item, problems));
        checked.addProperty("entity_types", audit.each(BuiltInRegistries.ENTITY_TYPE, namespace, "entity_type",
                (e, issues) -> audit.translated(e.getDescriptionId(), issues), problems));
        int total = checked.get("blocks").getAsInt() + checked.get("items").getAsInt() + checked.get("entity_types").getAsInt();
        if (total == 0) throw new ToolException("Nothing is registered in namespace '" + namespace + "'. Check the mod id with mods_list.");

        JsonObject o = new JsonObject();
        o.addProperty("namespace", namespace);
        o.add("checked", checked);
        o.addProperty("loot_tables_checked", audit.server != null);
        o.addProperty("entries_with_problems", problems.size());
        o.add("problems", Json.array(problems.subList(0, Math.min(limit, problems.size())), p -> p));
        if (problems.size() > limit) o.addProperty("truncated", true);
        return o;
    }

    /** Audits every entry of a registry in the namespace; adds one object per entry with problems; returns how many were checked. */
    private <T> int each(Registry<T> reg, String namespace, String kind, BiConsumer<T, Set<String>> audit, List<JsonObject> out) {
        int n = 0;
        for (var e : reg.entrySet()) {
            ResourceLocation id = Mc.id(e.getKey());
            if (!id.getNamespace().equals(namespace)) continue;
            n++;
            Set<String> issues = new LinkedHashSet<>();
            audit.accept(e.getValue(), issues);
            if (issues.isEmpty()) continue;
            JsonObject p = new JsonObject();
            p.addProperty("kind", kind);
            p.addProperty("id", id.toString());
            p.add("issues", Json.strings(issues));
            out.add(p);
        }
        return n;
    }

    private void block(Block b, Set<String> issues) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        if (b.defaultBlockState().isAir()) return;
        translated(b.getDescriptionId(), issues);
        ResourceLocation file = file(id, "blockstates/", ".json");
        Optional<JsonElement> json = read(file, issues);
        if (json.isEmpty()) issues.add("no blockstate file " + file);
        else for (ResourceLocation m : modelRefs(json.get())) modelOk(m, "blockstate", issues);
        if (server != null) {
            ResourceLocation loot = Mc.lootTable(b);
            if (loot != null && Mc.lootTable(server, loot) == null) issues.add("no loot table " + loot + " (the block drops nothing)");
        }
    }

    private void item(net.minecraft.world.item.Item item, Set<String> issues) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (item == net.minecraft.world.item.Items.AIR) return;
        translated(item.getDescriptionId(), issues);
        //? if >=1.21.4 {
        /*ResourceLocation file = file(id, "items/", ".json");
        Optional<JsonElement> json = read(file, issues);
        if (json.isEmpty()) issues.add("no item model definition " + file);
        else for (ResourceLocation m : modelRefs(json.get())) modelOk(m, "item definition", issues);
        *///?} else {
        ResourceLocation model = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "item/" + id.getPath());
        modelOk(model, "item", issues);
        //?}
    }

    void translated(String key, Set<String> issues) {
        if (!lang.has(key)) issues.add("no translation for " + key);
    }

    /** Records the problems of a model referenced from {@code from}, including its parents and textures. */
    private void modelOk(ResourceLocation model, String from, Set<String> issues) {
        for (String p : modelProblems(model, new HashSet<>())) issues.add(p + " (via " + from + ")");
    }

    private List<String> modelProblems(ResourceLocation model, Set<ResourceLocation> visiting) {
        List<String> known = modelProblems.get(model);
        if (known != null) return known;
        if (model.getPath().startsWith(BUILTIN) || !visiting.add(model)) return List.of();
        List<String> out = new ArrayList<>();
        ResourceLocation file = file(model, "models/", ".json");
        Set<String> readIssues = new LinkedHashSet<>();
        Optional<JsonElement> json = read(file, readIssues);
        out.addAll(readIssues);
        if (json.isEmpty()) {
            if (readIssues.isEmpty()) out.add("missing model " + model);
        } else if (json.get() instanceof JsonObject o) {
            if (o.get("parent") instanceof com.google.gson.JsonPrimitive parent) {
                ResourceLocation pid = ResourceLocation.tryParse(parent.getAsString());
                if (pid == null) out.add("model " + model + " has an invalid parent '" + parent.getAsString() + "'");
                else out.addAll(modelProblems(pid, visiting));
            }
            if (o.get("textures") instanceof JsonObject textures) {
                for (var t : textures.entrySet()) {
                    if (!t.getValue().isJsonPrimitive()) continue;
                    String ref = t.getValue().getAsString();
                    if (ref.startsWith("#")) continue;
                    ResourceLocation tex = ResourceLocation.tryParse(ref);
                    if (tex == null) out.add("model " + model + " has an invalid texture '" + ref + "'");
                    else if (!textureExists(tex)) out.add("missing texture " + tex + " (used by model " + model + ")");
                }
            }
        }
        visiting.remove(model);
        modelProblems.put(model, out);
        return out;
    }

    private boolean textureExists(ResourceLocation tex) {
        return tex.equals(MissingTextureAtlasSprite.getLocation()) || resources.getResource(file(tex, "textures/", ".png")).isPresent()
                || Mc.atlasHasSprite(Minecraft.getInstance(), tex);
    }

    /** Model ids referenced by a blockstate or item model definition file. */
    private static Set<ResourceLocation> modelRefs(JsonElement json) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        collectModels(json, out);
        return out;
    }

    private static void collectModels(JsonElement e, Set<ResourceLocation> out) {
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectModels(x, out);
        } else if (e instanceof JsonObject o) {
            for (String key : List.of("model", "base")) {
                if (o.get(key) instanceof com.google.gson.JsonPrimitive p && p.isString()) {
                    ResourceLocation id = ResourceLocation.tryParse(p.getAsString());
                    if (id != null) out.add(id);
                }
            }
            for (var en : o.entrySet()) collectModels(en.getValue(), out);
        }
    }

    private static ResourceLocation file(ResourceLocation id, String prefix, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), prefix + id.getPath() + suffix);
    }

    /** The parsed file, empty if it does not exist; a file that cannot be parsed is reported as an issue. */
    private Optional<JsonElement> read(ResourceLocation file, Set<String> issues) {
        Optional<Resource> res = resources.getResource(file);
        if (res.isEmpty()) return Optional.empty();
        try (Reader r = res.get().openAsReader()) {
            return Optional.of(JsonParser.parseReader(r));
        } catch (Exception e) {
            issues.add("cannot read " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
