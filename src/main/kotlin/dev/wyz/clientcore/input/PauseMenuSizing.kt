package dev.wyz.clientcore.input

import kotlin.math.roundToInt

/** Fits the pause column inside the scaled viewport, keeping a physical edge margin. */
object PauseMenuSizing {
    data class Layout(val scale: Float, val width: Int, val height: Int, val padding: Int)

    fun layout(width: Int, height: Int, guiScale: Double, contentHeight: Int): Layout {
        val padding = (24.0 / guiScale.coerceAtLeast(1.0)).roundToInt().coerceIn(4, 16)
        val scale = minOf(1f, ((height - padding * 2 - 18).coerceAtLeast(1)).toFloat() / contentHeight.coerceAtLeast(1),
            (width - padding * 2).coerceAtLeast(1) / 230f).coerceAtLeast(0.05f)
        return Layout(scale, (width / scale).toInt(), (height / scale).toInt(), (padding / scale).roundToInt())
    }
}
