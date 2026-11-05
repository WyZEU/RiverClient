package dev.wyz.clientcore.nametag

import dev.wyz.clientcore.compat.McId
import net.minecraft.network.chat.Component
//? if >=1.21.9 {
import net.minecraft.network.chat.FontDescription
//?} else {
/**///?}
import net.minecraft.network.chat.MutableComponent

/**
 * One small item icon per MCTiers mode, drawn in front of the tier label - a sword for
 * Sword, a mace for Mace - so a tag says which mode it is without spelling it out.
 *
 * Same technique as the River badge: a bitmap font whose glyphs are textures, which is
 * the one way to put an image inside a nametag, a tab entry or a chat line on every
 * version River ships. The glyphs point straight at vanilla's own item textures, so
 * nothing of Mojang's is copied into our jar and every icon matches the resource pack
 * the player is actually using.
 */
object TierIcons {

//? if >=1.21.9 {
    private val FONT = FontDescription.Resource(McId.fromNamespaceAndPath("clientcore", "tiers"))
//?} else {
/*    private val FONT: McId = McId.fromNamespaceAndPath("clientcore", "tiers")
*///?}

    /** Mode key (as the MCTiers API spells it) to its glyph in font/tiers.json. */
    private val GLYPHS = mapOf(
        "vanilla" to "",  // end crystal - vanilla here means crystal PvP
        "sword" to "",
        "axe" to "",
        "pot" to "",      // splash potion
        "nethop" to "",   // netherite helmet
        "smp" to "",      // totem of undying
        "uhc" to "",      // golden apple
        "mace" to ""
    )

    /** The icon for [mode], or null for a mode this client does not know yet. */
    fun icon(mode: String): MutableComponent? {
        val glyph = GLYPHS[mode] ?: return null
        return Component.literal(glyph).withStyle { style -> style.withFont(FONT) }
    }
}
