plugins {
    /*
      Loom ships as two plugins from 1.17.20. `-remap` is the classic one that maps
      the game jar into readable names; the plain one skips that stage entirely and
      is the only one that will configure without a `mappings` dependency. Which of
      them a target needs depends on whether its Minecraft version is obfuscated, so
      both are put on the classpath here and exactly one is applied below.
    */
    id("net.fabricmc.fabric-loom-remap") version "1.17.20" apply false
    id("net.fabricmc.fabric-loom") version "1.17.20" apply false
    kotlin("jvm") version "2.3.21"
}

/*
  Supplied per target by Stonecutter from versions/<mc>/gradle.properties. Read before
  `version` because the Minecraft version goes into the artifact name: every target
  writes to its own versions/<mc>/build/libs, but the jars all came out on the same
  file name, so the launcher had no way to tell a 1.21.4 build from a 1.21.11 one. The
  Fabric-style `+<mc>` suffix is exactly what it parses to pick the right jar.
*/
val minecraftVersion = property("minecraft_version") as String

/*
  1.21.11 was the last obfuscated release. From 26.1 the game ships unobfuscated and
  with parameter names, so Mojang publishes no client_mappings for it and Fabric has
  retired Yarn - there is nothing to remap against and nothing that needs remapping.
  Those releases also require Java 25; everything before them builds on 21.
*/
val unobfuscated = !minecraftVersion.startsWith("1.")
val javaVersion = if (unobfuscated) 25 else 21

apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

// Applying by id rather than in the plugins block means no generated `loom { }`
// accessor, so the extension is looked up by type instead.
val loom = extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()

version = "0.1.8.8+$minecraftVersion"
group = "dev.wyz"

base {
    archivesName.set("clientcore")
}

repositories {
    maven("https://jitpack.io")
    maven("https://api.modrinth.com/maven")
}

// Supplied per version by Stonecutter from versions/<ver>/gradle.properties, so
// this file is built once per entry in settings.gradle.kts rather than copied.
val fabricLoaderVersion = property("fabric_loader") as String
val fabricApiVersion = property("fabric_api") as String
val fabricKotlinVersion = property("fabric_kotlin") as String
val packFormat = property("pack_format") as String

/*
  From pack format 65 the game refuses a pack that declares only `pack_format`:
  "Pack declares support for version newer than 64, but is missing mandatory fields
  min_format and max_format". River was declaring just the one field on every target,
  so on 1.21.11 and both 26.x builds its own assets were being rejected outright.
  Each target builds for exactly one game version, so the range is that version alone.
*/
val packFormatRange = if (packFormat.toInt() > 64) {
    """,
    "min_format": $packFormat,
    "max_format": $packFormat"""
} else {
    ""
}

loom.splitEnvironmentSourceSets()

loom.mods.create("clientcore") {
    sourceSet(sourceSets["client"])
}

/*
  The `mod*` configurations exist to run a dependency through remapping on the way in.
  The no-remap plugin has no such stage and so does not declare them: on those targets
  a mod jar is already in the names the game uses and goes on the plain configuration.
*/
val modImplementation = if (unobfuscated) "implementation" else "modImplementation"
val modRuntimeOnly = if (unobfuscated) "runtimeOnly" else "modRuntimeOnly"

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    if (!unobfuscated) {
        "mappings"(loom.officialMojangMappings())
    }

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    /*
      Dev-run parity with the launcher's optimization suite: the real client always
      ships with these (ensureOptimizationSuite in the launcher installs them from
      Modrinth), so runClient loads the same core set - otherwise dev-instance FPS
      comparisons are meaningless. Runtime-only: never compiled against, not bundled.

      Pinned to the development version, and only added there. The other targets are
      build-only, and handing a 1.21.11 mod jar to a 26.x target would ask Loom to
      remap a jar built for a different game.
    */
    if (minecraftVersion == "1.21.11") {
        modRuntimeOnly("maven.modrinth:sodium:mc1.21.11-0.8.13-fabric")
        modRuntimeOnly("maven.modrinth:lithium:mc1.21.11-0.21.4-fabric")
        modRuntimeOnly("maven.modrinth:ferrite-core:8.2.0-fabric")
        modRuntimeOnly("maven.modrinth:immediatelyfast:1.14.3+1.21.11-fabric")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }

    withSourcesJar()
}

kotlin {
    jvmToolchain(javaVersion)
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("fabric_loader", fabricLoaderVersion)
    inputs.property("pack_format", packFormat)
    inputs.property("java_version", javaVersion)

    // The mod metadata and the pack format are the only resources that differ
    // per version, so they are templated rather than duplicated per tree.
    // The Java requirement is declared rather than fixed at 21: the 26.x builds are
    // compiled to 25 and would fail with a class-version error on 21, so Fabric should
    // say so plainly instead of letting the load crash.
    filesMatching(listOf("fabric.mod.json", "pack.mcmeta")) {
        expand(
            "version" to project.version,
            "minecraft_version" to minecraftVersion,
            "fabric_loader" to fabricLoaderVersion,
            "pack_format" to packFormat,
            "pack_format_range" to packFormatRange,
            "java_version" to javaVersion
        )
    }
}

// pack.mcmeta lives in the client source set, which gets its own task under
// splitEnvironmentSourceSets(), so templating it needs configuring separately.
tasks.named<ProcessResources>("processClientResources") {
    inputs.property("pack_format", packFormat)
    inputs.property("pack_format_range", packFormatRange)
    filesMatching("pack.mcmeta") {
        expand("pack_format" to packFormat, "pack_format_range" to packFormatRange)
    }
}

// Client + main source sets can contribute the same resource path.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}
