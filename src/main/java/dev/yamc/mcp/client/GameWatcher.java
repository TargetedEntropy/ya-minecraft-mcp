package dev.yamc.mcp.client;

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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import dev.yamc.mcp.util.Inventories;
import dev.yamc.mcp.util.Json;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Watches the client every tick and records notable changes into the {@link EventLog}. */
public final class GameWatcher {
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
            Watch.of(1, p -> p.level().dimension().location().toString(), (from, to, p) -> emit(GameEvent.DIMENSION_CHANGE, "from", from, "to", to)),
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

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post e) {
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
        d.addProperty("dimension", p.level().dimension().location().toString());
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

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        String text = e.getMessage().getString();
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        GameEvent type = GameEvent.CHAT;
        if (e instanceof ClientChatReceivedEvent.System sys) {
            type = sys.isOverlay() ? GameEvent.ACTIONBAR : GameEvent.SYSTEM_MESSAGE;
        } else if (e.getSender() != null && !Objects.equals(e.getSender(), net.minecraft.Util.NIL_UUID)) {
            d.addProperty("sender_uuid", e.getSender().toString());
            var info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(e.getSender());
            if (info != null) d.addProperty("sender", info.getProfile().getName());
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

    @SubscribeEvent
    public void onScreenOpen(ScreenEvent.Opening e) {
        JsonObject d = new JsonObject();
        d.addProperty("screen", e.getNewScreen().getClass().getSimpleName());
        d.addProperty("title", e.getNewScreen().getTitle().getString());
        EventLog.add(e.getNewScreen() instanceof DeathScreen ? GameEvent.DEATH_SCREEN : GameEvent.SCREEN_OPEN, d);
    }

    @SubscribeEvent
    public void onScreenClose(ScreenEvent.Closing e) {
        JsonObject d = new JsonObject();
        d.addProperty("screen", e.getScreen().getClass().getSimpleName());
        EventLog.add(GameEvent.SCREEN_CLOSE, d);
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        JsonObject d = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        d.addProperty("singleplayer", mc.hasSingleplayerServer());
        if (mc.getCurrentServer() != null) d.addProperty("server", mc.getCurrentServer().ip);
        EventLog.add(GameEvent.JOIN_WORLD, d);
        resetWatches();
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        EventLog.add(GameEvent.LEAVE_WORLD, new JsonObject());
        Markers.clear();
    }
}
