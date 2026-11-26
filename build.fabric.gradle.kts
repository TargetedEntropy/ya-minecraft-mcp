plugins {
    id("dev.kikugie.loom-back-compat")
}

val modId = sc.properties["mod.id"]
val modVersion = sc.properties["mod.version"]
version = "$modVersion+${sc.current.version}"
base.archivesName = "$modId-fabric"

val javaVersion = if (sc.current.parsed >= "26.1") 25 else 21

dependencies {
    /** Only the Fabric API modules the mod uses (faster setup than the whole API). */
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties["deps.fabric_loader"]}")
    fapi(
        "fabric-api-base", "fabric-lifecycle-events-v1", "fabric-message-api-v1", "fabric-networking-api-v1",
        "fabric-rendering-v1", "fabric-command-api-v2", "fabric-recipe-api-v1",
    )

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.code.gson:gson:2.10.1")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
    accessWidenerPath = sc.process(rootProject.file("src/main/resources/yamcmcp.ct"), "build/processed.ct")

    runConfigs.all {
        ideConfigGenerated(false)
    }
    runs {
        named("client") {
            runDir("../../run/${project.name}")
        }
        // Dev test client: auto-creates and joins a singleplayer world named "mcptest".
        register("testClient") {
            client()
            runDir("../../run-test/${project.name}")
            vmArg("-Dyamcmcp.devAutoWorld=mcptest")
            programArgs("--width", "854", "--height", "480")
        }
    }
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
            "id" to modId,
            "name" to sc.properties["mod.name"],
            "version" to modVersion,
            "license" to sc.properties["mod.license"],
            "minecraft" to sc.properties["mod.mc_compat"],
            "loader" to sc.properties["deps.fabric_loader"],
            "java" to "JAVA_$javaVersion",
        )
        inputs.properties(props)
        filesMatching(listOf("fabric.mod.json", "*.mixins.json")) { expand(props) }
        exclude("META-INF/neoforge.mods.toml", "META-INF/accesstransformer.cfg")
    }

    withType<Jar> {
        val suffix = "_$modId"
        from(rootProject.file("LICENSE")) { rename { it + suffix } }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the jar and copies it to build/libs/<mod version>/ in the root project."
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}
