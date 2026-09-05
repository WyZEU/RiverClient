pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Multi-version source preprocessing. One source tree, version-conditional
    // comments, one jar per entry in the version list below.
    id("dev.kikugie.stonecutter") version "0.9.7"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "ClientCore"

stonecutter {
    // Each entry builds its own jar from the shared sources in src/.
    // `vcsVersion` is the one the working tree is written against - the source
    // reads as plain 1.21.11 code and the older version is the commented branch.
    create(rootProject) {
        // 26.1 and 26.2 are separate API generations, not one "26.x": both ship
        // unobfuscated and need the no-remap Loom and Java 25, but only 26.2 splits the
        // HUD out of Gui, moves screen ownership onto it, and puts the screen overlays
        // on the submit-node model. Conditions are tagged >=26.1 or >=26.2 accordingly.
        versions("1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.11", "26.1.2", "26.2")
        vcsVersion = "1.21.11"
    }
}

include(":river-bootstrap")
