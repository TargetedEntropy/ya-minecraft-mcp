import dev.kikugie.stonecutter.controller.task.StonecutterTaskMutexService

plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

val modId = sc.properties["mod.id"]
val node = project.name
val modVersion = sc.properties["mod.version"]
version = "$modVersion+${sc.current.version}"
group = sc.properties["mod.group"]
base.archivesName = "$modId-neoforge"

val javaVersion = if (sc.current.parsed >= "26.1") 25 else 21

neoForge {
    version = sc.properties["deps.neo_loader"]
    // Nodes other than the active one build from Stonecutter's generated copy, so point at the processed file.
    accessTransformers.from(sc.process(rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"), "build/processed-at.cfg"))

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            client()
            gameDirectory = rootProject.file("run/$node")
        }
        // Dev test client: auto-creates and joins a singleplayer world named "mcptest".
        register("testClient") {
            client()
            gameDirectory = rootProject.file("run-test/$node")
            systemProperty("yamcmcp.devAutoWorld", "mcptest")
            programArguments.addAll("--width", "854", "--height", "480")
        }
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        val props = mapOf(
            "mod_id" to modId,
            "mod_name" to sc.properties["mod.name"],
            "mod_version" to modVersion,
            "mod_license" to sc.properties["mod.license"],
            "minecraft_version_range" to sc.properties["mod.mc_compat"],
            "neo_version_range" to sc.properties["deps.neo_compat"],
        )
        inputs.properties(props)
        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
        exclude("fabric.mod.json", "*.ct", "*.mixins.json")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
        usesService(StonecutterTaskMutexService.create(gradle, name))
    }

    withType<Jar> {
        val suffix = "_$modId"
        from(rootProject.file("LICENSE")) { rename { it + suffix } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the jar and copies it to build/libs/<mod version>/ in the root project."
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}
