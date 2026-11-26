package dev.yamc.mcp.tools;

import dev.yamc.mcp.YamcMcp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import dev.yamc.mcp.json.RecipeJson;
import dev.yamc.mcp.json.RegistryJson;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Source;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

final class ExportTools {
    private ExportTools() {}

    static void register(ToolRegistry r) {
        r.add(Tool.builder("export_data").title("Export data to disk")
                .desc("Dump registries (ids, names, tags, and codec JSON for data-driven entries), tags and recipes to JSON files under <game dir>/yamcmcp-export/<timestamp>/ for offline analysis or grep. Returns the directory and file counts.")
                .schema(Schema.of().strArray("what", "Any of registries, tags, recipes (default all).")
                        .str("namespace", "Only registries whose id is in this namespace (registries/tags export).")
                        .str("query", "Only registries whose id contains this (registries/tags export)."))
                .anyThread()
                .handler(ExportTools::export));
    }

    static ToolResult export(Args a) throws Exception {
        List<String> what = a.strList("what");
        boolean all = what.isEmpty(), registries = all || what.contains("registries"), tags = all || what.contains("tags");
        IdFilter f = IdFilter.from(a);
        Path dir = YamcMcp.platform().gameDir().resolve("yamcmcp-export").resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(dir);
        JsonObject summary = new JsonObject();
        summary.addProperty("directory", dir.toAbsolutePath().toString());
        if (registries || tags) {
            int files = 0, entries = 0;
            for (var e : ClientThread.call(Regs::all).entrySet()) {
                if (!f.test(e.getKey(), null)) continue;
                Path base = dir.resolve(e.getKey().getNamespace());
                String file = e.getKey().getPath() + ".json";
                if (registries) {
                    JsonObject out = new JsonObject();
                    out.addProperty("registry", e.getKey().toString());
                    out.addProperty("source", e.getValue().source());
                    JsonArray arr = entries(e.getValue().registry(), e.getValue().access());
                    out.add("entries", arr);
                    write(base.resolve("registries").resolve(file), out);
                    files++;
                    entries += arr.size();
                }
                if (tags) {
                    JsonObject t = tags(e.getValue().registry());
                    if (!t.isEmpty()) {
                        write(base.resolve("tags").resolve(file), t);
                        files++;
                    }
                }
            }
            summary.addProperty("registry_files", files);
            summary.addProperty("registry_entries", entries);
        }
        if (all || what.contains("recipes")) {
            try {
                RecipeSource src = ClientThread.call(() -> RecipeSource.of(Source.AUTO));
                JsonArray arr = new JsonArray();
                src.manager().getRecipes().stream().sorted(Comparator.comparing(RecipeHolder::id))
                        .forEach(h -> arr.add(RecipeJson.recipe(h, src.regs(), true)));
                write(dir.resolve("recipes.json"), arr);
                summary.addProperty("recipes", arr.size());
                summary.addProperty("recipe_source", src.source());
            } catch (Exception ex) {
                summary.addProperty("recipes_error", ex.getMessage());
            }
        }
        return ToolResult.json(summary);
    }

    private static <T> JsonArray entries(Registry<T> reg, RegistryAccess access) {
        JsonArray arr = new JsonArray();
        reg.holders().sorted(Comparator.comparing(h -> h.key().location()))
                .forEach(h -> arr.add(RegistryJson.entry(reg, h, access, true, true, true)));
        return arr;
    }

    private static <T> JsonObject tags(Registry<T> reg) {
        JsonObject tags = new JsonObject();
        reg.getTags().map(Pair::getSecond).sorted(Comparator.comparing(s -> s.key().location())).forEach(set ->
                tags.add("#" + set.key().location(), Json.strings(set.stream().map(Holder::getRegisteredName).sorted().toList())));
        return tags;
    }

    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private static void write(Path p, JsonElement json) throws Exception {
        Files.createDirectories(p.getParent());
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            PRETTY.toJson(json, w);
        }
    }
}
