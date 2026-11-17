package dev.wyz.clientcore.nametag

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=1.21.9 {
import net.minecraft.network.chat.FontDescription
//?} else {
/**///?}
import net.minecraft.network.chat.MutableComponent
import dev.wyz.clientcore.compat.McId
import java.util.UUID

object RiverBadgeState {
    /**
     * Every River player has the badge; there is no switch for it. The one exception is your own
     * cinematic mode: while it is on, nobody's badge is drawn on your screen. Other players still
     * see yours, because cinematic mode only changes what you see.
     */
    @JvmStatic
    fun visible(): Boolean = dev.wyz.clientcore.RiverRuntime.configOrNull()?.cinematicMode != true

    @JvmStatic
    fun shouldShow(uuid: UUID): Boolean {
        if (!visible()) return false
        val self = Minecraft.getInstance().player
        if (self != null && self.uuid == uuid) return true
        return EffectRoster.hasRemoteBadge(uuid)
    }

/*
  FontDescription arrived in 1.21.9, not 1.21.11 - before it, Style.withFont took the
  identifier directly. The identifier class itself is spelled differently again from
  1.21.11, which is what McId is for, so that rename does not need a second guard here.
*/
//? if >=1.21.9 {
    private val BADGE_FONT = FontDescription.Resource(
        McId.fromNamespaceAndPath("clientcore", "river_badge")
    )
//?} else {
/*    private val BADGE_FONT: McId = McId.fromNamespaceAndPath("clientcore", "river_badge")
*///?}

    /**
     * The real River logo, served by the force-enabled built-in resource pack
     * (see RiverBuiltinPack), so it renders in dev and under agent injection alike.
     */
    /**
     * The badge for a player: gold for River Plus (the server's answer, for others and for
     * you alike), orange during Halloween, otherwise the blue River logo. No uuid means you.
     */
    @JvmStatic
    @JvmOverloads
    fun badgeComponent(uuid: UUID? = null): MutableComponent {
        val self = Minecraft.getInstance().player?.uuid
        val plus = if (uuid == null || uuid == self) dev.wyz.clientcore.cosmetic.RiverPlus.active() else EffectRoster.hasRemotePlus(uuid)
        val glyph = when {
            plus -> "\uE002"
            dev.wyz.clientcore.ui.menu.SeasonalFx.halloween() -> "\uE001"
            else -> "\uE000"
        }
        return Component.literal(glyph).withStyle { style -> style.withFont(BADGE_FONT) }
    }
}
