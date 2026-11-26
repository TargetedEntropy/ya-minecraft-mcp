plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1-neoforge"

stonecutter parameters {
    val (_, loader) = current.project.split('-', limit = 2)

    // Version- and loader-specific values from stonecutter.properties.toml.
    properties {
        tags(current.version, loader)
    }

    // `//? if fabric {` / `//? if neoforge {`
    constants {
        match(loader, "fabric", "neoforge")
    }

    // Mojang renames applied to the whole source per version (reversible when switching the active version).
    replacements {
        string(current.parsed >= "1.21.11") {
            // Type-name contexts only: replacements also run in reverse on older versions, and a bare
            // "Identifier" -> "ResourceLocation" would rewrite unrelated names like getIdentifier().
            for (c in listOf(" ", ",", ".", ";", ">", ")", "::")) replace("ResourceLocation$c", "Identifier$c")
            replace("net.minecraft.Util", "net.minecraft.util.Util")
            replace("net.minecraft.client.renderer.RenderType;", "net.minecraft.client.renderer.rendertype.RenderType;")
            replace("net.minecraft.world.level.GameRules", "net.minecraft.world.level.gamerules.GameRules")
            replace("net.minecraft.world.entity.animal.WaterAnimal", "net.minecraft.world.entity.animal.fish.WaterAnimal")
            replace("net.minecraft.world.entity.npc.AbstractVillager", "net.minecraft.world.entity.npc.villager.AbstractVillager")
        }
        // 26.1+ ships unobfuscated: the class tweaker names official (= Mojang) names directly.
        string(current.parsed >= "26.1") {
            replace("classTweaker v2 named", "classTweaker v2 official")
        }
    }
}

/** Builds every version and loader, then copies the jars to build/libs/<mod version>/. */
tasks.register("buildAll") {
    group = "build"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
