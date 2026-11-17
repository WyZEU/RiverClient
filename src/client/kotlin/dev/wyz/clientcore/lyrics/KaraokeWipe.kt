package dev.wyz.clientcore.lyrics

import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverShapes
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * One karaoke word, filled from the left as it is sung. The fill edge moves by fractions of a
 * pixel with a soft edge instead of jumping a letter at a time: the sung colour is drawn over
 * the waiting one, clipped to the sung part, and the few pixel columns at the edge are drawn
 * part-way between the two. Where River's shape shader is available, a small soft light rides
 * on the edge.
 *
 * Cheap on purpose: a word not being sung is one draw, the word being sung at most six, and
 * nothing is measured here (the caller passes the width it already knows).
 */
object KaraokeWipe {
    /** How wide the soft edge is, in GUI pixels. */
    private const val FEATHER = 3f

    fun word(g: GuiGraphics, font: Font, text: Component, x: Int, y: Int, w: Int, fill: Float, sung: Int, waiting: Int, shadow: Boolean, light: Int = 0) {
        if (fill <= 0f) { g.drawString(font, text, x, y, waiting, shadow); return }
        if (fill >= 1f || w <= 0) { g.drawString(font, text, x, y, sung, shadow); return }
        g.drawString(font, text, x, y, waiting, shadow)
        val top = y - 1
        val bottom = y + font.lineHeight + 1
        val edge = x + w * fill
        val solidEnd = kotlin.math.floor(edge - FEATHER / 2f).toInt()
        if (solidEnd > x) ClientUi.withScissor(g, x - 1, top, solidEnd, bottom) { g.drawString(font, text, x, y, sung, false) }
        val last = kotlin.math.ceil(edge + FEATHER / 2f).toInt().coerceAtMost(x + w + 1)
        for (column in maxOf(solidEnd, x) until last) {
            val a = ((edge - (column + 0.5f)) / FEATHER + 0.5f).coerceIn(0f, 1f)
            if (a < 0.04f) continue
            ClientUi.withScissor(g, column, top, column + 1, bottom) { g.drawString(font, text, x, y, ClientUi.alpha(sung, a), false) }
        }
        if (light != 0 && RiverShapes.available) {
            RiverShapes.shadow(g, edge.toInt(), y + 1, 1, font.lineHeight - 2, 1, light, 5f)
        }
    }
}
