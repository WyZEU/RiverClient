package dev.wyz.clientcore.ui

/**
 * Rainbow as a colour choice. Any colour setting can hold it: the colour picker's Rainbow
 * button stores [RAINBOW_RGB], a near-black nobody picks by hand, and whatever draws that
 * colour passes it through [resolve], which turns it into the hue of the moment. Settings
 * keep storing a plain int, so no setting needed a new field.
 */
object RiverColor {
    const val RAINBOW_RGB = 0x010203
    private const val CYCLE_MS = 4000L

    fun isRainbow(color: Int): Boolean = (color and 0xFFFFFF) == RAINBOW_RGB

    /**
     * [color] as it should be drawn now: unchanged, or for rainbow the current hue with the
     * same alpha. [offset] (0 to 1) moves along the rainbow, so neighbouring letters or words
     * can take different colours of it.
     */
    fun resolve(color: Int, offset: Float = 0f): Int {
        if (!isRainbow(color)) return color
        val hue = (dev.wyz.clientcore.dev.DevClock.millis() % CYCLE_MS) / CYCLE_MS.toFloat() + offset
        return (color and 0xFF000000.toInt()) or (ClientUi.hsbToRgb(hue - kotlin.math.floor(hue), 0.7f, 1f) and 0xFFFFFF)
    }
}
