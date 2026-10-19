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
import org.lwjgl.glfw.GLFW

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

    private fun worldReady(client: Minecraft): Boolean {
        if (client.level == null || client.player == null || loadingOverlay(client)) {
            if (!shotWorldRequested && client.level == null && client.screen != null && !loadingOverlay(client)) {
                shotWorldRequested = true
                openWorld(client, benchWorld)
            }
            shotWorldAt = 0L
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
            if (now < grabAt) return
            grabAt = 0L
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
        grabAt = now + 4500
    }

    private fun open(client: Minecraft, spec: String) {
        val name = spec.substringBefore('@').substringBefore(':')
        val halloween = spec.substringBefore('@').contains(":halloween")
        val windowSize = spec.substringAfter('@', "").split('x').mapNotNull { it.toIntOrNull() }

        val config = RiverRuntime.config
        if (!themeSaved) { originalTheme = config.theme; themeSaved = true }
        config.theme = if (halloween) "halloween" else "river"
        if (windowSize.size == 2) prepareWindow(client, windowSize[0], windowSize[1])
        else prepareWindow(client, size[0], size[1])

        when (name) {
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
            "world-cosmetics" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverCosmeticsScreen(null))
            "world-content" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverContentBrowserScreen(null))
            "servers" -> client.setScreen(dev.wyz.clientcore.ui.screen.RiverServerBrowserScreen(null))
            "multiplayer" -> client.setScreen(net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(net.minecraft.client.gui.screens.TitleScreen()))
            "world-hud" -> {
                client.setScreen(null)
                dev.wyz.clientcore.ui.FriendMessageToasts.devPush("f-axie", "ItzAxie", "you on? we need one more for bedwars", 300)
            }
            "world-menu" -> client.setScreen(RiverMenuScreen(null))
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

    private fun capture(client: Minecraft, spec: String) {
        val dir = java.io.File(client.gameDirectory, "screenshots").apply { mkdirs() }
        val file = java.io.File(dir, "devshot-" + spec.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".png")
        capturing = true
        runCatching {
            val screenshot = Class.forName("net.minecraft.client.Screenshot")
            // 26.2 moved the main render target from Minecraft to GameRenderer.
            val target = runCatching { Minecraft::class.java.getMethod("getMainRenderTarget").invoke(client) }
                .getOrElse { client.gameRenderer.javaClass.getMethod("mainRenderTarget").invoke(client.gameRenderer) }
            val method = screenshot.methods.first { it.name == "takeScreenshot" && it.parameterCount == 2 }
            val consumer = java.util.function.Consumer<Any?> { image ->
                runCatching {
                    image!!.javaClass.getMethod("writeToFile", java.io.File::class.java).invoke(image, file)
                    (image as AutoCloseable).close()
                    println("[river] devshot saved ${file.name}")
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
            benchBlocks = if (benchPerModule) {
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
            hudOnly = benchLabel.takeIf { it != "none" && it != "all" }
            val inBlock = elapsedMs - block * benchBlockMs
            if (inBlock >= BENCH_SETTLE_MS && now - benchLastSampleAt >= 1000L) {
                benchLastSampleAt = now
                benchFps.getOrPut(benchLabel) { ArrayList() }.add(client.fps)
            }
            return
        }

        hudHidden = false
        hudOnly = null
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
            val open = flows.javaClass.methods.first {
                it.name == "openWorld" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
            }
            open.invoke(flows, name, Runnable { println("[river] bench: opening $name was cancelled") })
            println("[river] bench opening $name")
        }.onFailure { println("[river] bench could not open $name: $it") }
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

    private fun finish(client: Minecraft) {
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
