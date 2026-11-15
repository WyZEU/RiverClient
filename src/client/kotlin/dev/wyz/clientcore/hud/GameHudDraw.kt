package dev.wyz.clientcore.hud

/**
 * Alpha for whatever vanilla HUD part is being drawn right now. GuiMixin sets these around a part
 * and GuiGraphicsFadeMixin applies them, because vanilla picks those colors deep inside its own
 * code. Reset every frame in case something cancelled a part before its end was reached.
 */
object GameHudDraw {
    /** For background rectangles (sidebar, tab list). */
    @JvmField var fillAlpha = 1f

    /** For backdrop text (action bar, title), which fades in with this. */
    @JvmField var textAlpha = 1f

    /** Lowest alpha for text: older versions read a nearly transparent color as opaque. */
    private const val MIN_TEXT_ALPHA = 5

    @JvmStatic
    fun fill(color: Int): Int = if (fillAlpha >= 1f) color else scaled(color, fillAlpha, 0)

    @JvmStatic
    fun text(color: Int): Int = if (textAlpha >= 1f) color else scaled(color, textAlpha, MIN_TEXT_ALPHA)

    @JvmStatic
    fun reset() {
        fillAlpha = 1f
        textAlpha = 1f
    }

    private fun scaled(color: Int, alpha: Float, min: Int): Int =
        (((color ushr 24) * alpha).toInt().coerceAtLeast(min) shl 24) or (color and 0xFFFFFF)
}
