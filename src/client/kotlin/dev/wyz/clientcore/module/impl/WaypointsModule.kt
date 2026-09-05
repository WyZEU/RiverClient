package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.config.WaypointData
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.screen.RiverWaypointsScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Client-side position markers, saved per server/world in your profile.
 * Rendered as in-world beams with floating name + distance labels — no HUD
 * element. Only marks spots you (or your death) explicitly set.
 */
class WaypointsModule : Module("waypoints", "Waypoints", "In-world position markers", ModuleCategory.UTILITY, "flag", 8, 406, false) {

    companion object {
        val COLORS = intArrayOf(
            0xFF6C8CFF.toInt(), // river blue
            0xFFB18CFF.toInt(), // purple
            0xFF72F1B8.toInt(), // green
            0xFFFFD46B.toInt(), // amber
            0xFFFF8FA3.toInt(), // pink
            0xFF7FE3FF.toInt()  // cyan
        )

        fun colorOf(waypoint: WaypointData): Int = COLORS[waypoint.color.mod(COLORS.size)]
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Open manager"

    private var wasDead = false

    override fun onKeybindPressed(client: Minecraft): Boolean {
        client.setScreen(RiverWaypointsScreen(client.screen))
        return true
    }

    fun storageKey(client: Minecraft): String? {
        if (client.level == null) return null
        val singleplayer = client.singleplayerServer
        if (singleplayer != null) {
            return "sp:" + singleplayer.worldData.levelName.lowercase()
        }
        return ServerSafety.currentServerKey(client)
    }

    fun waypointsHere(client: Minecraft): MutableList<WaypointData> {
        val key = storageKey(client) ?: return mutableListOf()
        return ClientCore.config.waypointsMap().getOrPut(key) { mutableListOf() }
    }

    fun currentDimension(client: Minecraft): String =
//? if >=1.21.11 {
        client.level?.dimension()?.identifier()?.toString() ?: "minecraft:overworld"
//?} else {
/*        client.level?.dimension()?.location()?.toString() ?: "minecraft:overworld"
*///?}

    /**
     * What actually renders in the world: this dimension's waypoints, minus hidden ones.
     * The manager deliberately uses [waypointsHere] instead, so a hidden waypoint is still
     * listed there and can be switched back on.
     */
    fun visibleWaypoints(client: Minecraft): List<WaypointData> {
        val dimension = currentDimension(client)
        return waypointsHere(client).filter { it.dimension == dimension && !it.hidden }
    }

    fun addHere(client: Minecraft, name: String) {
        val player = client.player ?: return
        val list = waypointsHere(client)
        list.add(WaypointData(
            name = name,
            x = player.blockX,
            y = player.blockY,
            z = player.blockZ,
            dimension = currentDimension(client),
            color = list.size
        ))
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        val client = Minecraft.getInstance()
        list.add(SectionSetting("Waypoints"))
        list.add(BoolSetting("World markers", { mutableWaypoints().showWorldMarkers }, { mutableWaypoints().showWorldMarkers = it }))
        list.add(BoolSetting("Death waypoints", { mutableWaypoints().deathWaypoints }, { mutableWaypoints().deathWaypoints = it }))
        if (client.level != null) {
            list.add(ActionSetting("Manage waypoints", "Open") {
                client.setScreen(RiverWaypointsScreen(client.screen))
            })
        }
    }

    override fun tick(client: Minecraft) {
        val player = client.player ?: run { wasDead = false; return }
        val dead = player.isDeadOrDying
        if (dead && !wasDead && effectiveWaypointsCfg().deathWaypoints) {
            val list = waypointsHere(client)
            list.removeAll { it.name == "Death" }
            list.add(WaypointData(
                name = "Death",
                x = player.blockX,
                y = player.blockY,
                z = player.blockZ,
                dimension = currentDimension(client),
                color = 4
            ))
        }
        wasDead = dead
    }
}
