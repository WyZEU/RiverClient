package dev.wyz.clientcore.nametag

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import java.util.UUID

object RiverBadgeState {
    @JvmStatic
    fun shouldShow(uuid: UUID): Boolean {
        val self = Minecraft.getInstance().player
        if (self != null && self.uuid == uuid) return true
        return EffectRoster.hasRemoteBadge(uuid)
    }

    // 1.21.4's Style.withFont still takes a plain ResourceLocation (the structured
    // FontDescription wrapper is a later addition).
    private val BADGE_FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath("clientcore", "river_badge")

    /**
     * The real River logo, served by the force-enabled built-in resource pack
     * (see RiverBuiltinPack), so it renders in dev and under agent injection alike.
     */
    @JvmStatic
    fun badgeComponent(): MutableComponent =
        Component.literal("").withStyle { style -> style.withFont(BADGE_FONT) }
}
