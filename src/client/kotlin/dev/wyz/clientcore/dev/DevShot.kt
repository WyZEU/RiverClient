package dev.wyz.clientcore.dev

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.net.RiverSocial
import dev.wyz.clientcore.ui.screen.RiverFriendsScreen
import dev.wyz.clientcore.ui.screen.RiverHudEditorScreen
import dev.wyz.clientcore.ui.screen.RiverMenuScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
//? if >=26.3 {
/*import dev.wyz.clientcore.compat.GLFW
*///?} else {
import org.lwjgl.glfw.GLFW
//?}

/**
 * Dev screenshots of River's own screens, so a UI change can be looked at instead of
 * guessed at. Off unless the game is started with -Driver.devshot=<shots>; a normal game
 * never reaches anything in here.
 *
 * Each shot is `name[:halloween][@WIDTHxHEIGHT]`, comma separated, for example
 * `friends-chat,friends-chat:halloween,friends-chat@1366x768`. River caps its own screens
 * to a ~960x540 layout, so the window size is what changes how they look. The window is
 * created hidden and never goes full screen (DevShotWindowMixin, DevShotOptionsMixin); the
 * run waits for the title screen, opens each screen over sample data (RiverSocial preview),
 * saves `screenshots/devshot-<shot>.png` in the run folder, and quits.
 *
 * Version-sensitive calls go through reflection on purpose: this only ever runs in the
 * dev client, and it must not put a version conditional into every build.
 */
object DevShot {

    private val shots: List<String> = System.getProperty("river.devshot").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /*
      Benchmark (-Driver.bench=<seconds>): the same hidden window, a world instead of
      screens. It opens -Driver.bench.world (default "New World"), lets it warm up, uncaps
      the frame rate in memory only (these runs never save options), records a Java Flight
      Recording of the measured stretch next to plain frame-rate figures in bench/, and quits.
    */
    private val benchSeconds: Int? = System.getProperty("river.bench")?.toIntOrNull()
    private val benchWorld: String = System.getProperty("river.bench.world") ?: "New World"

    val active: Boolean get() = shots.isNotEmpty() || benchSeconds != null

    private val size = System.getProperty("river.devshot.size").orEmpty()
        .split('x').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 } ?: listOf(1920, 1080)

    private var index = -1
    private var shotWorldRequested = false
    private var shotWorldAt = 0L
    private var shotWorldLoggedAt = 0L

    private fun worldReady(client: Minecraft): Boolean {
        if (client.level == null || client.player == null || loadingOverlay(client)) {
            if (!shotWorldRequested && client.level == null && client.screen != null && !loadingOverlay(client)) {
                shotWorldRequested = true
                openWorld(client, benchWorld)
            }
            shotWorldAt = 0L
            // A copied world asks about a backup, then to confirm the upgrade; say yes to both.
            val prompt = client.screen
            val buttonIndex = when (prompt?.javaClass?.simpleName) {
                "BackupConfirmScreen" -> 1
                "ConfirmScreen" -> 0
                else -> -1
            }
            if (prompt != null && buttonIndex >= 0) {
                prompt.children().filterIsInstance<net.minecraft.client.gui.components.Button>().getOrNull(buttonIndex)?.let { button ->
                    button.javaClass.methods.firstOrNull { it.name == "onPress" }?.let { it.invoke(button, *arrayOfNulls<Any>(it.parameterCount)) }
                }
            }
            val waited = System.currentTimeMillis()
            if (waited - shotWorldLoggedAt > 10_000L) {
                shotWorldLoggedAt = waited
                println("[river] devshot waiting for the world on ${client.screen?.javaClass?.simpleName}")
            }
            return false
        }
        if (shotWorldAt == 0L) shotWorldAt = System.currentTimeMillis()
        return System.currentTimeMillis() - shotWorldAt > 6000L
    }
    private var done = false
    private var settleUntil = 0L
    private var grabAt = 0L
    private var nextAt = 0L
    @Volatile private var capturing = false
    private var windowPrepared = false
    private var themeSaved = false
    private var originalTheme: String? = null

    fun tick(client: Minecraft) {
        if (!active || done) return
        if (!windowPrepared) windowPrepared = prepareWindow(client, size[0], size[1])
        if (mutedFrom == null) mutedFrom = setMasterVolume(client, 0.0) ?: Double.NaN
        benchSeconds?.let { bench(client, it); return }
        // Shots named world-* are taken inside a world (glass, the HUD), so one is opened first.
        if (shots.any { it.startsWith("world-") } && !worldReady(client)) return
        if (loadingOverlay(client) || (client.screen == null && client.level == null)) {
            settleUntil = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (settleUntil == 0L) {
            settleUntil = now + 2500
            return
        }
        if (now < settleUntil || capturing) return

        // One step at a time: open a shot, give it time to settle, capture it, and only move
        // on once the capture has been written (it is read back from the GPU asynchronously).
        if (index >= 0 && grabAt != 0L) {
            if (shots[index].startsWith("world-media-controls") && !mediaControls(client, now)) return
            if (now < grabAt) return
            if (recording(shots[index]) && !record(client, shots[index])) return
            grabAt = 0L
            if (recording(shots[index])) { nextAt = now + 400; return }
            capture(client, shots[index])
            nextAt = now + 400
            return
        }
        if (now < nextAt) return
        index++
        if (index >= shots.size) {
            done = true
            finish(client)
            return
        }
        open(client, shots[index])
        grabAt = now + if (shots[index].startsWith("world-media-live") || shots[index].startsWith("world-media-lyrics-only")) 30_000 else 4500
    }

    private fun open(client: Minecraft, spec: String) {
        val name = spec.substringBefore('@').substringBefore(':')
        val halloween = spec.substringBefore('@').contains(":halloween")
        val windowSize = spec.substringAfter('@', "").split('x').mapNotNull { it.toIntOrNull() }

        val config = RiverRuntime.config
        if (!themeSaved) { originalTheme = config.theme; themeSaved = true }
        config.theme = if (halloween) "halloween" else "river"
        System.getProperty("river.devshot.ui")?.let { config.interfaceStyle = it }
        System.getProperty("river.devshot.accent")?.let { config.theme = it }
        if (windowSize.size == 2) prepareWindow(client, windowSize[0], windowSize[1])
        else prepareWindow(client, size[0], size[1])

        // "world-mod-<id>": the River menu with that module's settings open.
        if (name.startsWith("world-mod-")) {
            client.setScreen(RiverMenuScreen(null, dev.wyz.clientcore.module.ModuleRegistry.get(name.removePrefix("world-mod-"))))
            return
        }
        // A functional smoke pass in an isolated test world; no synthetic song or lyrics.
        if (name.startsWith("world-audit-")) {
            val registry = dev.wyz.clientcore.module.ModuleRegistry
            val module = registry.get<dev.wyz.clientcore.module.Module>(name.removePrefix("world-audit-")) ?: error("Unknown audit module")
            registry.all.forEach { it.enabled = false }
            module.enabled = true
            val settings = module.settings()
            settings.forEach { setting ->
                when (setting) {
                    is dev.wyz.clientcore.module.settings.BoolSetting -> { val old = setting.get(); setting.set(!old); setting.set(old) }
                    is dev.wyz.clientcore.module.settings.IntSetting -> { val old = setting.get(); setting.set(setting.min); setting.set(setting.max); setting.set(old) }
                    is dev.wyz.clientcore.module.settings.ChoiceSetting -> { val old = setting.get(); setting.options.forEach(setting.set); setting.set(old) }
                    is dev.wyz.clientcore.module.settings.ColorSetting -> { val old = setting.get(); setting.set(0xFF478AC9.toInt()); setting.set(old) }
                    is dev.wyz.clientcore.module.settings.KeybindSetting -> { val old = setting.get(); setting.set(-1); setting.set(old) }
                    else -> Unit // Actions can delete content or send messages; this pass does not invoke them.
                }
            }
            dev.wyz.clientcore.config.ConfigService.save(config)
            check(dev.wyz.clientcore.config.ConfigService.load().modules[module.id] == config.modules[module.id]) { "Profile roundtrip failed: ${module.id}" }
            println("[river] module audit ${module.id}: ${settings.size} settings, enabled=${module.active}, profile roundtrip passed")
            client.setScreen(RiverMenuScreen(null, module))
            return
        }
        when (name) {
            "world-media-live", "world-media-lyrics-only", "world-media-controls" -> {
                dev.wyz.clientcore.module.ModuleRegistry.all.forEach {
                    it.enabled = it.id == "lyrics" || (it.id == "now_playing" && name != "world-media-lyrics-only")
                }
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")?.settings()
                    ?.filterIsInstance<dev.wyz.clientcore.module.settings.BoolSetting>()
                    ?.filter { it.label == "On screen" || it.label == "In the world" }?.forEach { it.set(true) }
                // Keep a whole real line in view so the capture also exercises world text.
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")?.settings()
                    ?.filterIsInstance<dev.wyz.clientcore.module.settings.ChoiceSetting>()?.forEach {
                        if (it.label == "Show") it.set("Lines")
                        if (it.label == "Layout") it.set("Center")
                    }
                if (name == "world-media-controls") {
                    mediaStep = 0
                    mediaStepAt = System.currentTimeMillis()
                    dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.NowPlayingModule>("now_playing")?.editorSetScalePercent(150)
                    client.setScreen(net.minecraft.client.gui.screens.PauseScreen(true))
                } else client.setScreen(null)
            }
            // Opened from the title screen, which is where a missing item component crashed 26.1.2.
            "river-menu" -> client.setScreen(RiverMenuScreen(null))
            "river-hud" -> client.setScreen(RiverHudEditorScreen(null))
            "friends-signedout" -> {
                RiverSocial.stopPreview()
                RiverSocial.previewSignedOut = true
                client.setScreen(RiverFriendsScreen(null))
            }
            "friends-error" -> {
                RiverSocial.stopPreview("Could not get your Minecraft certificate from Mojang.")
                RiverSocial.previewSignedOut = true
                client.setScreen(RiverFriendsScreen(null))
            }
            "friends-empty" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.startEmpty()
                client.setScreen(RiverFriendsScreen(null))
            }
            "friends-home" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                client.setScreen(RiverFriendsScreen(null))
            }
            "friends-requests" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                client.setScreen(RiverFriendsScreen(null, initialTab = "requests"))
            }
            "friends-blocked" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                client.setScreen(RiverFriendsScreen(null, initialTab = "blocked"))
            }
            "friends-newchat" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                client.setScreen(RiverFriendsScreen(null, initialFriend = PreviewData.QUIET_FRIEND))
            }
            "world-style" -> client.setScreen(RiverMenuScreen(null, dev.wyz.clientcore.module.ModuleRegistry.get("stats")))
            "world-theme" -> client.setScreen(RiverMenuScreen(null).also { it.showSettingsPage() })
            "world-keys" -> client.setScreen(RiverMenuScreen(null).also { it.showKeysPage() })
            "world-settings-timechanger" -> client.setScreen(RiverMenuScreen(null, dev.wyz.clientcore.module.ModuleRegistry.get("time_changer")))
            "world-settings-sky" -> client.setScreen(RiverMenuScreen(null, dev.wyz.clientcore.module.ModuleRegistry.get("sky_effects")))
            "world-settings-lyrics" -> client.setScreen(RiverMenuScreen(null, dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")))
            "world-hudeditor-selected" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverHudEditorScreen(null).also {
                it.selectForDevShot(dev.wyz.clientcore.module.ModuleRegistry.get("coordinates"))
            })
            "world-hudeditor" -> {
                listOf("stats", "coordinates", "keystrokes", "armor_hud").forEach { id ->
                    dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.Module>(id)?.styleForDevShot()?.apply {
                        showBackground = true; glass = true; backgroundOpacity = 150; showBorder = true; borderOpacity = 90
                        cornerRadius = 4; boldText = true; smallCaps = id == "coordinates"
                    }
                }
                client.setScreen(dev.wyz.clientcore.ui.screen.RiverHudEditorScreen(null))
            }
            "cosmetics" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverCosmeticsScreen(null))
            "content" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverContentBrowserScreen(null))
            "texture-picker" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverTexturePickerScreen(null))
            "texture-search" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverTexturePickerScreen(null, "planks"))
            "texture-diamond" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverTexturePickerScreen(null, "diamond"))
            //? if >=1.21.11 && <26.1 {
            "packs-vanilla" -> client.setScreen(
                net.minecraft.client.gui.screens.packs.PackSelectionScreen(
                    client.resourcePackRepository, {}, client.resourcePackDirectory, Component.translatable("resourcePack.title")
                )
            )
            //?}
            // A made-up song's lyrics hanging in the world, with no player or network needed.
            "world-lyrics", "world-lyrics-lines", "world-lyrics-screen" -> {
                client.setScreen(null)
                if (recordSeconds != null) startRecording(client)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")
                    ?.startDemo(lines = name == "world-lyrics-lines", screen = name == "world-lyrics-screen", song = System.getProperty("river.devshot.song"))
            }
            // The aurora and shooting stars forced on, with the view tilted up toward them.
            "world-sky", "world-sky-up", "world-sky-south" -> {
                client.setScreen(null)
                client.player?.connection?.sendCommand("time set midnight")
                client.player?.setYRot(if (name == "world-sky-south") 0f else 180f)
                client.player?.setXRot(if (name == "world-sky-up") -70f else -25f)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.SkyEffectsModule>("sky_effects")?.startDemo()
                if (recordSeconds != null) startRecording(client, night = true)
            }
            // Time Changer sweeping from late morning to midnight over the recording, facing west so
            // the sun sets in view and the aurora comes out after it.
            "world-timesweep" -> {
                client.setScreen(null)
                client.player?.setYRot(90f)
                client.player?.setXRot(-18f)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.SkyEffectsModule>("sky_effects")?.enableForDevShot()
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.TimeChangerModule>("time_changer")?.let {
                    it.setTimeForDevShot(SWEEP_FROM)
                    it.enabled = true
                }
                if (recordSeconds != null) startRecording(client, night = true)
            }
            // Takes the Game HUD shot's scoreboard and boss bar back out of the test world.
            // Time Changer set to midnight; the world itself stays at its real time.
            "world-timechanger" -> {
                client.setScreen(null)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.TimeChangerModule>("time_changer")?.let {
                    it.setNightForDevShot()
                    it.enabled = true
                }
            }
            "world-clean" -> {
                client.setScreen(null)
                listOf("scoreboard objectives remove rv", "bossbar remove river:shot", "title @a clear")
                    .forEach { client.player?.connection?.sendCommand(it) }
            }
            // Game HUD settings on the real title, action bar and boss bar.
            "world-gamehud" -> {
                client.setScreen(null)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.GameHudModule>("game_hud")?.startDemo()
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.ScoreboardModule>("scoreboard")?.startDemo()
                listOf(
                    "scoreboard objectives add rv dummy \"River\"",
                    "scoreboard objectives setdisplay sidebar rv",
                    "scoreboard players set Sidebar rv 1",
                    "title @s times 0 600 0",
                    "title @s subtitle \"River subtitle\"",
                    "title @s title \"River\"",
                    "title @s actionbar \"River action bar\"",
                    "bossbar add river:shot \"River boss bar\"",
                    "bossbar set river:shot players @s"
                ).forEach { client.player?.connection?.sendCommand(it) }
            }
            // Parser and the real lookup, printed to the log: [river] lyrics ...
            "lyrics-check" -> {
                val sample = listOf(
                    "[ar:x]", "[offset:+100]", "[00:12.30] <00:12.30>First <00:12.80>line",
                    "[00:15.5][01:20.123] Chorus", "[00:20.00]", "[01:00:50] colon form"
                ).joinToString(System.lineSeparator())
                println("[river] lyrics parse " + dev.wyz.clientcore.lyrics.LrcParser.parse(sample).joinToString(" | ") { "${it.timeMs}='${it.text}'" })
                println("[river] lyrics index " + dev.wyz.clientcore.lyrics.LrcParser.indexAt(
                    dev.wyz.clientcore.lyrics.LrcParser.parse(sample), 16000))
                println("[river] lyrics clean '" + dev.wyz.clientcore.lyrics.LyricsService.cleanTitle("Song (feat. X) - Remastered 2011") + "'")
                Thread {
                    var state: dev.wyz.clientcore.lyrics.LyricsState = dev.wyz.clientcore.lyrics.LyricsService.stateFor(
                        "Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213)
                    var waited = 0
                    while (state is dev.wyz.clientcore.lyrics.LyricsState.Loading && waited < 20000) {
                        Thread.sleep(250); waited += 250
                        state = dev.wyz.clientcore.lyrics.LyricsService.stateFor("Never Gonna Give You Up", "Rick Astley", "Whenever You Need Somebody", 213)
                    }
                    val ready = state as? dev.wyz.clientcore.lyrics.LyricsState.Ready
                    println("[river] lyrics fetch " + (ready?.let { "ready ${it.lines.size} lines, first='${it.lines.firstOrNull { l -> l.text.isNotBlank() }?.text}' at ${it.lines.firstOrNull { l -> l.text.isNotBlank() }?.timeMs}ms, word-timed=${it.lines.count { l -> l.words != null }}, words=${it.lines.getOrNull(2)?.words?.joinToString(" ") { w -> "${w.text}@${w.startMs}+${w.durationMs}" }}" } ?: state::class.simpleName))
                    val cleaned = dev.wyz.clientcore.lyrics.LyricsService.stateFor("Never Gonna Give You Up - Remastered 2022", "Rick Astley", "", 0)
                    Thread.sleep(6000)
                    val again = dev.wyz.clientcore.lyrics.LyricsService.stateFor("Never Gonna Give You Up - Remastered 2022", "Rick Astley", "", 0)
                    println("[river] lyrics search-only " + (again as? dev.wyz.clientcore.lyrics.LyricsState.Ready)?.let { "ready ${it.lines.size} lines" } + " first=" + cleaned::class.simpleName)
                }.start()
            }
            "world-cosmetics" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverCosmeticsScreen(null))
            "world-content" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverContentBrowserScreen(null))
            "servers" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverServerBrowserScreen(null))
            "multiplayer" -> client.setScreen(net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(net.minecraft.client.gui.screens.TitleScreen()))
            "world-hud" -> {
                client.setScreen(null)
                dev.wyz.clientcore.ui.FriendMessageToasts.devPush("f-axie", "ItzAxie", "you on? we need one more for bedwars", 300)
            }
            // Three modules pushed together, to see their panels join: Coordinates under Stats, Time beside it.
            "world-join" -> {
                client.setScreen(null)
                val reg = dev.wyz.clientcore.module.ModuleRegistry
                val sw = client.window.guiScaledWidth
                val sh = client.window.guiScaledHeight
                val stats = reg.get<dev.wyz.clientcore.module.Module>("stats")
                val coords = reg.get<dev.wyz.clientcore.module.Module>("coordinates")
                val time = reg.get<dev.wyz.clientcore.module.Module>("time")
                listOfNotNull(stats, coords, time).forEach { m ->
                    m.enabled = true
                    (m.settings().firstOrNull { it is dev.wyz.clientcore.module.settings.ChoiceSetting && it.label == "Preset" }
                        as? dev.wyz.clientcore.module.settings.ChoiceSetting)?.set?.invoke("Glass")
                }
                if (stats != null && coords != null && time != null) {
                    stats.x = 40; stats.y = 40; stats.placed = true; stats.captureAnchor(client, sw, sh)
                    val (w, h) = stats.editorApproximateSize(client)
                    coords.x = 40; coords.y = 40 + h; coords.placed = true; coords.captureAnchor(client, sw, sh)
                    time.x = 40 + w; time.y = 40; time.placed = true; time.captureAnchor(client, sw, sh)
                }
            }
            "world-menu" -> client.setScreen(RiverMenuScreen(null))
            "world-crosshair" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverCrosshairEditorScreen(null))
            "world-waypoints" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverWaypointsScreen(null))
            "world-macros" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverMacrosScreen(null))
            "title" -> client.setScreen(net.minecraft.client.gui.screens.TitleScreen())
            // The menu slideshow caught halfway through a crossfade (the capture comes 4.5 s later).
            "title-fade" -> {
                client.setScreen(net.minecraft.client.gui.screens.TitleScreen())
                dev.wyz.clientcore.ui.menu.MenuSlides.ageForDevShot(12_000L - 5_300L)
            }
            // The menu slideshow recorded, 2.5x faster than real so a reel sees several pictures.
            "title-slides" -> {
                client.setScreen(net.minecraft.client.gui.screens.TitleScreen())
                if (recordSeconds != null) startRecording(client)
            }
            "world-inventory" -> {
                dev.wyz.clientcore.ClientCore.config.inventoryButtonList().apply { clear(); add("Pets=/pets"); add("/wardrobe"); add("Warp=/warp hub") }
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.InventoryToolsModule>("inventory_tools")?.enabled = true
                client.player?.let { client.setScreen(net.minecraft.client.gui.screens.inventory.InventoryScreen(it)) }
            }
            "event-title" -> {
                dev.wyz.clientcore.ui.SeasonEvent.devPreview(4 * 3_600_000L + 18 * 60_000L, done = false)
                client.setScreen(net.minecraft.client.gui.screens.TitleScreen())
            }
            "event-title-done" -> {
                dev.wyz.clientcore.ui.SeasonEvent.devPreview(10 * 3_600_000L, done = true)
                client.setScreen(net.minecraft.client.gui.screens.TitleScreen())
            }
            "event-pause" -> {
                dev.wyz.clientcore.ui.SeasonEvent.devPreview(4 * 3_600_000L + 18 * 60_000L, done = false)
                client.setScreen(PausePreview())
            }
            "toasts" -> {
                PreviewData.start()
                client.setScreen(ToastPreview())
                dev.wyz.clientcore.ui.FriendMessageToasts.devPush("f-axie", "ItzAxie", "you on? we need one more for bedwars", 600)
                dev.wyz.clientcore.ui.FriendMessageToasts.devPush(PreviewData.CHAT_FRIEND, "Galaxysdc", "party is open, join me on hypixel", 1600)
            }
            "friends-menu" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                val screen = RiverFriendsScreen(null, initialFriend = PreviewData.CHAT_FRIEND)
                client.setScreen(screen)
                screen.preview(armedMenu = "remove")
            }
            "friends-add" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                val screen = RiverFriendsScreen(null)
                client.setScreen(screen)
                screen.preview(searchText = "Technoblade")
            }
            // What a blacklisted account sees: the server's refusal as the sign-in error...
            "friends-account-blocked" -> {
                RiverSocial.stopPreview("This account can't use River Client.", blocked = true)
                RiverSocial.previewSignedOut = true
                client.setScreen(RiverFriendsScreen(null))
            }
            // ...and what a friend sees when messaging that account.
            "friends-dm-blocked" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                val screen = RiverFriendsScreen(null, initialFriend = PreviewData.CHAT_FRIEND)
                client.setScreen(screen)
                screen.preview(
                    noticeText = "Galaxysdc has been blacklisted from River Client. Ask the owner why.",
                    noticeIsError = true
                )
            }
            "friends-notice" -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                val screen = RiverFriendsScreen(null, initialFriend = PreviewData.CHAT_FRIEND)
                client.setScreen(screen)
                screen.preview(noticeText = "Copied play.hypixel.net")
            }
            else -> {
                RiverSocial.previewSignedOut = false
                PreviewData.start()
                client.setScreen(RiverFriendsScreen(null, initialFriend = PreviewData.CHAT_FRIEND))
            }
        }
    }

    /*
      Recording (-Driver.devshot.record=<seconds>): a world-lyrics* shot becomes a run of
      frames, screenshots/rec-<shot>/f00000.png..., at 30 per second of song. DevClock is
      stepped one frame at a time and each frame is only taken after the game has drawn it,
      so the result is smooth however slow the capture is. The world is frozen, only the
      lyrics HUD draws, and the view turns slowly.
    */
    private val recordSeconds: Double? = System.getProperty("river.devshot.record")?.toDoubleOrNull()
    private const val REC_FPS = 30
    private var recFrame = 0
    private var recDrawn = false
    private var recYaw = 0f
    private var recPitch = -8f
    private var lyricsWereOn: Boolean? = null

    private fun recording(spec: String) = recordSeconds != null && (spec.startsWith("world-lyrics") || spec.startsWith("world-sky") || spec == "world-timesweep" || spec == "title-slides")
    private const val SWEEP_FROM = 4000
    private const val SWEEP_TO = 18000

    private fun startRecording(client: Minecraft, night: Boolean = false) {
        DevClock.fixedMs = System.currentTimeMillis()
        hudOnly = if (night) "sky_effects" else "lyrics"
        // A sky recording shows only the sky: the lyrics of whatever is playing stay out of it.
        if (night) dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")?.let { lyricsWereOn = it.enabled; it.enabled = false }
        recFrame = 0
        recDrawn = false
        client.player?.connection?.sendCommand("tick freeze")
        client.player?.connection?.sendCommand(if (night) "time set midnight" else "time set 6000")
        recYaw = client.player?.yRot ?: 0f
        recPitch = if (night) client.player?.xRot ?: -25f else -8f
    }

    /** One step of a recording; true once every frame is written. */
    private fun record(client: Minecraft, spec: String): Boolean {
        val total = (recordSeconds!! * REC_FPS).toInt()
        if (recFrame >= total) {
            DevClock.fixedMs = 0L
            hudOnly = null
            recFrame = 0
            client.player?.connection?.sendCommand("tick unfreeze")
            lyricsWereOn?.let { on -> dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")?.enabled = on }
            lyricsWereOn = null
            return true
        }
        if (!recDrawn) {
            // Set the time and view for this frame, and take it on the next tick once drawn.
            if (recFrame > 0) {
                val step = 1000L / REC_FPS + (if (recFrame % 3 == 0) 1 else 0)
                DevClock.fixedMs += if (spec == "title-slides") step * 5 / 2 else step
            }
            if (spec == "world-timesweep") {
                // Eased so the sunset is not rushed: slow at both ends.
                val t = recFrame.toFloat() / (total - 1).coerceAtLeast(1)
                val eased = t * t * (3 - 2 * t)
                dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.TimeChangerModule>("time_changer")
                    ?.setTimeForDevShot((SWEEP_FROM + (SWEEP_TO - SWEEP_FROM) * eased).toInt())
            }
            client.player?.let {
                val yaw = recYaw + recFrame * 1.5f / REC_FPS
                it.yRot = yaw; it.yRotO = yaw
                it.xRot = recPitch; it.xRotO = recPitch
            }
            recDrawn = true
            return false
        }
        val name = spec.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dir = java.io.File(client.gameDirectory, "screenshots/rec-$name").apply { mkdirs() }
        capture(client, spec, java.io.File(dir, "f%05d.png".format(recFrame)), quiet = true)
        recFrame++
        recDrawn = false
        return false
    }

    private fun capture(client: Minecraft, spec: String, out: java.io.File? = null, quiet: Boolean = false) {
        val dir = java.io.File(client.gameDirectory, "screenshots").apply { mkdirs() }
        val file = out ?: java.io.File(dir, "devshot-" + spec.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".png")
        capturing = true
        runCatching {
//? if >=1.21.11 && <26.1 {
            // A direct call also works in a production jar, where the names looked up below do not exist.
            net.minecraft.client.Screenshot.takeScreenshot(client.mainRenderTarget) { image ->
                image.writeToFile(file)
                image.close()
                if (!quiet) println("[river] devshot saved ${file.name}")
                capturing = false
            }
            return
//?}
            val screenshot = Class.forName("net.minecraft.client.Screenshot")
            // 26.2 moved the main render target from Minecraft to GameRenderer.
            val target = runCatching { Minecraft::class.java.getMethod("getMainRenderTarget").invoke(client) }
                .getOrElse { client.gameRenderer.javaClass.getMethod("mainRenderTarget").invoke(client.gameRenderer) }
            // Before 1.21.5 the screenshot is read back at once and returned, not handed to a callback.
            screenshot.methods.firstOrNull { it.name == "takeScreenshot" && it.parameterCount == 1 }?.let { direct ->
                val image = direct.invoke(null, target)
                image!!.javaClass.getMethod("writeToFile", java.io.File::class.java).invoke(image, file)
                (image as AutoCloseable).close()
                if (!quiet) println("[river] devshot saved ${file.name}")
                capturing = false
                return
            }
            val method = screenshot.methods.first { it.name == "takeScreenshot" && it.parameterCount == 2 }
            val consumer = java.util.function.Consumer<Any?> { image ->
                runCatching {
                    image!!.javaClass.getMethod("writeToFile", java.io.File::class.java).invoke(image, file)
                    (image as AutoCloseable).close()
                    if (!quiet) println("[river] devshot saved ${file.name}")
                }.onFailure { println("[river] devshot could not write ${file.name}: $it") }
                capturing = false
            }
            method.invoke(null, target, consumer)
        }.onFailure {
            println("[river] devshot failed for $spec: $it")
            capturing = false
        }
    }

    // ------------------------------------------------------------------ benchmark

    private const val BENCH_WARMUP_MS = 20_000L
    /** The first second of each block is thrown away: the switch itself is not the steady state. */
    private const val BENCH_SETTLE_MS = 1_000L

    /*
      Each block draws a different HUD: "none", "all", or one module on its own. By default
      it alternates all/none in 10 s blocks; with -Driver.bench.modules=true it also gives
      every enabled module a block of its own, so each one's cost can be read against none.
      The whole sequence runs twice so drift (chunks, time of day) mostly cancels out.
    */
    private val benchPerModule = System.getProperty("river.bench.modules") == "true"

    /*
      -Driver.bench.features=all|glass: the whole of a module is switched, not only its HUD,
      so what a module costs in the world (sky, lyrics, capes) is measured too. "none" has
      every module off, "all" is the config as it is, "all-noglass" the same without the
      glass blur, and with "all" every module then gets a block where only it is on.
    */
    private val benchFeatures: String? = System.getProperty("river.bench.features")
    private var benchSaved: Map<String, Boolean>? = null
    private var benchGlassSaved = true
    private var benchApplied = ""

    private fun applyFeatureBlock(label: String) {
        val modules = dev.wyz.clientcore.module.ModuleRegistry.all
        val config = dev.wyz.clientcore.RiverRuntime.config
        val saved = benchSaved ?: modules.associate { it.id to it.enabled }.also {
            benchSaved = it
            benchGlassSaved = config.glassEffect
        }
        config.glassEffect = benchGlassSaved && label != "all-noglass"
        for (m in modules) {
            m.enabled = when (label) {
                "none" -> false
                "all", "all-noglass" -> saved[m.id] == true
                else -> m.id == label
            }
        }
        if (label == "lyrics") {
            dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.LyricsModule>("lyrics")?.startDemo()
        }
    }

    private fun restoreFeatures() {
        val saved = benchSaved ?: return
        for (m in dev.wyz.clientcore.module.ModuleRegistry.all) m.enabled = saved[m.id] == true
        dev.wyz.clientcore.RiverRuntime.config.glassEffect = benchGlassSaved
    }

    /** Read by RiverRuntime.renderHud: true during the benchmark's "none" blocks. */
    @JvmStatic var hudHidden = false
        private set
    /** Read by ModuleRegistry.render: when set, only this module draws. */
    @JvmStatic var hudOnly: String? = null
        private set

    private var benchWorldRequested = false
    private var benchInWorldAt = 0L
    private var benchStartedAt = 0L
    private var benchLastSampleAt = 0L
    private var benchBlocks: List<String> = emptyList()
    private var benchBlockMs = 10_000L
    private val benchFps = LinkedHashMap<String, ArrayList<Int>>()
    private val benchAlloc = LinkedHashMap<String, Long>()
    private var benchAllocAt = 0L
    private var benchLabel = "all"
    private var benchRecording: jdk.jfr.Recording? = null
    private var benchGcCountStart = 0L
    private var benchGcTimeStart = 0L

    private fun bench(client: Minecraft, seconds: Int) {
        val now = System.currentTimeMillis()
        val inWorld = client.level != null && client.player != null && client.screen == null && !loadingOverlay(client)
        if (!inWorld) {
            if (!benchWorldRequested && client.level == null && client.screen != null && !loadingOverlay(client)) {
                benchWorldRequested = true
                openWorld(client, benchWorld)
            }
            benchInWorldAt = 0L
            return
        }
        if (benchInWorldAt == 0L) benchInWorldAt = now
        if (benchStartedAt == 0L) {
            if (now - benchInWorldAt < BENCH_WARMUP_MS) return
            uncapFrameRate(client)
            benchBlocks = if (benchFeatures != null) {
                benchBlockMs = 4_000L
                val known = dev.wyz.clientcore.module.ModuleRegistry.all.filter { !it.comingSoon }.map { it.id }
                // "all" gives every module a block; a comma list (stats,coordinates) only those.
                val ids = if (benchFeatures == "all") known else benchFeatures.split(',').filter { it in known }
                val once = listOf("none", "all", "all-noglass") + ids
                val repeats = if (ids.isEmpty()) ((seconds * 1000L) / (benchBlockMs * once.size)).toInt().coerceAtLeast(2) else 2
                List(repeats) { once }.flatten()
            } else if (benchPerModule) {
                benchBlockMs = 6_000L
                val ids = dev.wyz.clientcore.module.ModuleRegistry.all.filter { it.active }.map { it.id }
                val once = listOf("none", "all") + ids
                once + once
            } else {
                benchBlockMs = 10_000L
                List(((seconds * 1000L) / benchBlockMs).toInt().coerceAtLeast(2)) { if (it % 2 == 0) "all" else "none" }
            }
            benchRecording = runCatching {
                jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply { start() }
            }.getOrNull()
            benchGcCountStart = gcCount()
            benchGcTimeStart = gcTime()
            benchStartedAt = now
            benchAllocAt = allocatedBytes()
            println("[river] bench: ${benchBlocks.size} blocks of ${benchBlockMs / 1000}s: ${benchBlocks.distinct()}")
            return
        }

        val elapsedMs = now - benchStartedAt
        val block = (elapsedMs / benchBlockMs).toInt()
        // Allocation is charged to whichever HUD was drawn since the last tick.
        val allocNow = allocatedBytes()
        benchAlloc[benchLabel] = (benchAlloc[benchLabel] ?: 0L) + (allocNow - benchAllocAt)
        benchAllocAt = allocNow
        if (block < benchBlocks.size) {
            benchLabel = benchBlocks[block]
            hudHidden = benchLabel == "none"
            if (benchFeatures != null) {
                if (benchApplied != benchLabel) {
                    benchApplied = benchLabel
                    applyFeatureBlock(benchLabel)
                }
            } else {
                hudOnly = benchLabel.takeIf { it != "none" && it != "all" }
            }
            val inBlock = elapsedMs - block * benchBlockMs
            if (inBlock >= BENCH_SETTLE_MS && now - benchLastSampleAt >= 1000L) {
                benchLastSampleAt = now
                benchFps.getOrPut(benchLabel) { ArrayList() }.add(client.fps)
            }
            return
        }

        hudHidden = false
        hudOnly = null
        restoreFeatures()
        // Everything is measured before the recording is written: the dump is not River.
        val elapsed = elapsedMs / 1000.0
        val gcCollections = gcCount() - benchGcCountStart
        val gcMs = gcTime() - benchGcTimeStart
        // Medians, not averages: one stall (an autosave, a chunk burst) wrecks an average.
        val noneMedian = benchFps["none"]?.sorted()?.let { it[it.size / 2].toDouble() } ?: 0.0
        val report = buildString {
            appendLine("world=$benchWorld seconds=${"%.1f".format(elapsed)} window=${client.window.width}x${client.window.height} blocks=${benchBlockMs / 1000}s")
            appendLine(String.format("%-18s %6s %6s %6s  %s", "HUD", "avg", "median", "min", "cost vs none"))
            for ((label, list) in benchFps) {
                val sorted = list.sorted()
                val avg = sorted.average()
                val median = sorted[sorted.size / 2].toDouble()
                val cost = if (label == "none" || noneMedian <= 0) "" else "%.3f ms/frame".format(1000 / median - 1000 / noneMedian)
                appendLine(String.format("%-18s %6d %6d %6d  %s", label, avg.toInt(), sorted[sorted.size / 2], sorted.first(), cost))
            }
            val perLabelSeconds = benchBlockMs / 1000.0 * benchBlocks.count { it == "all" }
            appendLine("render-thread alloc MB/s: all ${"%.1f".format((benchAlloc["all"] ?: 0) / 1048576.0 / perLabelSeconds)}, none ${"%.1f".format((benchAlloc["none"] ?: 0) / 1048576.0 / perLabelSeconds)}")
            appendLine("gc collections=$gcCollections gc ms=$gcMs")
        }
        val dir = java.io.File(client.gameDirectory, "bench").apply { mkdirs() }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        java.io.File(dir, "river-$stamp.txt").writeText(report)
        benchRecording?.let { recording ->
            runCatching {
                recording.stop()
                recording.dump(java.io.File(dir, "river-$stamp.jfr").toPath())
                recording.close()
            }
        }
        println("[river] bench done\n$report")
        done = true
        client.stop()
    }

    /** The render thread's allocations so far; the tick runs on that thread. */
    private fun allocatedBytes(): Long = runCatching {
        (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes
    }.getOrDefault(0L)

    private fun gcCount(): Long = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun gcTime(): Long = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }

    /** Opens a singleplayer world by folder name. Reflection: the flow's API differs by version. */
    private fun openWorld(client: Minecraft, name: String) {
        runCatching {
            val flows = Minecraft::class.java.methods.first { it.name == "createWorldOpenFlows" && it.parameterCount == 0 }.invoke(client)
            if (java.lang.Boolean.getBoolean("river.devshot.createWorld") && !java.io.File(client.gameDirectory, "saves/$name/level.dat").exists()) {
                val settingsClass = Class.forName("net.minecraft.world.level.LevelSettings")
                val constructor = settingsClass.constructors.first { it.parameterCount == 7 || it.parameterCount == 5 }
                val types = constructor.parameterTypes
                val data = types.last().getField("DEFAULT").get(null)
                val creative = types[1].enumConstants.first { it.toString() == "CREATIVE" }
                val settings = if (types.size == 7) {
                    val features = types.last().getMethod("enabledFeatures").invoke(data)
                    val rules = types[5].constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInstance(features) }?.newInstance(features)
                        ?: types[5].getConstructor().newInstance()
                    constructor.newInstance(name, creative, false, types[3].enumConstants.first { it.toString() == "PEACEFUL" }, true, rules, data)
                } else {
                    val difficulty = types[2].constructors.first { it.parameterCount == 3 }
                    val peaceful = difficulty.parameterTypes[0].enumConstants.first { it.toString() == "PEACEFUL" }
                    constructor.newInstance(name, creative, difficulty.newInstance(peaceful, false, false), true, data)
                }
                val optionsClass = Class.forName("net.minecraft.world.level.levelgen.WorldOptions")
                val options = optionsClass.getConstructor(Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).newInstance(1L, false, false)
                val presets = Class.forName("net.minecraft.world.level.levelgen.presets.WorldPresets")
                val dimensions = presets.methods.firstOrNull { it.name == "createFlatWorldDimensions" && it.parameterCount == 1 }
                    ?: presets.methods.first { it.name == "createNormalWorldDimensions" && it.parameterCount == 1 }
                val factory = java.util.function.Function<Any, Any> { dimensions.invoke(null, it) }
                flows.javaClass.methods.first { it.name == "createFreshLevel" && it.parameterCount == 5 }.invoke(flows, name, settings, options, factory, null)
                println("[river] devshot creating test world $name")
                return
            }
            val open = flows.javaClass.methods.first {
                it.name == "openWorld" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
            }
            open.invoke(flows, name, Runnable { println("[river] bench: opening $name was cancelled") })
            println("[river] bench opening $name")
        }.onFailure {
            println("[river] bench could not open $name: $it")
            if (shots.isNotEmpty()) throw it
        }
    }

    /**
     * No frame cap, no vsync, and no AFK limiter (nobody touches a benchmark, so the game
     * would otherwise drop to its idle frame rate after a minute). Never saved.
     */
    private fun uncapFrameRate(client: Minecraft) {
        val options = client.options
        fun option(name: String): Any? = runCatching {
            options.javaClass.methods.first { it.name == name && it.parameterCount == 0 }.invoke(options)
        }.getOrNull()
        fun set(instance: Any?, value: Any) = runCatching {
            instance!!.javaClass.methods.first { it.name == "set" && it.parameterCount == 1 }.invoke(instance, value)
        }
        set(option("framerateLimit"), 260)
        set(option("enableVsync"), false)
        option("inactivityFpsLimit")?.let { instance ->
            runCatching {
                val current = instance.javaClass.methods.first { it.name == "get" && it.parameterCount == 0 }.invoke(instance)
                @Suppress("UNCHECKED_CAST")
                val minimized = (current!!.javaClass.enumConstants as Array<Enum<*>>).first { it.name == "MINIMIZED" }
                set(instance, minimized)
            }
        }
    }

    /** A hidden run makes no sound. The volume it had is put back before the game closes, as options.txt is shared with real dev runs. */
    private var mutedFrom: Double? = null

    /** Sets the master volume and returns the old one. By reflection: the option getter moved between versions. */
    private fun setMasterVolume(client: Minecraft, volume: Double): Double? = runCatching {
        val options = client.options
        val instance = options.javaClass.methods.first { it.name == "getSoundSourceOptionInstance" && it.parameterCount == 1 }
            .invoke(options, net.minecraft.sounds.SoundSource.MASTER)!!
        val old = instance.javaClass.methods.first { it.name == "get" && it.parameterCount == 0 }.invoke(instance) as Double
        instance.javaClass.methods.first { it.name == "set" && it.parameterCount == 1 }.invoke(instance, volume)
        old
    }.getOrNull()

    private var mediaStep = 0
    private var mediaStepAt = 0L
    private var mediaTitle = ""
    private var mediaPausedAt = 0L
    private var mediaPosition = 0L

    /** Exercises rendered HUD hitboxes and the real session; the player must already be playing. */
    private fun mediaControls(client: Minecraft, now: Long): Boolean {
        if (mediaStep == 6) return true
        check(now - mediaStepAt < 20_000L) { "Media control audit timed out at step $mediaStep" }
        val track = dev.wyz.clientcore.media.MediaSession.current() ?: return false
        val module = dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.NowPlayingModule>("now_playing") ?: error("Music module missing")
        fun field(name: String): Any = module.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(module)
        fun click(name: String) {
            check(field("controlsShown") == true) { "Music controls are not rendered" }
            val box = field(name) as IntArray
            check(box[2] > box[0] && box[3] > box[1]) { "Empty music control hitbox" }
            val mx = module.x + ((box[0] + box[2]) / 2.0 - module.x) * module.scaleFactor()
            val my = module.y + ((box[1] + box[3]) / 2.0 - module.y) * module.scaleFactor()
            val window = client.window
            fun windowValue(vararg names: String): Number = window.javaClass.methods.first {
                it.name in names && it.parameterCount == 0
            }.invoke(window) as Number
            val mouse = client.mouseHandler
            mouse.javaClass.getDeclaredField("xpos").apply { isAccessible = true }.setDouble(mouse, mx * windowValue("getScreenWidth", "screenWidth").toDouble() / window.guiScaledWidth)
            mouse.javaClass.getDeclaredField("ypos").apply { isAccessible = true }.setDouble(mouse, my * windowValue("getScreenHeight", "screenHeight").toDouble() / window.guiScaledHeight)
            val press = mouse.javaClass.declaredMethods.first { it.name == "onButton" || it.name == "onPress" }.apply { isAccessible = true }
            val handle = windowValue("handle", "getWindow").toLong()
            if (press.parameterCount == 3) {
                val info = press.parameterTypes[1].getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(0, 0)
                press.invoke(mouse, handle, info, 1)
                press.invoke(mouse, handle, info, 0)
            } else {
                press.invoke(mouse, handle, 0, 1, 0)
                press.invoke(mouse, handle, 0, 0, 0)
            }
            mediaStepAt = now
        }
        when (mediaStep) {
            0 -> if (track.playing && track.artFile.isNotBlank() && field("controlsShown") == true) {
                mediaTitle = track.title
                click("playBox")
                mediaStep = 1
            }
            1 -> if (!track.playing && now - mediaStepAt > 2000L) {
                mediaPosition = track.positionMs
                mediaPausedAt = now
                mediaStep = 2
            }
            2 -> if (now - mediaPausedAt > 2000L) {
                check(!track.playing && kotlin.math.abs(track.positionMs - mediaPosition) < 250L) { "Paused music clock moved" }
                println("[river] media controls: HUD pause and frozen clock passed")
                click("playBox")
                mediaStep = 3
            }
            3 -> if (track.playing && track.positionMs > mediaPosition + 750L) {
                println("[river] media controls: HUD resume passed")
                click("nextBox")
                mediaStep = 4
            }
            4 -> if (track.title != mediaTitle && track.artFile.isNotBlank() && module.playback()?.title == track.title) {
                println("[river] media controls: HUD next passed (${track.title})")
                click("prevBox")
                mediaStep = 5
            }
            5 -> if (track.title == mediaTitle && track.playing) {
                println("[river] media controls: HUD previous passed ($mediaTitle)")
                mediaStep = 6
                grabAt = now + 4500L
            }
        }
        return false
    }

    private fun finish(client: Minecraft) {
        mutedFrom?.takeIf { !it.isNaN() }?.let { setMasterVolume(client, it) }
        if (themeSaved) RiverRuntime.config.theme = originalTheme
        RiverSocial.stopPreview()
        RiverSocial.previewSignedOut = false
        println("[river] devshot done")
        client.stop()
    }

    /** The resource-loading overlay is up. Not every version has getOverlay, hence reflection. */
    private val overlayGetter by lazy {
        Minecraft::class.java.methods.firstOrNull { it.name == "getOverlay" && it.parameterCount == 0 }
    }

    private fun loadingOverlay(client: Minecraft): Boolean = runCatching {
        overlayGetter?.invoke(client) != null
    }.getOrDefault(false)

    /** Hides the window so the run does not take over the screen, and sizes the frame. */
    private fun prepareWindow(client: Minecraft, w: Int, h: Int): Boolean = runCatching {
        val window = client.window
        val handleMethod = window.javaClass.methods.firstOrNull { it.name == "handle" && it.parameterCount == 0 }
            ?: window.javaClass.getMethod("getWindow")
        val handle = handleMethod.invoke(window) as Long
        // Off screen, hidden and see-through to the mouse: whoever is using the machine
        // must be able to keep working while this runs, without clicking into the game.
        GLFW.glfwSetWindowPos(handle, -20000, -20000)
        GLFW.glfwHideWindow(handle)
        runCatching { GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE) }
        GLFW.glfwSetWindowSize(handle, w, h)
        true
    }.getOrDefault(true)
}

/** A stand-in for the world, with the message popups drawn over it the way the HUD draws them. */
private class ToastPreview : Screen(Component.literal("Toast preview")) {
//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        g.fillGradient(0, 0, width, height * 2 / 3, 0xFF6FA8FF.toInt(), 0xFFA9CBFF.toInt())
        g.fill(0, height * 2 / 3, width, height, 0xFF5D8C3A.toInt())
        g.fill(0, height * 2 / 3 + 6, width, height, 0xFF79553A.toInt())
        dev.wyz.clientcore.ui.FriendMessageToasts.render(Minecraft.getInstance(), g)
    }

    override fun isPauseScreen(): Boolean = false
}

/** Stands in for the pause menu, which needs a player to open: its buttons, River's included. */
private class PausePreview : Screen(Component.literal("Pause preview")) {
    override fun init() {
        val x = width / 2 - 102
        val y = height / 4 + 8
        val labels = listOf("Back to Game", "Advancements", "Options...", "Save and Quit to Title")
        labels.forEachIndexed { i, label ->
            addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal(label)) {}.bounds(x, y + i * 24, 204, 20).build())
        }
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("River Client")) {}.bounds(x, y + labels.size * 24, 204, 20).build())
    }

    override fun isPauseScreen(): Boolean = false
}

/** The sample people and messages the dev shots draw. Real player names, so faces load. */
private object PreviewData {

    const val CHAT_FRIEND = "f-galaxy"
    const val QUIET_FRIEND = "f-jam"
    private const val SELF = "self"

    private val friends = listOf(
        RiverSocial.Friend(CHAT_FRIEND, "Galaxysdc", true, "play.hypixel.net", "online"),
        RiverSocial.Friend(QUIET_FRIEND, "jamboy703", true, "", "online"),
        RiverSocial.Friend("f-axie", "ItzAxie", true, "", "idle"),
        RiverSocial.Friend("f-kyr", "Kyr0Fall", true, "minemen.club", "dnd"),
        RiverSocial.Friend("f-crx", "Crxmson", false, "", "offline"),
        RiverSocial.Friend("f-kyro", "Kyro", false, "", "offline"),
        RiverSocial.Friend("f-pao", "Paolino", false, "", "offline")
    )

    private val requests = listOf(
        RiverSocial.Request("r-dream", "Dream"),
        RiverSocial.Request("r-grian", "Grian")
    )

    private val blocked = listOf(
        RiverSocial.Friend("b-1", "xXgriefer99Xx", false, ""),
        RiverSocial.Friend("b-2", "SpamBot3000", false, "")
    )

    private fun thread(): List<RiverSocial.Message> {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val day = 24 * 60 * minute
        fun them(text: String, ago: Long) = RiverSocial.Message("m$ago", CHAT_FRIEND, "Galaxysdc", text, now - ago)
        fun me(text: String, ago: Long) = RiverSocial.Message("m$ago", SELF, "You", text, now - ago)
        return listOf(
            them("you still up for bedwars later?", day + 95 * minute),
            me("yeah after dinner", day + 93 * minute),
            me("like 8?", day + 92 * minute),
            them("perfect", day + 90 * minute),
            them("yo did you see the new river update", 48 * minute),
            them("the friends screen got way better lol", 47 * minute),
            me("haha yeah, faces and everything now", 41 * minute),
            me("also you can see who is idle or on do not disturb, and unread messages get a little count next to the name so you never miss one", 40 * minute),
            them("nice. im on hypixel rn, join me", 6 * minute),
            them("party is open", 5 * minute)
        )
    }

    fun start() {
        RiverSocial.startPreview(
            self = SELF,
            friends = friends,
            requests = requests,
            threads = mapOf(CHAT_FRIEND to thread()),
            blocked = blocked,
            unread = mapOf("f-axie" to 2, "f-kyr" to 1)
        )
    }

    fun startEmpty() {
        RiverSocial.startPreview(SELF, emptyList(), emptyList(), emptyMap(), emptyList(), emptyMap())
    }
}
