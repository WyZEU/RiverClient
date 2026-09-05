package dev.wyz.clientcore.tab

import java.util.UUID

object TabPingCache {
    private data class Entry(
        var text: String,
        var latency: Int,
        var lastRefreshAt: Long
    )

    private val entries = HashMap<UUID, Entry>()
    private const val REFRESH_MS = 5_000L

    @JvmStatic
    fun pingText(uuid: UUID, latency: Int): String {
        val now = System.currentTimeMillis()
        val entry = entries[uuid]
        val normalizedLatency = latency.coerceAtLeast(-1)
        if (entry == null || entry.latency != normalizedLatency || now - entry.lastRefreshAt >= REFRESH_MS) {
            val text = if (latency < 0) "?" else latency.toString()
            entries[uuid] = Entry(text, normalizedLatency, now)
            return text
        }
        return entry.text
    }
}
