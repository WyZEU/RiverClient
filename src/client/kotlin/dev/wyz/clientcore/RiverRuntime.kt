package dev.wyz.clientcore

import dev.wyz.clientcore.bridge.DesktopBridge
import dev.wyz.clientcore.config.ClientCoreConfig
import dev.wyz.clientcore.config.ConfigService
import dev.wyz.clientcore.hud.HudRenderer
import dev.wyz.clientcore.hud.WatermarkRenderer
import dev.wyz.clientcore.input.ClientKeybinds
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.net.PresenceService
import dev.wyz.clientcore.net.ReconnectRuntime
import dev.wyz.clientcore.net.ReconnectState
import dev.wyz.clientcore.pvp.TotemPopTracker
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.world.WaypointWorldRenderer
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import java.util.Base64

object RiverRuntime {

    lateinit var config: ClientCoreConfig
        private set

    /** Config if [initialize] has run, else null. Lets hot paths avoid runCatching. */
    fun configOrNull(): ClientCoreConfig? = if (::config.isInitialized) config else null

    private var initialized = false
    private var configReloadTicker = 0
    private var lastPresenceServerKey: String? = null
    private var lastPresenceIconHash: Int? = null
    private var lastPresenceIconBase64: String? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        config = ConfigService.load()
        ModuleRegistry.bootstrap()
        ClientKeybinds.initialize()
        HudRenderer.initialize()
        WatermarkRenderer.initialize()
        runCatching { WaypointWorldRenderer.initialize() }
    }

    fun saveConfig() {
        if (!initialized) return
        ConfigService.save(config)
    }

    /** Saves the current profile and activates [name]. Returns true on success. */
    fun switchProfile(name: String): Boolean {
        if (!initialized) return false
        val next = ConfigService.switchProfile(name, config) ?: return false
        config = next
        return true
    }

    fun tick(client: Minecraft) {
        initialize()
        ClientKeybinds.tick(client)
        if (client.level == null) {
            TotemPopTracker.clear()
        }
        ReconnectState.capture(client)
        ReconnectRuntime.tick(client)
        configReloadTicker += 1
        if (configReloadTicker >= 10) {
            configReloadTicker = 0
            ConfigService.reloadIfChanged()?.let { config = it }
        }
        pushDesktopPresenceState(client)
        PresenceService.update(client)
        // Friends presence has to tick while you play, not only while the menu is open,
        // or everyone actually in a game shows as offline to their friends.
        dev.wyz.clientcore.net.RiverSocial.tick(client)
        dev.wyz.clientcore.ui.FriendMessageToasts.tick(client)
        ModuleRegistry.tick(client)
    }

    fun renderHud(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        initialize()
        HudRenderer.render(client, graphics, tickDelta)
    }

    @Suppress("UNUSED_PARAMETER")
    fun renderScreen(client: Minecraft, screen: Screen?, graphics: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) {
        initialize()
        // Watermark shows over regular menus (pause, inventory, ...) but never on the
        // main menu, River's own screens, or the multiplayer server list - there its
        // top-right corner overlaps server pings and other clients' overlays.
        if (screen == null || screen is RiverScreen || screen is TitleScreen || screen is JoinMultiplayerScreen) return
        WatermarkRenderer.renderOverlay(client, graphics)
    }

    fun shutdown() {
        if (!initialized) return
        PresenceService.stop()
        ConfigService.save(config)
    }

    private fun pushDesktopPresenceState(client: Minecraft) {
        val screen = client.screen
        val currentServer = client.currentServer
        when {
            currentServer != null && client.level != null -> {
                val serverIconBytes = runCatching { currentServer.getIconBytes() }.getOrNull()
                val serverKey = "${currentServer.name}|${currentServer.ip}"
                val iconHash = serverIconBytes?.contentHashCode()
                val iconBase64 = if (serverKey == lastPresenceServerKey && iconHash == lastPresenceIconHash) {
                    lastPresenceIconBase64
                } else {
                    serverIconBytes?.let { Base64.getEncoder().encodeToString(it) }.also {
                        lastPresenceServerKey = serverKey
                        lastPresenceIconHash = iconHash
                        lastPresenceIconBase64 = it
                    }
                }
                DesktopBridge.pushPresenceState(
                    state = "playing_server",
                    serverName = currentServer.name,
                    serverAddress = currentServer.ip,
                    serverIconBase64 = iconBase64
                )
            }
            screen is JoinMultiplayerScreen -> DesktopBridge.pushPresenceState("browsing_server_list")
            screen is TitleScreen || (client.level == null && screen != null) -> DesktopBridge.pushPresenceState("in_main_menu")
            screen is PauseScreen -> DesktopBridge.pushPresenceState("in_game")
            client.level != null -> DesktopBridge.pushPresenceState("in_game")
            else -> DesktopBridge.pushPresenceState("in_launcher")
        }
    }
}
