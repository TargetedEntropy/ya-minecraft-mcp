package dev.yamc.mcp.tools;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.ClientThread;
import dev.yamc.mcp.util.IdFilter;
import dev.yamc.mcp.util.Json;
import dev.yamc.mcp.util.Names;
import dev.yamc.mcp.util.Regs;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ToolResult export(Args a) throws Exception {
        List<String> what = a.strList("what");
        boolean all = what.isEmpty();
        IdFilter f = IdFilter.from(a);
        Path dir = FMLPaths.GAMEDIR.get().resolve("yamcmcp-export").resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.createDirectories(dir);
        var gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        JsonObject summary = new JsonObject();
        summary.addProperty("directory", dir.toAbsolutePath().toString());
        Map<ResourceLocation, Regs.Resolved> regs = ClientThread.call(Regs::all);
        if (all || what.contains("registries") || what.contains("tags")) {
            int files = 0, entries = 0;
            for (var e : regs.entrySet()) {
                if (!f.test(e.getKey(), null)) continue;
                Registry reg = e.getValue().registry();
                Codec codec = Regs.elementCodec(reg.key());
                Path base = dir.resolve(e.getKey().getNamespace());
                if (all || what.contains("registries")) {
                    JsonObject out = new JsonObject();
                    out.addProperty("registry", e.getKey().toString());
                    out.addProperty("source", e.getValue().source());
                    JsonArray arr = new JsonArray();
                    List<Holder.Reference<?>> hs = ((java.util.stream.Stream<Holder.Reference<?>>) reg.holders())
                            .sorted(Comparator.comparing(h -> h.key().location())).toList();
                    for (Holder.Reference<?> h : hs) {
                        JsonObject x = new JsonObject();
                        x.addProperty("id", h.key().location().toString());
                        String n = Names.of(reg.key(), h.key().location(), h.value());
                        if (n != null) x.addProperty("name", n);
                        x.add("tags", Json.strings(h.tags().map(t -> "#" + t.location()).sorted().toList()));
                        if (codec != null) x.add("data", Json.encode(codec, h.value(), e.getValue().access()));
                        arr.add(x);
                        entries++;
                    }
                    out.add("entries", arr);
                    write(gson, base.resolve("registries").resolve(e.getKey().getPath() + ".json"), out);
                    files++;
                }
                if (all || what.contains("tags")) {
                    JsonObject tags = new JsonObject();
                    ((java.util.stream.Stream<com.mojang.datafixers.util.Pair<TagKey<?>, net.minecraft.core.HolderSet.Named<?>>>) reg.getTags())
                            .sorted(Comparator.comparing(p -> p.getFirst().location()))
                            .forEach(p -> {
                                JsonArray m = new JsonArray();
                                p.getSecond().stream().map(h -> ((Holder<?>) h).getRegisteredName()).sorted().forEach(m::add);
                                tags.add("#" + p.getFirst().location(), m);
                            });
                    if (!tags.isEmpty()) {
                        write(gson, base.resolve("tags").resolve(e.getKey().getPath() + ".json"), tags);
                        files++;
                    }
                }
            }
            summary.addProperty("registry_files", files);
            summary.addProperty("registry_entries", entries);
        }
        if (all || what.contains("recipes")) {
            try {
                RecipeTools.Src src = ClientThread.call(() -> RecipeTools.src(new Args(null)));
                JsonArray arr = new JsonArray();
                List<RecipeHolder<?>> list = src.manager().getRecipes().stream().sorted(Comparator.comparing(RecipeHolder::id)).toList();
                for (RecipeHolder<?> h : list) arr.add(RecipeTools.recipeJson(h, src.regs(), true));
                write(gson, dir.resolve("recipes.json"), arr);
                summary.addProperty("recipes", arr.size());
                summary.addProperty("recipe_source", src.source());
            } catch (Exception ex) {
                summary.addProperty("recipes_error", ex.getMessage());
            }
        }
        return ToolResult.json(summary);
    }

    private static void write(com.google.gson.Gson gson, Path p, Object json) throws Exception {
        Files.createDirectories(p.getParent());
        try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            gson.toJson((com.google.gson.JsonElement) json, w);
        }
    }
}
