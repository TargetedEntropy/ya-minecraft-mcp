package dev.yamc.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yamc.mcp.client.Ticker;
import dev.yamc.mcp.debug.Mixins;
import dev.yamc.mcp.debug.Perf;
import dev.yamc.mcp.mcp.Args;
import dev.yamc.mcp.mcp.Schema;
import dev.yamc.mcp.mcp.Tool;
import dev.yamc.mcp.mcp.ToolException;
import dev.yamc.mcp.mcp.ToolRegistry;
import dev.yamc.mcp.mcp.ToolResult;
import dev.yamc.mcp.util.Mc;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Mod development on the client: key bindings, the open screen's widgets, performance and mixins. */
final class ClientDevTools {
    private ClientDevTools() {}

    private static final int MAX_WIDGETS = 300;

    static void register(ToolRegistry r) {
        r.add(Tool.builder("keybinds").title("Key bindings")
                .desc("Every key binding (vanilla and mods): its name (translation key, e.g. key.mymod.open), category, bound "
                        + "key and display name. Filter with query. Press one with key_press.")
                .schema(Schema.of().str("query", "Case-insensitive substring of the name, display name, category or key."))
                .handler(ClientDevTools::keybinds));

        r.add(Gates.action("key_press").title("Press a key binding")
                .desc("Presses a key binding by name (from keybinds) as if the player pressed its key: held down for the given "
                        + "ticks and counted as one click, so mods that poll consumeClick() or isDown() react. Works without the "
                        + "key being bound. The game must be in a world with no screen open for most bindings to act.")
                .schema(Schema.of().reqStr("name", "The binding's name, e.g. key.jump or key.mymod.open.")
                        .integer("ticks", "How long to hold it, in ticks (default 1, max 200)."))
                .anyThread()
                .handler(ClientDevTools::keyPress));

        r.add(Tool.builder("screen_widgets").title("Screen widgets")
                .desc("The open screen (any mod GUI): its class, title and size, and every widget in it: class, position and size "
                        + "(GUI coordinates), label, whether active/visible/focused, and text field contents. Each widget has an "
                        + "index for widget_click. Container slots are in open_container.")
                .handler(a -> ToolResult.json(screenWidgets())));

        r.add(Gates.action("widget_click").title("Click / type in a widget")
                .desc("Clicks a widget of the open screen by its index from screen_widgets (a left click at its center, through "
                        + "the screen like a real click), or with text, replaces a text field's contents.")
                .schema(Schema.of().reqStr("index", "Widget index from screen_widgets, e.g. 3 or 2.1.")
                        .str("text", "Set this text in a text field instead of clicking."))
                .handler(ClientDevTools::widgetClick));

        r.add(Tool.builder("perf").title("Performance")
                .desc("Client and (singleplayer) server performance: FPS, memory, server tick time (average, worst of the last "
                        + "100 ticks), tick rate, loaded chunks, and entity and ticking block entity counts by type and by mod, "
                        + "busiest first, to find what a mod adds that costs time.")
                .schema(Schema.of().integer("top", "How many types to list per count (default 15, max 100)."))
                .anyThread()
                .handler(a -> ToolResult.json(Perf.snapshot(a.integer("top", 15, 1, 100)))));

        r.add(Tool.builder("mixins").title("Mixins")
                .desc("Mixin configs loaded in the game (every mod's), with their package, priority and how many classes they "
                        + "target; filter with config. With target (a class name), the mixins actually applied to that class "
                        + "and the configs that declare it as a target, to find out why a mixin did not apply. Classes are only "
                        + "transformed when first loaded.")
                .schema(Schema.of().str("config", "Case-insensitive substring of the config name, e.g. a mod id.")
                        .str("target", "Fully qualified class, e.g. net.minecraft.client.Minecraft."))
                .anyThread()
                .handler(a -> ToolResult.json(a.has("target") ? Mixins.target(a.reqStr("target").trim()) : Mixins.configs(a.str("config", null)))));
    }

    // ------------------------------------------------------------------ key bindings

    private static ToolResult keybinds(Args a) {
        String q = a.str("query", "").trim().toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        for (KeyMapping k : Ctx.mc().options.keyMappings) {
            String display = net.minecraft.network.chat.Component.translatable(k.getName()).getString();
            String category = Mc.keyCategory(k);
            String key = k.getTranslatedKeyMessage().getString();
            if (!q.isEmpty() && Arrays.stream(new String[]{k.getName(), display, category, key, k.saveString()})
                    .noneMatch(s -> s.toLowerCase(Locale.ROOT).contains(q))) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", k.getName());
            o.addProperty("display_name", display);
            o.addProperty("category", category);
            o.addProperty("key", k.isUnbound() ? "unbound" : key);
            o.addProperty("key_id", k.saveString());
            if (k.isDown()) o.addProperty("down", true);
            out.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", out.size());
        o.add("keybinds", out);
        return ToolResult.json(o);
    }

    private static ToolResult keyPress(Args a) throws Exception {
        String name = a.reqStr("name").trim();
        int ticks = a.integer("ticks", 1, 1, 200);
        return Ticker.run(() -> {
            KeyMapping k = Arrays.stream(Ctx.mc().options.keyMappings).filter(m -> m.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new ToolException("No key binding '" + name + "'. List them with keybinds."));
            k.clickCount++;
            k.setDown(true);
            return new Ticker.Job<ToolResult>() {
                int left = ticks;

                @Override
                public ToolResult step() {
                    if (--left > 0) {
                        k.setDown(true); // the game resets it from the physical key each tick
                        return null;
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("pressed", name);
                    o.addProperty("ticks", ticks);
                    o.addProperty("unconsumed_clicks", k.clickCount);
                    return ToolResult.json(o);
                }

                @Override
                public void end(boolean completed) {
                    k.setDown(false);
                }
            };
        }, ticks * 50L + 15_000);
    }

    // ------------------------------------------------------------------ screen widgets

    private record Found(String index, GuiEventListener widget, int depth) {}

    private static Screen screen() {
        Screen s = Mc.screen(Ctx.mc());
        if (s == null) throw new ToolException("No screen is open.");
        return s;
    }

    private static List<Found> widgets(Screen s) {
        List<Found> out = new ArrayList<>();
        collect(s.children(), "", 0, out);
        return out;
    }

    private static void collect(List<? extends GuiEventListener> children, String prefix, int depth, List<Found> out) {
        for (int i = 0; i < children.size() && out.size() < MAX_WIDGETS; i++) {
            GuiEventListener w = children.get(i);
            String index = prefix + i;
            out.add(new Found(index, w, depth));
            if (w instanceof ContainerEventHandler c && depth < 4) collect(c.children(), index + ".", depth + 1, out);
        }
    }

    private static JsonObject screenWidgets() {
        Screen s = screen();
        JsonObject o = new JsonObject();
        o.addProperty("screen", s.getClass().getName());
        o.addProperty("title", s.getTitle().getString());
        o.addProperty("width", s.width);
        o.addProperty("height", s.height);
        JsonArray arr = new JsonArray();
        for (Found f : widgets(s)) {
            JsonObject w = new JsonObject();
            w.addProperty("index", f.index());
            w.addProperty("class", f.widget().getClass().getName());
            if (f.widget() instanceof AbstractWidget aw) {
                w.addProperty("x", aw.getX());
                w.addProperty("y", aw.getY());
                w.addProperty("width", aw.getWidth());
                w.addProperty("height", aw.getHeight());
                String label = aw.getMessage().getString();
                if (!label.isEmpty()) w.addProperty("label", label);
                w.addProperty("active", aw.active);
                w.addProperty("visible", aw.visible);
            }
            if (f.widget().isFocused()) w.addProperty("focused", true);
            if (f.widget() instanceof EditBox e) w.addProperty("text", e.getValue());
            arr.add(w);
        }
        o.addProperty("count", arr.size());
        o.add("widgets", arr);
        return o;
    }

    private static ToolResult widgetClick(Args a) {
        Screen s = screen();
        String index = a.reqStr("index").trim();
        Found f = widgets(s).stream().filter(x -> x.index().equals(index)).findFirst()
                .orElseThrow(() -> new ToolException("No widget " + index + ". List them with screen_widgets."));
        JsonObject o = new JsonObject();
        o.addProperty("index", index);
        if (a.has("text")) {
            if (!(f.widget() instanceof EditBox e)) throw new ToolException("Widget " + index + " is not a text field.");
            e.setValue(a.str("text", ""));
            o.addProperty("text", e.getValue());
        } else {
            if (!(f.widget() instanceof AbstractWidget w)) throw new ToolException("Widget " + index + " has no position to click.");
            if (!w.visible || !w.active) o.addProperty("note", "The widget is " + (!w.visible ? "hidden" : "inactive") + "; clicked anyway.");
            double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
            o.addProperty("handled", Mc.click(s, x, y));
            o.addProperty("x", x);
            o.addProperty("y", y);
        }
        Screen now = Mc.screen(Minecraft.getInstance());
        o.addProperty("screen_after", now == null ? "none" : now.getClass().getName());
        return ToolResult.json(o);
    }
}
