package dev.wyz.clientcore.world

import dev.wyz.clientcore.config.WaypointData

/**
 * Compact, human-pasteable waypoint string for sharing in chat or DMs.
 * Format: `river:wp:<name>:<x>:<y>:<z>:<color>` with the name URL-ish escaped
 * so colons and spaces survive. Anyone on River can paste it into Import.
 */
object WaypointShare {

    private const val PREFIX = "river:wp:"

    fun encode(wp: WaypointData): String {
        val name = wp.name.replace("%", "%25").replace(":", "%3A")
        return "$PREFIX$name:${wp.x}:${wp.y}:${wp.z}:${wp.color}"
    }

    /** Returns a fresh WaypointData (dimension left default; caller sets current), or null. */
    fun decode(raw: String?): WaypointData? {
        val text = raw?.trim() ?: return null
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split(":")
        if (parts.size < 5) return null
        val name = parts[0].replace("%3A", ":").replace("%25", "%").ifEmpty { "Waypoint" }.take(20)
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        val color = parts[4].toIntOrNull() ?: 0
        return WaypointData(name = name, x = x, y = y, z = z, color = color)
    }
}
