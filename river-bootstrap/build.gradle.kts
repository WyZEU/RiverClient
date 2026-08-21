plugins {
    kotlin("jvm") version "2.3.21"
    application
}

group = "dev.wyz"
version = rootProject.version

repositories {
    mavenCentral()
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

application {
    mainClass.set("dev.wyz.riverbootstrap.MainKt")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("river-bootstrap")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // Main-Class keeps the legacy wrapper launch working; Premain-Class makes this jar usable
        // as a -javaagent that injects River's mixins without River being a Fabric mod.
        attributes["Main-Class"] = "dev.wyz.riverbootstrap.MainKt"
        attributes["Premain-Class"] = "dev.wyz.riverbootstrap.Agent"
        attributes["Agent-Class"] = "dev.wyz.riverbootstrap.Agent"
        attributes["Can-Retransform-Classes"] = "false"
        attributes["Can-Redefine-Classes"] = "false"
    }
    from(
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    )
}
