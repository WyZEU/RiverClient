package dev.wyz.riverbootstrap

import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.time.Instant

private const val MAIN_CLASS_PROPERTY = "river.bootstrap.mainClass"
private const val CLASSPATH_PROPERTY = "river.bootstrap.classpath"
private const val GAME_DIR_PROPERTY = "river.bootstrap.gameDir"
private const val VERSION_PROPERTY = "river.bootstrap.version"

fun main(args: Array<String>) {
    val targetMainClass = System.getProperty(MAIN_CLASS_PROPERTY)?.trim().orEmpty()
    require(targetMainClass.isNotEmpty()) {
        "River bootstrap did not receive a target main class."
    }

    val classpathEntries = System.getProperty(CLASSPATH_PROPERTY)
        ?.split(File.pathSeparatorChar)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.map(::File)
        ?.filter(File::exists)
        .orEmpty()

    require(classpathEntries.isNotEmpty()) {
        "River bootstrap did not receive a usable classpath."
    }

    writeBootstrapMarker()

    val urls = classpathEntries.map { it.toURI().toURL() }.toTypedArray()
    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
        Thread.currentThread().contextClassLoader = loader
        val mainClass = Class.forName(targetMainClass, true, loader)
        val mainMethod: Method = mainClass.getMethod("main", Array<String>::class.java)
        mainMethod.invoke(null, args)
    }
}

private fun writeBootstrapMarker() {
    val gameDir = System.getProperty(GAME_DIR_PROPERTY)?.takeIf { it.isNotBlank() } ?: return
    val version = System.getProperty(VERSION_PROPERTY)?.takeIf { it.isNotBlank() } ?: "unknown"
    runCatching {
        val root = File(gameDir, "river-runtime")
        root.mkdirs()
        File(root, "bootstrap-state.json").writeText(
            """
            {
              "launcher": "River Client",
              "mode": "bootstrap",
              "minecraftVersion": "$version",
              "startedAt": "${Instant.now()}"
            }
            """.trimIndent()
        )
    }
}
