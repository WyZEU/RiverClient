package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Wall clock and session length in one readout.
 *
 * Both answer "how long have I been at this", and nobody wants one of them in
 * the top left and the other in the bottom right.
 */
class TimeModule : Module("time", "Time", "Clock and session length", ModuleCategory.HUD, "clock", 8, 280, false) {

    private val formatter = DateTimeFormatter.ofPattern("HH:mm")
    private val startedAt = System.currentTimeMillis()

    private fun showClock() = flag("clock", true)
    private fun showSession() = flag("session", true)

    override val multiLine: Boolean
        get() = showClock() && showSession()

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Readouts"))
        list.add(BoolSetting("Clock", { flag("clock", true) }, { setFlag("clock", it) }))
        list.add(BoolSetting("Session length", { flag("session", true) }, { setFlag("session", it) }))
    }

    private fun sessionLength(): String {
        val totalSeconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = ArrayList<Pair<String, String>>(2)
        if (showClock()) rows.add("Time" to LocalTime.now().format(formatter))
        if (showSession()) rows.add("Session" to sessionLength())
        drawRows(client, graphics, rows)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = ArrayList<Pair<String, String>>(2)
        if (showClock()) rows.add("Time" to "21:04")
        if (showSession()) rows.add("Session" to "42:17")
        drawRows(client, graphics, rows)
    }

    private fun drawRows(client: Minecraft, graphics: GuiGraphics, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val gap = 6
        val lineH = font.lineHeight + 2 + st.spacing
        val labelW = rows.maxOf { font.width(it.first) }
        val valueW = rows.maxOf { font.width(it.second) }
        val w = pad + 4 + labelW + gap + valueW + pad
        val h = pad * 2 + rows.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val value = panelTextColor()
        val lx = x + pad + 4
        rows.forEachIndexed { i, row ->
            val ly = y + pad + i * lineH
            graphics.drawString(font, row.first, lx, ly, labelColor, st.textShadow)
            graphics.drawString(font, row.second, lx + labelW + gap, ly, value, st.textShadow)
        }
    }
}
