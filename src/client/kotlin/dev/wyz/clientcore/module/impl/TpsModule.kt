package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import net.minecraft.client.Minecraft

/**
 * How fast the server is ticking, out of 20.
 *
 * In singleplayer the game's own server reports its average tick time. On a server there
 * is no number to ask for, but the server sends the time once every 20 of its ticks, so
 * the gap between two of those says how fast it is running: one second apart is 20 TPS,
 * two seconds apart is 10. Averaged over the last few, so one late packet doesn't jump it.
 */
class TpsModule : RowsHudModule("tps", "TPS", "Server ticks per second", "item:minecraft:repeater", 8, 460) {

    companion object {
        private const val SAMPLES = 5
        private val gaps = ArrayDeque<Long>()
        @Volatile private var lastAt = 0L

        /** Each time update from the server, on the game thread. */
        fun onTimeUpdate() {
            val now = System.currentTimeMillis()
            val previous = lastAt
            lastAt = now
            if (previous == 0L) return
            val gap = now - previous
            // A gap over 10 s is a reconnect or a world change, not a slow server.
            if (gap <= 0 || gap > 10_000) {
                synchronized(gaps) { gaps.clear() }
                return
            }
            synchronized(gaps) {
                gaps.addLast(gap)
                while (gaps.size > SAMPLES) gaps.removeFirst()
            }
        }

        private fun serverTps(): Double? {
            if (System.currentTimeMillis() - lastAt > 15_000) return null
            val average = synchronized(gaps) { if (gaps.isEmpty()) null else gaps.average() } ?: return null
            return (20_000.0 / average).coerceIn(0.0, 20.0)
        }
    }

    private fun tps(client: Minecraft): Double? {
        val local = client.singleplayerServer
        if (local != null) {
            val mspt = runCatching { local.averageTickTimeNanos / 1_000_000.0 }.getOrNull() ?: return null
            if (mspt <= 0.0) return 20.0
            return (1000.0 / mspt).coerceAtMost(20.0)
        }
        return serverTps()
    }

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        if (client.level == null) return emptyList()
        val tps = tps(client) ?: return listOf("TPS" to "...")
        return listOf("TPS" to "%.1f".format(tps))
    }

    override fun previewRows() = listOf("TPS" to "20.0")
}
