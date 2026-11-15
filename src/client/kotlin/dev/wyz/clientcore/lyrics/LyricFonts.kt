package dev.wyz.clientcore.lyrics

import dev.wyz.clientcore.compat.McId
import net.minecraft.network.chat.Component
//? if >=1.21.9 {
import net.minecraft.network.chat.FontDescription
//?} else {
/**///?}

/**
 * The faces the 3D lyrics can use. Minecraft and Unifont are the game's own fonts; Exo 2
 * (Light) and Montserrat are TrueType fonts in assets/clientcore/font, each with a bold
 * cut for the word being sung. Exo 2 is also drawn stretched sideways (see [stretch]).
 */
object LyricFonts {
    val NAMES = listOf("Minecraft", "Unifont", "Exo 2", "Montserrat")
    const val WIDE = 2

/*
  FontDescription arrived in 1.21.9; before it Style.withFont took the identifier itself.
  Same split as RiverBadgeState.
*/
//? if >=1.21.9 {
    private fun font(namespace: String, path: String) = FontDescription.Resource(McId.fromNamespaceAndPath(namespace, path))
//?} else {
/*    private fun font(namespace: String, path: String): McId = McId.fromNamespaceAndPath(namespace, path)
*///?}

    private val SMOOTH = font("minecraft", "uniform")
    private val WIDE_REGULAR = font("clientcore", "lyrics_wide")
    private val WIDE_BOLD = font("clientcore", "lyrics_wide_bold")
    private val CLEAN_REGULAR = font("clientcore", "lyrics_clean")
    private val CLEAN_BOLD = font("clientcore", "lyrics_clean_bold")

    /** [text] in face [face] (an index into [NAMES]), in its bold cut when [bold]. */
    fun styled(text: String, face: Int, bold: Boolean): Component = Component.literal(text).withStyle { style ->
        when (face) {
            1 -> style.withFont(SMOOTH).withBold(bold)
            2 -> style.withFont(if (bold) WIDE_BOLD else WIDE_REGULAR)
            3 -> style.withFont(if (bold) CLEAN_BOLD else CLEAN_REGULAR)
            else -> style.withBold(bold)
        }
    }

    /** Exo 2 is drawn 1.3 times wider and a quarter smaller, so it reads as a broad display face. */
    fun stretch(face: Int): Float = if (face == WIDE) 1.3f else 1f
    fun sizeFactor(face: Int): Float = if (face == WIDE) 0.75f else 1f
}
