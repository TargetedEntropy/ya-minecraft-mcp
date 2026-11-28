package dev.yamc.mcp.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.YamcMcp;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What Mixin knows: every config with its mixins, and what was applied to a class. Mixin's public API only lists
 * configs not yet processed, so the processed ones are read from its transformer by reflection (field names are stable
 * across Mixin 0.8.x, which both loaders ship); if that fails, the tool says so. What was applied to a loaded class is
 * read from the {@link MixinMerged} annotations Mixin leaves on every method it merges into it.
 */
public final class Mixins {
    private Mixins() {}

    /** A config and its mixins (class names), as read from the transformer. */
    private record Cfg(IMixinConfig config, List<IMixinInfo> mixins) {}

    /** The processed configs, or null if Mixin's internals could not be read. */
    private static @Nullable List<Cfg> configs() {
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            Object processor = field(transformer, "processor");
            List<Cfg> out = new ArrayList<>();
            for (String list : List.of("configs", "pendingConfigs")) {
                for (Object c : (Collection<?>) field(processor, list)) {
                    List<IMixinInfo> mixins = new ArrayList<>();
                    for (String f : List.of("mixins", "pendingMixins"))
                        for (Object m : (Collection<?>) field(c, f)) mixins.add((IMixinInfo) m);
                    out.add(new Cfg((IMixinConfig) c, mixins));
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final String UNREADABLE = "Mixin's config list could not be read in this environment.";

    public static JsonObject configs(@Nullable String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        JsonObject o = new JsonObject();
        List<Cfg> all = configs();
        if (all == null) {
            o.addProperty("note", UNREADABLE);
            return o;
        }
        List<Cfg> shown = all.stream().filter(c -> f.isEmpty() || c.config().getName().toLowerCase(Locale.ROOT).contains(f))
                .sorted(Comparator.comparing(c -> c.config().getName())).toList();
        JsonArray arr = new JsonArray();
        for (Cfg c : shown) {
            JsonObject x = new JsonObject();
            x.addProperty("config", c.config().getName());
            x.addProperty("package", c.config().getMixinPackage());
            x.addProperty("priority", c.config().getPriority());
            x.addProperty("mixin_count", c.mixins().size());
            if (!f.isEmpty()) { // filtered to a mod: list its mixins and their targets
                JsonArray mixins = new JsonArray();
                for (IMixinInfo m : c.mixins()) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("mixin", m.getClassName());
                    JsonArray targets = new JsonArray();
                    m.getTargetClasses().forEach(t -> targets.add(t.replace('/', '.')));
                    mo.add("targets", targets);
                    mixins.add(mo);
                }
                x.add("mixins", mixins);
            }
            arr.add(x);
        }
        o.addProperty("count", arr.size());
        o.add("configs", arr);
        return o;
    }

    public static JsonObject target(String className) {
        String dotted = className.replace('/', '.'), internal = dotted.replace('.', '/');
        JsonObject o = new JsonObject();
        o.addProperty("target", dotted);
        boolean loaded = ClassInfo.fromCache(internal) != null;
        o.addProperty("loaded", loaded);

        JsonArray declared = new JsonArray();
        List<Cfg> all = configs();
        if (all == null) o.addProperty("declared_note", UNREADABLE);
        else for (Cfg c : all) for (IMixinInfo m : c.mixins()) {
            if (m.getTargetClasses().stream().noneMatch(t -> t.replace('/', '.').equals(dotted))) continue;
            JsonObject x = new JsonObject();
            x.addProperty("mixin", m.getClassName());
            x.addProperty("config", c.config().getName());
            declared.add(x);
        }
        o.add("declared_by", declared);

        // Mixins with merged methods; a mixin that only adds fields or interfaces leaves no annotation.
        Map<String, Integer> merged = new TreeMap<>();
        if (loaded) {
            try {
                Class<?> cls = Class.forName(dotted, false, YamcMcp.class.getClassLoader());
                for (Method m : cls.getDeclaredMethods()) {
                    MixinMerged mm = m.getAnnotation(MixinMerged.class);
                    if (mm != null) merged.merge(mm.mixin(), 1, Integer::sum);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                o.addProperty("merged_note", "Could not inspect the class: " + e);
            }
        }
        JsonArray applied = new JsonArray();
        merged.forEach((mixin, n) -> {
            JsonObject x = new JsonObject();
            x.addProperty("mixin", mixin);
            x.addProperty("merged_methods", n);
            applied.add(x);
        });
        o.add("applied", applied);
        if (!loaded) o.addProperty("note", declared.isEmpty()
                ? "Mixin has no record of this class: no mixin targets it, or the name is wrong (use the runtime name)."
                : "Mixin has not transformed this class yet: it is loaded on first use.");
        else {
            List<String> missing = new ArrayList<>();
            declared.forEach(d -> {
                String m = d.getAsJsonObject().get("mixin").getAsString();
                if (!merged.containsKey(m)) missing.add(m);
            });
            if (!missing.isEmpty()) o.addProperty("note", "Declared but no merged methods found for " + missing
                    + ": either they add only fields/interfaces, or they failed to apply (check logs with logger=mixin).");
        }
        return o;
    }
}
