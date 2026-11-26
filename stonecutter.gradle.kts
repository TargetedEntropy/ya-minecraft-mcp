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
}

/** Builds every version and loader, then copies the jars to build/libs/<mod version>/. */
tasks.register("buildAll") {
    group = "build"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}
