package dev.wyz.clientcore.ui

import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * How River's screens style text beyond the plain game font: small capitals for labels,
 * bold for names and headings, and a gradient for the few titles that carry the accent.
 *
 * All of it is the game's own font. Small capitals are real Unicode letters (U+1D00 and
 * friends) that the default font already has glyphs for, so they cost nothing and work on
 * every version; bold and colour are ordinary text styles.
 */
object RiverText {

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"

    /** "Modules" -> small capitals. Anything that is not a letter is left alone. */
    fun small(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val i = LOWER.indexOf(ch.lowercaseChar())
            out.append(if (i >= 0) SMALL[i] else ch)
        }
        return out.toString()
    }

    private val BOLD: Style = Style.EMPTY.withBold(true)

    /*
      A HUD module's bold and small-caps settings. They are switched on only while that
      module draws (ModuleRegistry, the HUD editor and the menu preview set them), and the
      game's own string drawing and width checks read them (TextOrderCacheMixin,
      FontWidthMixin), so every module gets both without each one changing how it draws.
      Before 1.21.6 strings are drawn by a path those hooks do not see, so it stays off.
    */
    @JvmField var hudBold = false
    @JvmField var hudSmall = false

    @JvmStatic
    fun hudActive(): Boolean = hudBold || hudSmall

    private fun hudComponent(raw: String): Component {
        val text = if (hudSmall) small(raw) else raw
        return if (hudBold) Component.literal(text).withStyle(BOLD) else Component.literal(text)
    }

    @JvmStatic
    fun hudOrder(raw: String): net.minecraft.util.FormattedCharSequence = hudComponent(raw).visualOrderText

    @JvmStatic
    fun hudWidth(font: Font, raw: String): Int = font.width(hudComponent(raw))

    /** Runs [body] with a module's text style on, and always switches it back off. */
    inline fun <T> withHudStyle(bold: Boolean, small: Boolean, body: () -> T): T {
//? if >=1.21.6 {
        hudBold = bold
        hudSmall = small
//?}
        try {
            return body()
        } finally {
            hudBold = false
            hudSmall = false
        }
    }

    fun bold(text: String): Component = Component.literal(text).withStyle(BOLD)

    /** Width of [text] drawn bold: a bold glyph is a pixel wider. */
    fun boldWidth(font: Font, text: String): Int = font.width(bold(text))

    /** Bold text at [x], [y]. Returns its width so a caller can carry on from the end. */
    fun drawBold(g: GuiGraphics, font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean = true): Int {
        val component = bold(text)
        g.drawString(font, component, x, y, color, shadow)
        return font.width(component)
    }

    /** Bold, with the colour running from [from] to [to] across the letters. */
    fun drawBoldGradient(g: GuiGraphics, font: Font, text: String, x: Int, y: Int, from: Int, to: Int, shadow: Boolean = true): Int {
        var cursor = x
        val last = (text.length - 1).coerceAtLeast(1)
        text.forEachIndexed { index, ch ->
            val colour = ClientUi.mix(from, to, index.toFloat() / last.toFloat())
            val piece = Component.literal(ch.toString()).withStyle(BOLD)
            g.drawString(font, piece, cursor, y, colour or (0xFF shl 24), shadow)
            cursor += font.width(piece)
        }
        return cursor - x
    }
}
