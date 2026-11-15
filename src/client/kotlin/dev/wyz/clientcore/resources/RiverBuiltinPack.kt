package dev.wyz.clientcore.resources

import net.minecraft.network.chat.Component
import net.minecraft.server.packs.FilePackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import java.net.JarURLConnection
import java.nio.file.Path
import java.util.Optional

/**
 * River's built-in resource pack, force-enabled and pinned like a server pack.
 * This is how the badge font (and any future pack-based asset) loads when River
 * is agent-injected and Fabric's mod resource pipeline never sees our jar.
 * Lunar and Feather ship their assets the same way.
 *
 * The pack root is discovered from our own classpath location, so it works both
 * for the packaged jar and for a dev run where resources live in a directory.
 */
object RiverBuiltinPack {

    private const val PACK_ID = "river_builtin"

    /** A resource that only exists in OUR jar, so classpath lookup can't hit another mod. */
    private const val ANCHOR = "/assets/clientcore/font/river_badge.json"

    @JvmStatic
    fun appendTo(existing: Set<RepositorySource>): Set<RepositorySource> {
        val source = createSource() ?: return existing
        return existing + source
    }

    private fun createSource(): RepositorySource? {
        val root = findPackRoot() ?: return null
        val supplier: Pack.ResourcesSupplier = if (root.toFile().isDirectory) {
            PathPackResources.PathResourcesSupplier(root)
        } else {
            FilePackResources.FileResourcesSupplier(root)
        }
        return RepositorySource { consumer ->
            val pack = Pack.readMetaAndCreate(
                PackLocationInfo(PACK_ID, Component.literal("River Client"), PackSource.BUILT_IN, Optional.empty()),
                supplier,
                PackType.CLIENT_RESOURCES,
                PackSelectionConfig(true, Pack.Position.TOP, true)
            )
            if (pack != null) {
                consumer.accept(pack)
            } else {
                log("pack metadata could not be read from $root")
            }
        }
    }

    private fun findPackRoot(): Path? = runCatching {
        val url = RiverBuiltinPack::class.java.getResource(ANCHOR) ?: run {
            log("anchor resource missing, no built-in pack")
            return null
        }
        when (url.protocol) {
            "jar" -> {
                val connection = url.openConnection() as JarURLConnection
                Path.of(java.io.File(connection.jarFileURL.toURI()).absolutePath)
            }
            "file" -> {
                // .../assets/clientcore/font/river_badge.json -> pack root is 4 levels up.
                var path = Path.of(url.toURI())
                repeat(4) { path = path.parent ?: return null }
                path
            }
            else -> {
                log("unsupported resource protocol ${url.protocol}")
                null
            }
        }
    }.onFailure { log("failed to locate pack root: $it") }.getOrNull()

    private fun log(message: String) {
        println("[river] builtin pack: $message")
    }
}
