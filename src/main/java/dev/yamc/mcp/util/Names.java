package dev.yamc.mcp.util;

import dev.yamc.mcp.util.Mc;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;

/** Best-effort localized display names for registry entries. */
public final class Names {
    private Names() {}

    public static String of(ResourceKey<? extends Registry<?>> registry, ResourceLocation id, Object value) {
        try {
            if (value instanceof Block b) return b.getName().getString();
            if (value instanceof Item i) return Mc.itemName(i).getString();
            if (value instanceof EntityType<?> e) return e.getDescription().getString();
            if (value instanceof MobEffect m) return m.getDisplayName().getString();
            if (value instanceof Enchantment en) return en.description().getString();
            if (value instanceof Attribute a) return Component.translatable(a.getDescriptionId()).getString();
            if (value instanceof DamageType d) return translate("death.attack." + d.msgId());
            String path = Mc.id(registry).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            String n = translate(Util.makeDescriptionId(last, id));
            if (n == null && last.endsWith("s")) n = translate(Util.makeDescriptionId(last.substring(0, last.length() - 1), id));
            return n;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String translate(String key) {
        Language lang = Language.getInstance();
        return lang.has(key) ? lang.getOrDefault(key) : null;
    }
}
