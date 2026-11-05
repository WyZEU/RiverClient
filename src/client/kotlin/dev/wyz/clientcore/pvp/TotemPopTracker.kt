package dev.wyz.clientcore.pvp

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.TotemCounterModule
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.UUID

/**
 * Totem pops per player, counted from entity event 35 - the one the server sends when a
 * totem saves someone, and the same signal TotemCounter counts. Reset when that player
 * dies, when a round ends on servers that announce it, when you leave the world, and by
 * hand from the Totem Counter module.
 */
object TotemPopTracker {
    private data class PopEntry(
        var name: String,
        var pops: Int,
        var updatedAt: Long
    )

    private val entries = LinkedHashMap<UUID, PopEntry>()

    /** What minigame servers print when a round is over (TotemCounter's list). */
    private val ROUND_END_MESSAGES = listOf("Winners:", "has won the round.", "has won the game!", "Winner: NONE!", "Match Complete")

    @JvmStatic
    fun record(player: Player) {
        val now = System.currentTimeMillis()
        val current = entries[player.uuid]
        val pops = if (current == null) {
            entries[player.uuid] = PopEntry(player.name.string, 1, now)
            1
        } else {
            current.name = player.name.string
            current.pops += 1
            current.updatedAt = now
            current.pops
        }
        ModuleRegistry.get<TotemCounterModule>("totem_counter")?.onPop(player, pops)
    }

    @JvmStatic
    fun pops(uuid: UUID): Int = entries[uuid]?.pops ?: 0

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

    /** A death ends that player's count: the next life starts from zero. */
    fun tick(client: Minecraft) {
        val level = client.level
        if (level == null) {
            entries.clear()
            return
        }
        if (entries.isEmpty()) return
        if (ModuleRegistry.get<TotemCounterModule>("totem_counter")?.resetOnDeath() == false) return
        for (player in level.players()) {
            if (player.isDeadOrDying && entries.containsKey(player.uuid)) entries.remove(player.uuid)
        }
    }

    @JvmStatic
    fun onChatMessage(text: String) {
        if (entries.isEmpty()) return
        if (ModuleRegistry.get<TotemCounterModule>("totem_counter")?.resetOnRoundEnd() == false) return
        if (ROUND_END_MESSAGES.any { text.contains(it) }) entries.clear()
    }
}
