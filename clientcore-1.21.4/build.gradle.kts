plugins {
    id("fabric-loom") version "1.17.14"
    kotlin("jvm") version "2.3.21"
}

// River Client for Minecraft 1.21.4. This is a fork of the 1.21.11 clientcore source
// (../src), not a shared/preprocessed tree - Mojang renamed enough (ResourceLocation vs
// Identifier, Player vs Avatar) and rewrote enough of the render pipeline between these
// versions that a single source tree isn't worth the preprocessor complexity. Fixes and
// features that apply to both versions need to be ported by hand between the two trees.
version = "0.1.6"
group = "dev.wyz"

base {
    // Matches the launcher's clientcoreJarNameFor(): a bare "+<mc>" suffix marks the
    // Minecraft version a clientcore jar targets. See launcher/src/bundled/README.md.
    archivesName.set("clientcore")
}
project.version = "${version}+1.21.4"

repositories {
    maven("https://jitpack.io")
    maven("https://api.modrinth.com/maven")
}

val minecraftVersion = "1.21.4"
val fabricLoaderVersion = "0.19.3"
val fabricApiVersion = "0.119.4+1.21.4"
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
    // Official Mojang mappings, same as the 1.21.11 project - keeps class/method names
    // consistent between the two source trees (Identifier/ResourceLocation, Avatar/Player
    // etc. are Yarn-vs-official naming differences, not version differences; mixing mapping
    // sources here would make every rename look like a fake version diff).
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    modRuntimeOnly("maven.modrinth:sodium:mc1.21.4-0.6.13-fabric")
    modRuntimeOnly("maven.modrinth:lithium:mc1.21.4-0.15.3-fabric")
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
    inputs.property("version", version)

    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}
