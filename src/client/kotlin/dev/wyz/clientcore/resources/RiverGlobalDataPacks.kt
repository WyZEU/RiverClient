package dev.wyz.clientcore.resources

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.FilePackResources
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A single global data-pack folder (gameDir/river/global_datapacks). Every .zip or folder
 * dropped in it is force-enabled as a data pack for every singleplayer world, so packs the
 * user downloads from the in-game browser apply everywhere with no per-world setup.
 *
 * This only matters in singleplayer: on a server the server owns its own data packs, and
 * this source is only appended to the integrated server's data repository (see
 * PackRepositoryMixin), never to anything that touches multiplayer.
 */
object RiverGlobalDataPacks {

    fun folder(): Path {
        val dir = Minecraft.getInstance().gameDirectory.toPath().resolve("river").resolve("global_datapacks")
        runCatching { if (!dir.exists()) dir.createDirectories() }
        return dir
    }

    @JvmStatic
    fun appendTo(existing: Set<RepositorySource>): Set<RepositorySource> {
        return existing + RepositorySource { consumer ->
            val dir = folder()
            if (!dir.exists() || !dir.isDirectory()) return@RepositorySource
            val entries = runCatching {
                dir.listDirectoryEntries().sortedBy { it.name.lowercase() }
            }.getOrDefault(emptyList())

            for (entry in entries) {
                val isDirectory = entry.isDirectory()
                val isZip = entry.name.endsWith(".zip", ignoreCase = true)
                if (!isDirectory && !isZip) continue

                val supplier: Pack.ResourcesSupplier = if (isDirectory) {
                    PathPackResources.PathResourcesSupplier(entry)
                } else {
                    FilePackResources.FileResourcesSupplier(entry)
                }

                val pack = Pack.readMetaAndCreate(
                    PackLocationInfo("river_global/${entry.name}", Component.literal("River: ${entry.name}"), PackSource.WORLD, java.util.Optional.empty()),
                    supplier,
                    PackType.SERVER_DATA,
                    // required = true force-enables the pack, so downloaded data packs apply
                    // without the user re-enabling them for every world.
                    PackSelectionConfig(true, Pack.Position.TOP, false)
                )
                if (pack != null) consumer.accept(pack)
            }
        }
    }
}
