package dev.wyz.clientcore

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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen

object RiverRuntime {

    lateinit var config: ClientCoreConfig
        private set

    /** Config if [initialize] has run, else null. Lets hot paths avoid runCatching. */
    fun configOrNull(): ClientCoreConfig? = if (::config.isInitialized) config else null

    private var initialized = false
    private var configReloadTicker = 0

    fun initialize() {
        if (initialized) return
        initialized = true
        // Before River writes its config: whether this is an instance's first launch.
        dev.wyz.clientcore.perf.LowEndPreset.noteLaunch()
        config = ConfigService.load()
        // In October the Halloween theme is switched on once for anyone still on the default.
        if (dev.wyz.clientcore.ui.Seasons.offerOnce(config)) ConfigService.save(config)
        ModuleRegistry.bootstrap()
        ClientKeybinds.initialize()
        HudRenderer.initialize()
        WatermarkRenderer.initialize()
        runCatching { WaypointWorldRenderer.initialize() }
        //? if >=1.21.11 && <26.1 {
        // The Texture picker button on the vanilla resource pack screen.
        runCatching { dev.wyz.clientcore.resources.TextureMixer.install() }
        //?}
        /*
          Turns the preview data an item hands over into something that can draw itself.
          Registered once - the event is not built to be added to every frame.

          Fabric renamed this in 26.1; it is the same event in the same package either way.
        */
        /*
          Replaces vanilla's list of contents with a line saying how to see the grid. The
          list itself is cancelled in ContainerContentsTooltipMixin; this puts something
          useful where it was.
        */
        runCatching {
            net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
                dev.wyz.clientcore.ui.tooltip.ContainerTooltips.hintFor(stack)?.let { lines.add(it) }
            }
        }
        runCatching {
//? if >=26.1 {
/*            net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback.EVENT.register { data ->
                if (data is dev.wyz.clientcore.ui.tooltip.ShulkerTooltipData) {
                    dev.wyz.clientcore.ui.tooltip.ShulkerTooltipComponent(data)
                } else {
                    null
                }
            }
*///?} else {
            net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback.EVENT.register { data ->
                if (data is dev.wyz.clientcore.ui.tooltip.ShulkerTooltipData) {
                    dev.wyz.clientcore.ui.tooltip.ShulkerTooltipComponent(data)
                } else {
                    null
                }
            }
//?}
        }
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

    private var mixinAudited = false

    /**
     * Dev check, off unless -Driver.mixinAudit=true: makes Mixin apply every River mixin now
     * instead of when each class first loads, so a hook that no longer matches a version
     * fails on the title screen rather than in the middle of a game.
     */
    private fun mixinAuditOnce() {
        if (mixinAudited || System.getProperty("river.mixinAudit") != "true") return
        mixinAudited = true
        org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit()
        println("[river] mixin audit passed")
    }

    fun tick(client: Minecraft) {
        initialize()
        mixinAuditOnce()
        // Dev screenshots of River's screens; does nothing unless -Driver.devshot is set.
        dev.wyz.clientcore.dev.DevShot.tick(client)
        dev.wyz.clientcore.perf.LowEndPreset.tick(client)
        dev.wyz.clientcore.config.ServerProfiles.tick(client)
        ClientKeybinds.tick(client)
        TotemPopTracker.tick(client)
        dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.HitColorModule>("hit_color")?.sync(client)
        dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.FogModule>("fog")?.syncSaturation(client)
        ReconnectState.capture(client)
        ReconnectRuntime.tick(client)
        configReloadTicker += 1
        if (configReloadTicker >= 10) {
            configReloadTicker = 0
            ConfigService.reloadIfChanged()?.let { config = it }
        }
        PresenceService.update(client)
        // Friends presence has to tick while you play, not only while the menu is open,
        // or everyone actually in a game shows as offline to their friends.
        dev.wyz.clientcore.net.RiverSocial.tick(client)
        dev.wyz.clientcore.ui.SeasonEvent.tick(client)
        dev.wyz.clientcore.ui.FriendMessageToasts.tick(client)
        dev.wyz.clientcore.ui.tooltip.ContainerTooltips.tick(client)
        ModuleRegistry.tick(client)
    }

    fun renderHud(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        initialize()
        if (dev.wyz.clientcore.dev.DevShot.hudHidden) return
        HudRenderer.render(client, graphics, tickDelta)
        // Drawn after the HUD so a message is never hidden behind a module.
        dev.wyz.clientcore.ui.FriendMessageToasts.render(client, graphics)
    }

    @Suppress("UNUSED_PARAMETER")
    fun renderScreen(client: Minecraft, screen: Screen?, graphics: GuiGraphics, mouseX: Int, mouseY: Int, tickDelta: Float) {
        initialize()
        if (screen != null) dev.wyz.clientcore.ui.SeasonEvent.renderScreen(client, screen, graphics)
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

}
