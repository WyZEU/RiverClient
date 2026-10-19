package dev.wyz.clientcore.module

import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * A HUD readout made of label/value rows in one panel, drawn the way the Time module
 * draws its own. A row with an empty label is a single value across the panel.
 *
 * The small info modules (pack display, server address, saturation, ...) are nothing but
 * this, so they say what to show and the drawing lives here once.
 */
abstract class RowsHudModule(
    id: String,
    displayName: String,
    description: String,
    icon: String,
    defaultX: Int,
    defaultY: Int,
    defaultEnabled: Boolean = false
) : Module(id, displayName, description, ModuleCategory.HUD, icon, defaultX, defaultY, defaultEnabled) {

    /** What to show right now, or an empty list to draw nothing. */
    protected abstract fun rows(client: Minecraft): List<Pair<String, String>>

    /** What the HUD editor shows when there is no world to read from. */
    protected abstract fun previewRows(): List<Pair<String, String>>

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawRows(client, graphics, rows(client))
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawRows(client, graphics, previewRows())
    }

    private fun drawRows(client: Minecraft, graphics: GuiGraphics, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val gap = 6
        val lineH = font.lineHeight + 2 + st.spacing
        val labelW = rows.filter { it.first.isNotEmpty() }.maxOfOrNull { font.width(it.first) } ?: 0
        val rowW = rows.maxOf { (label, value) ->
            if (label.isEmpty()) font.width(value) else labelW + gap + font.width(value)
        }
        val w = pad + 4 + rowW + pad
        val h = pad * 2 + rows.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val value = panelTextColor()
        val lx = x + pad + 4
        rows.forEachIndexed { i, (label, text) ->
            val ly = y + pad + i * lineH
            if (label.isEmpty()) {
                graphics.drawString(font, text, lx, ly, value, st.textShadow)
            } else {
                graphics.drawString(font, label, lx, ly, labelColor, st.textShadow)
                graphics.drawString(font, text, lx + labelW + gap, ly, value, st.textShadow)
            }
        }
    }
}
