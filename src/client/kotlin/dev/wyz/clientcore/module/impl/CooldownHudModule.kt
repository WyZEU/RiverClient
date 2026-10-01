package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.pvp.CooldownTracker
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Every item cooldown you are waiting on - ender pearl, wind charge, chorus fruit, a
 * disabled shield - as its icon, the seconds left and a bar running down. Nothing is
 * drawn while nothing is cooling down.
 */
class CooldownHudModule : Module("cooldowns", "Cooldowns", "Item cooldowns with the seconds left", ModuleCategory.HUD, "item:minecraft:ender_pearl", 8, 300, false) {

    private fun horizontal() = scalar("layout", 0) == 1
    private fun decimals() = flag("decimals", true)
    private fun showBar() = flag("bar", true)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Cooldowns"))
        list.add(ChoiceSetting("Layout", listOf("Vertical", "Horizontal"),
            { if (horizontal()) "Horizontal" else "Vertical" },
            { setScalar("layout", if (it == "Horizontal") 1 else 0) }))
        list.add(BoolSetting("Tenths of a second", { flag("decimals", true) }, { setFlag("decimals", it) }))
        list.add(BoolSetting("Progress bar", { flag("bar", true) }, { setFlag("bar", it) }))
    }

    private class Row(val stack: ItemStack, val left: Float, val seconds: Float)

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = CooldownTracker.active(tickDelta).map { (entry, left) ->
            Row(entry.stack, left, left * entry.durationTicks / 20f)
        }
        if (rows.isNotEmpty()) draw(client, graphics, rows)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, listOf(
            Row(ItemStack(Items.ENDER_PEARL), 0.6f, 0.6f),
            Row(ItemStack(Items.SHIELD), 0.84f, 4.2f)
        ))
    }

    private fun label(seconds: Float): String =
        if (decimals() && seconds < 10f) String.format(Locale.ROOT, "%.1fs", seconds)
        else "${ceil(seconds).toInt()}s"

    private fun draw(client: Minecraft, graphics: GuiGraphics, rows: List<Row>) {
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        // Sized for the widest label that can appear, so the panel does not twitch as
        // the numbers count down.
        val textW = font.width(if (decimals()) "0.0s" else "00s").coerceAtLeast(rows.maxOf { font.width(label(it.seconds)) })
        val bar = showBar()
        val textColor = panelTextColor()
        val barColor = panelAccentColor()
        val trackColor = 0x40FFFFFF

        if (horizontal()) {
            val cell = maxOf(16, textW)
            val cellH = 16 + 2 + font.lineHeight + if (bar) 3 else 0
            val w = pad * 2 + rows.size * cell + (rows.size - 1) * 6
            drawPillBackground(graphics, w, pad * 2 + cellH)
            rows.forEachIndexed { i, row ->
                val cx = x + pad + i * (cell + 6)
                val cy = y + pad
                graphics.renderItem(row.stack, cx + (cell - 16) / 2, cy)
                val text = label(row.seconds)
                graphics.drawString(font, text, cx + (cell - font.width(text)) / 2, cy + 18, textColor, st.textShadow)
                if (bar) drawBar(graphics, cx, cy + 18 + font.lineHeight + 1, cell, row.left, barColor, trackColor)
            }
        } else {
            val rowH = 18
            val w = pad * 2 + 16 + 4 + textW
            drawPillBackground(graphics, w, pad * 2 + rows.size * rowH - 2)
            rows.forEachIndexed { i, row ->
                val ry = y + pad + i * rowH
                graphics.renderItem(row.stack, x + pad, ry)
                val tx = x + pad + 20
                graphics.drawString(font, label(row.seconds), tx, ry + (if (bar) 2 else 4), textColor, st.textShadow)
                if (bar) drawBar(graphics, tx, ry + 12, textW, row.left, barColor, trackColor)
            }
        }
    }

    private fun drawBar(graphics: GuiGraphics, x: Int, y: Int, w: Int, left: Float, color: Int, track: Int) {
        graphics.fill(x, y, x + w, y + 2, track)
        val filled = (w * left.coerceIn(0f, 1f)).roundToInt()
        if (filled > 0) graphics.fill(x, y, x + filled, y + 2, color)
    }
}
