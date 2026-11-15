package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import net.minecraft.client.Minecraft

/**
 * A stopwatch on a key: press to start, press to stop, press again to start over from zero.
 * For timing a speedrun split, a bridge, a parkour section.
 */
class StopwatchModule : RowsHudModule("stopwatch", "Stopwatch", "Start and stop a timer with a key", "item:minecraft:recovery_compass", 8, 556) {

    override val keybindLabel: String = "Start / stop key"

    private var startedAt = 0L
    private var stoppedAt = 0L
    private var running = false

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active) return false
        val now = System.currentTimeMillis()
        if (running) {
            running = false
            stoppedAt = now
        } else {
            running = true
            startedAt = now
            stoppedAt = 0L
        }
        return true
    }

    private fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        return "%d:%02d.%02d".format(total / 60_000, (total / 1000) % 60, (total / 10) % 100)
    }

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        val elapsed = when {
            running -> System.currentTimeMillis() - startedAt
            startedAt != 0L -> stoppedAt - startedAt
            else -> 0L
        }
        return listOf("Stopwatch" to format(elapsed))
    }

    override fun previewRows() = listOf("Stopwatch" to "1:24.37")
}
