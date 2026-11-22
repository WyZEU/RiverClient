package dev.wyz.clientcore.cosmetic

import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.nametag.EffectRoster
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

object WingCosmetics {
    data class Style(val slot: Int, val id: String, val name: String) {
        val texture = McId.fromNamespaceAndPath("clientcore", "${WingMesh.DIRECTORY}/$id.png")
        val preview = McId.fromNamespaceAndPath("clientcore", "${WingMesh.DIRECTORY}/$id-preview.png")
        val mesh by lazy { WingMesh.load(id) }
    }

    val styles = listOf(
        Style(1, "wingemberdragon", "Ember Dragon Wings"),
        Style(2, "wingverdantbloom", "Verdant Bloom Wings"),
        Style(3, "wingvoidshard", "Voidshard Wings"),
        Style(4, "wingvampirelord", "Vampire Lord Wings"),
        Style(5, "wingriverregalia", "River Regalia Wings")
    )

    private val byId = styles.associateBy { it.id }
    private val bySlot = styles.associateBy { it.slot }
    private val devWing = System.getProperty("river.dev.wing")
        ?.takeIf { FabricLoader.getInstance().isDevelopmentEnvironment }
        ?.let(byId::get)

    fun defaultSlot(): Int = devWing?.slot ?: 0

    fun owns(style: Style): Boolean =
        style == devWing || CapeEntitlements.ownsWing(style.id) ||
            (style.id == "wingriverregalia" && RiverPlus.active())

    fun owned(): List<Style> = styles.filter(::owns)

    fun refreshEquipped() {
        if ((ModuleRegistry.get<CapeModule>("cape")?.wingSlot() ?: 0) != 0) {
            CapeEntitlements.refreshWingsIfStale()
        }
    }

    fun equipped(): Style? =
        ModuleRegistry.get<CapeModule>("cape")?.wingSlot()?.let(bySlot::get)?.takeIf(::owns)

    @JvmStatic
    fun forPlayer(player: Player): Style? =
        if (player == Minecraft.getInstance().player) equipped()
        else EffectRoster.remoteWingStyle(player.uuid)?.let(byId::get)
}
