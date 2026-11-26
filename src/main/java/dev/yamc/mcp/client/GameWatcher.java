package dev.yamc.mcp.client;

import dev.yamc.mcp.util.Mc;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import dev.yamc.mcp.util.Inventories;
import dev.yamc.mcp.util.Json;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Watches the client every tick and records notable changes into the {@link EventLog}. Driven by {@link ClientEvents}. */
final class GameWatcher {
    /**
     * Samples one value every {@code every} ticks and reports changes. The first sample after a
     * reset (and null samples) only establish the baseline.
     */
    private static final class Watch<T> {
        interface Change<T> {
            void changed(T before, T now, LocalPlayer p);
        }

        private final int every;
        private final Function<LocalPlayer, T> sample;
        private final Change<T> onChange;
        private T last;

        private Watch(int every, Function<LocalPlayer, T> sample, Change<T> onChange) {
            this.every = every;
            this.sample = sample;
            this.onChange = onChange;
        }

        static <T> Watch<T> of(int every, Function<LocalPlayer, T> sample, Change<T> onChange) {
            return new Watch<>(every, sample, onChange);
        }

        void tick(LocalPlayer p, int tick) {
            if (tick % every != 0) return;
            T now = sample.apply(p);
            T before = last;
            last = now;
            if (before != null && now != null && !before.equals(now)) onChange.changed(before, now, p);
        }

        void reset() {
            last = null;
        }
    }

    private final CatchTracker catches = new CatchTracker();

    private final List<Watch<?>> watches = List.of(
            Watch.of(1, LocalPlayer::getHealth, GameWatcher::health),
            Watch.of(1, LocalPlayer::isDeadOrDying, (was, dead, p) -> {
                if (dead) death(p);
            }),
            Watch.of(1, p -> Mc.id(p.level().dimension()).toString(), (from, to, p) -> emit(GameEvent.DIMENSION_CHANGE, "from", from, "to", to)),
            Watch.of(1, p -> p.experienceLevel, (from, to, p) -> {
                if (to > from) {
                    JsonObject d = new JsonObject();
                    d.addProperty("level", to);
                    EventLog.add(GameEvent.LEVEL_UP, d);
                }
            }),
            Watch.of(1, p -> Minecraft.getInstance().gameMode == null ? null : Minecraft.getInstance().gameMode.getPlayerMode(),
                    (from, to, p) -> emit(GameEvent.GAMEMODE_CHANGE, "from", from.getName(), "to", to.getName())),
            Watch.of(20, p -> p.level().getBiome(p.blockPosition()).getRegisteredName(), (from, to, p) -> emit(GameEvent.BIOME_CHANGE, "from", from, "to", to)),
            Watch.of(5, GameWatcher::effects, GameWatcher::effectsChanged),
            Watch.of(10, p -> Inventories.counts(p.getInventory(), true), GameWatcher::inventoryChanged));

    private int tick;
    private final Map<Long, Long> recentChat = new HashMap<>();

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Ticker.tick();
        if (p == null || mc.level == null) {
            resetWatches();
            return;
        }
        tick++;
        for (Watch<?> w : watches) w.tick(p, tick);
        catches.tick(p, mc.level);
    }

    private void resetWatches() {
        watches.forEach(Watch::reset);
        catches.reset();
    }

    private static void emit(GameEvent type, String k1, String v1, String k2, String v2) {
        JsonObject d = new JsonObject();
        d.addProperty(k1, v1);
        d.addProperty(k2, v2);
        EventLog.add(type, d);
    }

    private static void health(float before, float h, LocalPlayer p) {
        if (Math.abs(h - before) < 0.5f) return;
        JsonObject d = new JsonObject();
        d.addProperty("health", h);
        d.addProperty("delta", h - before);
        if (h < before) {
            DamageSource src = p.getLastDamageSource();
            if (src != null) {
                d.addProperty("damage_type", src.typeHolder().getRegisteredName());
                if (src.getEntity() != null) d.addProperty("attacker", src.getEntity().getName().getString());
            }
        }
        EventLog.add(h < before ? GameEvent.DAMAGE : GameEvent.HEAL, d);
    }

    private static void death(LocalPlayer p) {
        JsonObject d = new JsonObject();
        d.add("pos", Json.pos(p.blockPosition()));
        d.addProperty("dimension", Mc.id(p.level().dimension()).toString());
        EventLog.add(GameEvent.DEATH, d);
    }

    private static Set<Holder<MobEffect>> effects(LocalPlayer p) {
        Set<Holder<MobEffect>> now = new HashSet<>();
        for (MobEffectInstance i : p.getActiveEffects()) now.add(i.getEffect());
        return now;
    }

    private static void effectsChanged(Set<Holder<MobEffect>> before, Set<Holder<MobEffect>> now, LocalPlayer p) {
        for (Holder<MobEffect> h : now) if (!before.contains(h)) effectEvent(GameEvent.EFFECT_ADDED, h, p.getEffect(h));
        for (Holder<MobEffect> h : before) if (!now.contains(h)) effectEvent(GameEvent.EFFECT_REMOVED, h, null);
    }

    private static void effectEvent(GameEvent type, Holder<MobEffect> effect, MobEffectInstance inst) {
        JsonObject d = new JsonObject();
        d.addProperty("effect", effect.getRegisteredName());
        if (inst != null) {
            d.addProperty("amplifier", inst.getAmplifier());
            d.addProperty("duration_ticks", inst.getDuration());
        }
        EventLog.add(type, d);
    }

    private static void inventoryChanged(Map<Item, Integer> before, Map<Item, Integer> inv, LocalPlayer p) {
        Set<Item> keys = new HashSet<>(inv.keySet());
        keys.addAll(before.keySet());
        for (Item it : keys) {
            int delta = inv.getOrDefault(it, 0) - before.getOrDefault(it, 0);
            if (delta == 0) continue;
            JsonObject d = new JsonObject();
            d.addProperty("item", BuiltInRegistries.ITEM.getKey(it).toString());
            d.addProperty("delta", delta);
            d.addProperty("now", inv.getOrDefault(it, 0));
            EventLog.add(delta > 0 ? GameEvent.ITEM_GAINED : GameEvent.ITEM_LOST, d);
        }
    }

    void chat(Component message, @Nullable UUID sender, boolean system, boolean overlay) {
        String text = message.getString();
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        GameEvent type = GameEvent.CHAT;
        if (system) {
            type = overlay ? GameEvent.ACTIONBAR : GameEvent.SYSTEM_MESSAGE;
        } else if (sender != null && !Objects.equals(sender, net.minecraft.Util.NIL_UUID)) {
            d.addProperty("sender_uuid", sender.toString());
            var info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(sender);
            if (info != null) d.addProperty("sender", Mc.name(info.getProfile()));
        }
        if (type == GameEvent.ACTIONBAR) {
            long key = text.hashCode();
            long now = System.currentTimeMillis();
            Long prev = recentChat.put(key, now);
            if (prev != null && now - prev < 3000) return; // action bars repeat every tick; de-duplicate
            if (recentChat.size() > 64) recentChat.clear();
        }
        ChatHistory.add(type, d);
        EventLog.add(type, d);
    }

    void screenOpening(Screen screen) {
        JsonObject d = new JsonObject();
        d.addProperty("screen", screen.getClass().getSimpleName());
        d.addProperty("title", screen.getTitle().getString());
        EventLog.add(screen instanceof DeathScreen ? GameEvent.DEATH_SCREEN : GameEvent.SCREEN_OPEN, d);
    }

    void screenClosing(Screen screen) {
        JsonObject d = new JsonObject();
        d.addProperty("screen", screen.getClass().getSimpleName());
        EventLog.add(GameEvent.SCREEN_CLOSE, d);
    }

    void loggingIn() {
        JsonObject d = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        d.addProperty("singleplayer", mc.hasSingleplayerServer());
        if (mc.getCurrentServer() != null) d.addProperty("server", mc.getCurrentServer().ip);
        EventLog.add(GameEvent.JOIN_WORLD, d);
        resetWatches();
    }

    void loggingOut() {
        EventLog.add(GameEvent.LEAVE_WORLD, new JsonObject());
        Markers.clear();
    }
}
