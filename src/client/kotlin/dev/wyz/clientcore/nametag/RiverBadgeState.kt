package dev.wyz.clientcore.nametag

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import java.util.UUID

object RiverBadgeState {
    @JvmStatic
    fun shouldShow(uuid: UUID): Boolean {
        val self = Minecraft.getInstance().player
        if (self != null && self.uuid == uuid) return true
        return EffectRoster.hasRemoteBadge(uuid)
    }

    private val BADGE_FONT = FontDescription.Resource(
        Identifier.fromNamespaceAndPath("clientcore", "river_badge")
    )

    /**
     * The real River logo, served by the force-enabled built-in resource pack
     * (see RiverBuiltinPack), so it renders in dev and under agent injection alike.
     */
    @JvmStatic
    fun badgeComponent(): MutableComponent =
        Component.literal("").withStyle { style -> style.withFont(BADGE_FONT) }
}
