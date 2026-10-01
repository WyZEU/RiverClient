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
    @JvmStatic
    fun shouldShow(uuid: UUID): Boolean {
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
    @JvmStatic
    fun badgeComponent(): MutableComponent =
        Component.literal("").withStyle { style -> style.withFont(BADGE_FONT) }
}
