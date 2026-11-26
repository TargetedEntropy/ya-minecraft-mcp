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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Watches the client every tick and records notable changes into the {@link EventLog}. */
public final class GameWatcher {
    private float lastHealth = -1;
    private String lastDim;
    private String lastBiome;
    private int lastLevel = -1;
    private GameType lastMode;
    private Set<Holder<MobEffect>> lastEffects = new HashSet<>();
    private Map<Item, Integer> lastInv;
    private boolean wasDead;
    private int tick;
    private final Map<Long, Long> recentChat = new HashMap<>();

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Ticker.tick();
        if (p == null || mc.level == null) {
            lastHealth = -1;
            lastInv = null;
            return;
        }
        tick++;
        float h = p.getHealth();
        if (lastHealth >= 0 && Math.abs(h - lastHealth) >= 0.5f) {
            JsonObject d = new JsonObject();
            d.addProperty("health", h);
            d.addProperty("delta", h - lastHealth);
            if (h < lastHealth) {
                DamageSource src = p.getLastDamageSource();
                if (src != null) {
                    d.addProperty("damage_type", src.typeHolder().getRegisteredName());
                    if (src.getEntity() != null) d.addProperty("attacker", src.getEntity().getName().getString());
                }
            }
            EventLog.add(h < lastHealth ? "damage" : "heal", d);
        }
        lastHealth = h;
        boolean dead = p.isDeadOrDying();
        if (dead && !wasDead) {
            JsonObject d = new JsonObject();
            d.add("pos", dev.yamc.mcp.util.Json.pos(p.blockPosition()));
            d.addProperty("dimension", p.level().dimension().location().toString());
            EventLog.add("death", d);
        }
        wasDead = dead;

        String dim = p.level().dimension().location().toString();
        if (!dim.equals(lastDim)) {
            if (lastDim != null) {
                JsonObject d = new JsonObject();
                d.addProperty("from", lastDim);
                d.addProperty("to", dim);
                EventLog.add("dimension_change", d);
            }
            lastDim = dim;
        }
        if (p.experienceLevel != lastLevel) {
            if (lastLevel >= 0 && p.experienceLevel > lastLevel) {
                JsonObject d = new JsonObject();
                d.addProperty("level", p.experienceLevel);
                EventLog.add("level_up", d);
            }
            lastLevel = p.experienceLevel;
        }
        GameType mode = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
        if (mode != lastMode) {
            if (lastMode != null && mode != null) {
                JsonObject d = new JsonObject();
                d.addProperty("from", lastMode.getName());
                d.addProperty("to", mode.getName());
                EventLog.add("gamemode_change", d);
            }
            lastMode = mode;
        }
        if (tick % 20 == 0) {
            String biome = p.level().getBiome(p.blockPosition()).getRegisteredName();
            if (!biome.equals(lastBiome)) {
                if (lastBiome != null) {
                    JsonObject d = new JsonObject();
                    d.addProperty("from", lastBiome);
                    d.addProperty("to", biome);
                    EventLog.add("biome_change", d);
                }
                lastBiome = biome;
            }
        }
        if (tick % 5 == 0) {
            Set<Holder<MobEffect>> now = new HashSet<>();
            for (MobEffectInstance i : p.getActiveEffects()) now.add(i.getEffect());
            for (Holder<MobEffect> h2 : now) if (!lastEffects.contains(h2)) effectEvent("effect_added", h2, p.getEffect(h2));
            for (Holder<MobEffect> h2 : lastEffects) if (!now.contains(h2)) effectEvent("effect_removed", h2, null);
            lastEffects = now;
        }
        if (tick % 10 == 0) {
            Map<Item, Integer> inv = new HashMap<>();
            for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) inv.merge(s.getItem(), s.getCount(), Integer::sum);
            for (ItemStack s : p.getInventory().armor) if (!s.isEmpty()) inv.merge(s.getItem(), s.getCount(), Integer::sum);
            for (ItemStack s : p.getInventory().offhand) if (!s.isEmpty()) inv.merge(s.getItem(), s.getCount(), Integer::sum);
            if (lastInv != null) {
                Set<Item> keys = new HashSet<>(inv.keySet());
                keys.addAll(lastInv.keySet());
                for (Item it : keys) {
                    int delta = inv.getOrDefault(it, 0) - lastInv.getOrDefault(it, 0);
                    if (delta == 0) continue;
                    JsonObject d = new JsonObject();
                    d.addProperty("item", BuiltInRegistries.ITEM.getKey(it).toString());
                    d.addProperty("delta", delta);
                    d.addProperty("now", inv.getOrDefault(it, 0));
                    EventLog.add(delta > 0 ? "item_gained" : "item_lost", d);
                }
            }
            lastInv = inv;
        }
    }

    private static void effectEvent(String type, Holder<MobEffect> effect, MobEffectInstance inst) {
        JsonObject d = new JsonObject();
        d.addProperty("effect", effect.getRegisteredName());
        if (inst != null) {
            d.addProperty("amplifier", inst.getAmplifier());
            d.addProperty("duration_ticks", inst.getDuration());
        }
        EventLog.add(type, d);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        String text = e.getMessage().getString();
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        String type = "chat";
        if (e instanceof ClientChatReceivedEvent.System sys) {
            type = sys.isOverlay() ? "actionbar" : "system_message";
        } else if (e.getSender() != null && !Objects.equals(e.getSender(), net.minecraft.Util.NIL_UUID)) {
            d.addProperty("sender_uuid", e.getSender().toString());
            var info = Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getPlayerInfo(e.getSender());
            if (info != null) d.addProperty("sender", info.getProfile().getName());
        }
        if (type.equals("actionbar")) {
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
        EventLog.add(e.getNewScreen() instanceof DeathScreen ? "death_screen" : "screen_open", d);
    }

    @SubscribeEvent
    public void onScreenClose(ScreenEvent.Closing e) {
        JsonObject d = new JsonObject();
        d.addProperty("screen", e.getScreen().getClass().getSimpleName());
        EventLog.add("screen_close", d);
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        JsonObject d = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        d.addProperty("singleplayer", mc.hasSingleplayerServer());
        if (mc.getCurrentServer() != null) d.addProperty("server", mc.getCurrentServer().ip);
        EventLog.add("join_world", d);
        lastDim = null;
        lastBiome = null;
        lastLevel = -1;
        lastMode = null;
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        EventLog.add("leave_world", new JsonObject());
        Markers.clear();
    }
}
