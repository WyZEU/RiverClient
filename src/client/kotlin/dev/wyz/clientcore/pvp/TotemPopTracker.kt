package dev.wyz.clientcore.pvp

import net.minecraft.world.entity.player.Player
import java.util.UUID

object TotemPopTracker {
    private data class PopEntry(
        var name: String,
        var pops: Int,
        var updatedAt: Long
    )

    private val entries = LinkedHashMap<UUID, PopEntry>()

    @JvmStatic
    fun record(player: Player) {
        val now = System.currentTimeMillis()
        val current = entries[player.uuid]
        if (current == null) {
            entries[player.uuid] = PopEntry(player.name.string, 1, now)
        } else {
            current.name = player.name.string
            current.pops += 1
            current.updatedAt = now
        }
    }

    @JvmStatic
    fun topEntries(limit: Int = 5): List<Pair<String, Int>> {
        return entries.values
            .sortedWith(compareByDescending<PopEntry> { it.pops }.thenByDescending { it.updatedAt })
            .take(limit)
            .map { it.name to it.pops }
    }

    @JvmStatic
    fun clear() {
        entries.clear()
    }
}
