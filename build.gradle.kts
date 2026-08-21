plugins {
    id("fabric-loom") version "1.17.14"
    kotlin("jvm") version "2.3.21"
}

version = "0.1.6"
group = "dev.wyz"

base {
    archivesName.set("clientcore")
}

repositories {
    maven("https://jitpack.io")
    maven("https://api.modrinth.com/maven")
}

val minecraftVersion = "1.21.11"
val fabricLoaderVersion = "0.19.2"
val fabricApiVersion = "0.141.3+1.21.11"
val fabricKotlinVersion = "1.13.11+kotlin.2.3.21"

loom {
    splitEnvironmentSourceSets()

    mods {
        create("clientcore") {
            sourceSet(sourceSets["client"])
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // Dev-run parity with the launcher's optimization suite: the real client always
    // ships with these (ensureOptimizationSuite in the launcher installs them from
    // Modrinth), so runClient loads the same core set - otherwise dev-instance FPS
    // comparisons are meaningless. Runtime-only: never compiled against, not bundled.
    modRuntimeOnly("maven.modrinth:sodium:mc1.21.11-0.8.13-fabric")
    modRuntimeOnly("maven.modrinth:lithium:mc1.21.11-0.21.4-fabric")
    modRuntimeOnly("maven.modrinth:ferrite-core:8.2.0-fabric")
    modRuntimeOnly("maven.modrinth:immediatelyfast:1.14.3+1.21.11-fabric")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// Client + main source sets can contribute the same resource path.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
