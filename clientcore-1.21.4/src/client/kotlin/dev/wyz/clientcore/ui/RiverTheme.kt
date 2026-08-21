package dev.wyz.clientcore.ui

import dev.wyz.clientcore.RiverRuntime

/**
 * Accent themes. The picked accent drives the whole UI (menu, toggles, sliders)
 * and the HUD pill accents. Stored per profile as [dev.wyz.clientcore.config.ClientCoreConfig.theme]
 * ("custom" reads the hex in [dev.wyz.clientcore.config.ClientCoreConfig.themeCustom]).
 */
object RiverTheme {

    const val CUSTOM_KEY = "custom"

    data class Palette(val key: String, val label: String, val accentA: Int, val accentB: Int, val hudAccent: Int)

    val presets: List<Palette> = listOf(
        Palette("river", "River", 0xFF5F49B8.toInt(), 0xFF7A59D6.toInt(), 0xFF7A8CFF.toInt()),
        Palette("ocean", "Ocean", 0xFF2F6FE0.toInt(), 0xFF19B8D8.toInt(), 0xFF62C6FF.toInt()),
        Palette("emerald", "Emerald", 0xFF0FA36B.toInt(), 0xFF2ED49A.toInt(), 0xFF57E6AE.toInt()),
        Palette("ember", "Ember", 0xFFD9542E.toInt(), 0xFFE8853B.toInt(), 0xFFFF9E6B.toInt()),
        Palette("rose", "Rose", 0xFFC23A78.toInt(), 0xFFE05FA8.toInt(), 0xFFFF8FC0.toInt()),
        Palette("mono", "Mono", 0xFF4B5263.toInt(), 0xFF7A8398.toInt(), 0xFFC9CEDA.toInt())
    )

    // Memoized: `current` is read many times per frame (every accent color in the UI and
    // HUD goes through it), so the palette is only rebuilt when the config values change.
    private var cachedThemeKey: String? = null
    private var cachedCustomA = -1
    private var cachedCustomB = -1
    private var cachedPalette: Palette? = null

    val current: Palette
        get() {
            val cfg = RiverRuntime.configOrNull()
            val key = cfg?.theme ?: "river"
            if (key == CUSTOM_KEY) {
                val a = (cfg?.themeCustom ?: 0x7A8CFF) and 0xFFFFFF
                val b = (cfg?.themeCustomB ?: 0xAD85FF) and 0xFFFFFF
                val hit = cachedPalette
                if (hit != null && cachedThemeKey == key && cachedCustomA == a && cachedCustomB == b) return hit
                return customPalette(a, b).also {
                    cachedPalette = it
                    cachedThemeKey = key
                    cachedCustomA = a
                    cachedCustomB = b
                }
            }
            val hit = cachedPalette
            if (hit != null && cachedThemeKey == key) return hit
            return (presets.firstOrNull { it.key == key } ?: presets.first()).also {
                cachedPalette = it
                cachedThemeKey = key
            }
        }

    /**
     * Builds a full palette from two explicit gradient stops. The HUD accent is a
     * brighter, less saturated version of the start color so pills stay readable
     * over the world.
     */
    fun customPalette(startRgb: Int, endRgb: Int): Palette {
        val hsb = rgbToHsb(startRgb)
        val accentA = (0xFF shl 24) or (startRgb and 0xFFFFFF)
        val accentB = (0xFF shl 24) or (endRgb and 0xFFFFFF)
        val hud = (0xFF shl 24) or (hsbToRgb(hsb[0], (hsb[1] * 0.72f).coerceIn(0f, 1f), (hsb[2] + 0.26f).coerceIn(0.55f, 1f)) and 0xFFFFFF)
        return Palette(CUSTOM_KEY, "Custom", accentA, accentB, hud)
    }

    /**
     * Convenience for a single base hex: derives the second stop by rotating the
     * hue ~28 degrees and lifting it, matching the old one-color custom behavior.
     */
    fun customPalette(rgb: Int): Palette {
        val hsb = rgbToHsb(rgb)
        val end = hsbToRgb((hsb[0] + 0.078f) % 1f, (hsb[1] * 0.92f).coerceIn(0f, 1f), (hsb[2] + 0.12f).coerceIn(0f, 1f)) and 0xFFFFFF
        return customPalette(rgb and 0xFFFFFF, end)
    }

    private fun rgbToHsb(rgb: Int): FloatArray {
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        var hue = 0f
        if (delta > 0.00001f) {
            hue = when (max) {
                r -> ((g - b) / delta) % 6f
                g -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            } / 6f
            if (hue < 0f) hue += 1f
        }
        val sat = if (max <= 0f) 0f else delta / max
        return floatArrayOf(hue, sat, max)
    }

    private fun hsbToRgb(h: Float, s: Float, b: Float): Int {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = b * (1f - s)
        val q = b * (1f - f * s)
        val t = b * (1f - (1f - f) * s)
        val (r, g, bl) = when (i % 6) {
            0 -> Triple(b, t, p)
            1 -> Triple(q, b, p)
            2 -> Triple(p, b, t)
            3 -> Triple(p, q, b)
            4 -> Triple(t, p, b)
            else -> Triple(b, p, q)
        }
        return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
    }
}
