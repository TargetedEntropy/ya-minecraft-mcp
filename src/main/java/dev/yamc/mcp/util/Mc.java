package dev.yamc.mcp.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Small Minecraft API differences between versions, behind one stable name each. Version conditions
 * for anything outside recipes ({@link dev.yamc.mcp.recipe.Recipes}) and rendering belong here, not in the tools.
 */
public final class Mc {
    private Mc() {}

    // ------------------------------------------------------------------ registries

    /** The id of a resource key (ResourceKey.location() was renamed identifier() in 1.21.11). */
    public static ResourceLocation id(ResourceKey<?> key) {
        //? if >=1.21.11 {
        /*return key.identifier();
        *///?} else
        return key.location();
    }

    public static <T> Optional<Holder.Reference<T>> holder(Registry<T> reg, ResourceLocation id) {
        //? if >=1.21.2 {
        /*return reg.get(id);
        *///?} else
        return reg.getHolder(id);
    }

    /** By numeric (network) id. */
    public static <T> Optional<Holder.Reference<T>> holder(Registry<T> reg, int id) {
        //? if >=1.21.2 {
        /*return reg.get(id);
        *///?} else
        return reg.getHolder(id);
    }

    @Nullable
    public static <T> T value(Registry<T> reg, ResourceLocation id) {
        //? if >=1.21.2 {
        /*return reg.getValue(id);
        *///?} else
        return reg.get(id);
    }

    public static <T> Optional<HolderSet.Named<T>> tag(Registry<T> reg, TagKey<T> tag) {
        //? if >=1.21.2 {
        /*return reg.get(tag);
        *///?} else
        return reg.getTag(tag);
    }

    public static <T> Stream<HolderSet.Named<T>> tags(Registry<T> reg) {
        //? if >=1.21.2 {
        /*return reg.getTags();
        *///?} else
        return reg.getTags().map(com.mojang.datafixers.util.Pair::getSecond);
    }

    public static <T> Stream<Holder.Reference<T>> holders(Registry<T> reg) {
        //? if >=1.21.2 {
        /*return reg.listElements();
        *///?} else
        return reg.holders();
    }

    public static long tagCount(Registry<?> reg) {
        //? if >=1.21.2 {
        /*return reg.getTags().count();
        *///?} else
        return reg.getTagNames().count();
    }

    public static <T> Optional<Registry<T>> registry(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
        //? if >=1.21.2 {
        /*return access.lookup(key);
        *///?} else
        return access.registry(key);
    }

    public static <T> Registry<T> registryOrThrow(RegistryAccess access, ResourceKey<? extends Registry<? extends T>> key) {
        //? if >=1.21.2 {
        /*return access.lookupOrThrow(key);
        *///?} else
        return access.registryOrThrow(key);
    }

    /** The static registry with this id, or null. */
    @Nullable
    public static Registry<?> staticRegistry(ResourceLocation id) {
        return value(net.minecraft.core.registries.BuiltInRegistries.REGISTRY, id);
    }

    /**
     * The integrated server's richest registry access. Before 1.21.2 the reloadable registries (loot tables,
     * predicates, item modifiers) are a full RegistryAccess; later they are separate from it.
     */
    public static RegistryAccess serverRegistries(MinecraftServer s) {
        //? if >=1.21.2 {
        /*return s.registryAccess();
        *///?} else {
        try {
            return s.reloadableRegistries().get();
        } catch (Throwable t) {
            return s.registryAccess();
        }
        //?}
    }

    /** A loot table of the integrated server, or null if there is none with this id. */
    @Nullable
    public static net.minecraft.world.level.storage.loot.LootTable lootTable(MinecraftServer s, ResourceLocation id) {
        var key = ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, id);
        //? if >=1.21.6 {
        /*return s.reloadableRegistries().lookup().lookup(net.minecraft.core.registries.Registries.LOOT_TABLE)
                .flatMap(l -> l.get(key)).map(Holder::value).orElse(null);
        *///?} elif >=1.21.2 {
        /*return s.reloadableRegistries().getKeys(net.minecraft.core.registries.Registries.LOOT_TABLE).contains(id) ? s.reloadableRegistries().getLootTable(key) : null;
        *///?} else
        return registryOrThrow(serverRegistries(s), net.minecraft.core.registries.Registries.LOOT_TABLE).get(key);
    }

    // ------------------------------------------------------------------ game objects

    public static Component itemName(Item i) {
        //? if >=1.21.2 {
        /*return i.getName();
        *///?} else
        return i.getDescription();
    }

    public static ItemStack craftingRemainder(Item i) {
        //? if >=1.21.2 {
        /*return i.getCraftingRemainder();
        *///?} else
        return i.hasCraftingRemainingItem() ? new ItemStack(i.getCraftingRemainingItem()) : ItemStack.EMPTY;
    }

    @Nullable
    public static ResourceLocation lootTable(Block b) {
        //? if >=1.21.2 {
        /*return b.getLootTable().map(Mc::id).orElse(null);
        *///?} else
        return b.getLootTable().location();
    }

    @Nullable
    public static ResourceLocation lootTable(EntityType<?> e) {
        //? if >=1.21.2 {
        /*return e.getDefaultLootTable().map(Mc::id).orElse(null);
        *///?} else
        return e.getDefaultLootTable().location();
    }

    public static ResourceLocation soundId(SoundEvent e) {
        //? if >=1.21.2 {
        /*return e.location();
        *///?} else
        return e.getLocation();
    }

    /** Lowest buildable Y. */
    public static int minY(LevelHeightAccessor l) {
        //? if >=1.21.2 {
        /*return l.getMinY();
        *///?} else
        return l.getMinBuildHeight();
    }

    /** One above the highest buildable Y (exclusive bound). */
    public static int maxBuildHeight(LevelHeightAccessor l) {
        //? if >=1.21.2 {
        /*return l.getMaxY() + 1;
        *///?} else
        return l.getMaxBuildHeight();
    }

    public static Direction nearestDirection(double x, double y, double z) {
        //? if >=1.21.2 {
        /*return Direction.getApproximateNearest(x, y, z);
        *///?} else
        return Direction.getNearest(x, y, z);
    }

    // ------------------------------------------------------------------ interaction

    /** Whether the client should swing the arm for this result. */
    public static boolean swings(InteractionResult r) {
        //? if >=1.21.2 {
        /*return r instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT;
        *///?} else
        return r.shouldSwing();
    }

    /** A short lower-case name for the result: success, consume, pass, fail, ... */
    public static String name(InteractionResult r) {
        //? if >=1.21.2 {
        /*if (r instanceof InteractionResult.Success s) return s.swingSource() == InteractionResult.SwingSource.SERVER ? "success_server" : s.swingSource() == InteractionResult.SwingSource.NONE ? "consume" : "success";
        return r.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        *///?} else
        return r.name().toLowerCase(Locale.ROOT);
    }

    public static boolean isDay(net.minecraft.world.level.Level l) {
        //? if >=1.21.5 {
        /*return l.isBrightOutside();
        *///?} else
        return l.isDay();
    }

    public static String name(com.mojang.authlib.GameProfile g) {
        //? if >=1.21.9 {
        /*return g.name();
        *///?} else
        return g.getName();
    }

    public static java.util.UUID id(com.mojang.authlib.GameProfile g) {
        //? if >=1.21.9 {
        /*return g.id();
        *///?} else
        return g.getId();
    }

    // ------------------------------------------------------------------ client

    public static net.minecraft.network.chat.HoverEvent showText(Component text) {
        //? if >=1.21.5 {
        /*return new net.minecraft.network.chat.HoverEvent.ShowText(text);
        *///?} else
        return new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, text);
    }

    public static net.minecraft.network.chat.ClickEvent copyToClipboard(String text) {
        //? if >=1.21.5 {
        /*return new net.minecraft.network.chat.ClickEvent.CopyToClipboard(text);
        *///?} else
        return new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.COPY_TO_CLIPBOARD, text);
    }

    /** Captures the main framebuffer. Since 1.21.5 the image arrives later (GPU readback), possibly after a frame. */
    public static void screenshot(Minecraft mc, java.util.function.Consumer<NativeImage> done) {
        //? if >=1.21.5 {
        /*net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), done);
        *///?} else
        done.accept(net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget()));
    }

    public static void toast(Minecraft mc, Component title, Component text) {
        //? if >=1.21.2 {
        /*net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToastManager(),
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text);
        *///?} else
        net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.getToasts(), net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text);
    }

    /** PNG bytes of an image. */
    public static byte[] png(NativeImage img) throws java.io.IOException {
        //? if >=1.21.2 {
        /*Path tmp = Files.createTempFile("yamcmcp", ".png");
        try {
            img.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        *///?} else
        return img.asByteArray();
    }
}
