package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.compat.McId
import com.google.gson.Gson
import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.ChatScreen
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.resources.ResourceLocation
*///?}
import org.lwjgl.glfw.GLFW
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import dev.wyz.clientcore.compat.riverBlit

/**
 * Now playing.
 *
 * The launcher's native helper reads the Windows media session and drops the track
 * JSON plus the real album cover into the file bridge; this only renders what it
 * finds. Shows the album art, track name and timestamp - no app branding text.
 *
 * When a menu is open (inventory, pause, etc.) it also shows previous / play-pause /
 * next controls; clicking one writes a command to the bridge that the launcher runs
 * against the media session. Purely a readout otherwise - server-safe.
 */
class NowPlayingModule : Module("now_playing", "Spotify", "Now playing from your music", ModuleCategory.HUD, "spotify", 8, 244) {

    private companion object {
        const val POLL_MS = 500L      // the launcher writes every second; poll faster so it never lags a beat
        const val COVER = 30          // album-art square, px
        const val BAR_H = 2
        const val STALE_MS = 12_000L
        const val BTN = 11            // media control button size
        val SPOTIFY_GREEN = 0xFF1DB954.toInt()
    }

    override val hudStack: HudStack = HudStack.TOP_RIGHT

    private data class Track(
        val playing: Boolean = false,
        val status: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val source: String = "",
        val position: Int = 0,
        val duration: Int = 0,
        val artFile: String = "",
        val at: Long = 0L
    )

    private val gson = Gson()
    private var track: Track? = null
    private var lastPollMs = 0L
    private var readAtMs = 0L

    // Playback clock anchor. Spotify only pushes its timeline position every few seconds,
    // so we anchor to the last real position and interpolate between pushes; otherwise the
    // progress would only move when Spotify pushes (roughly every 5 seconds).
    private var anchorPos = 0
    private var anchorAt = 0L
    private var anchorKey = ""
    private var lastRawPos = -1

//? if >=1.21.11 {
    private var coverTexture: Identifier? = null
//?} else {
/*    private var coverTexture: ResourceLocation? = null
*///?}
    private var coverKey: String = ""
    private var coverW: Int = 1
    private var coverH: Int = 1
//? if >=1.21.11 {
    private var logoTexture: Identifier? = null
//?} else {
/*    private var logoTexture: ResourceLocation? = null
*///?}
    private var logoTried = false

    // Hitboxes for the three media buttons, in screen space, valid only while a menu
    // is open. onControlClick (called by the Screen mixin) tests against these.
    private var prevBox = intArrayOf(0, 0, 0, 0)
    private var playBox = intArrayOf(0, 0, 0, 0)
    private var nextBox = intArrayOf(0, 0, 0, 0)
    private var controlsShown = false
    private var wasMouseDown = false

    private val bridgeDir: Path by lazy {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), "AppData", "Roaming")
        appData.resolve("River Client").resolve("bridge")
    }
    private val bridgeFile: Path by lazy { bridgeDir.resolve("nowplaying.json") }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Now Playing"))
        list.add(BoolSetting("Album art", { flag("np_art", true) }, { setFlag("np_art", it) }))
        list.add(BoolSetting("Progress bar", { flag("np_progress", true) }, { setFlag("np_progress", it) }))
        list.add(BoolSetting("Times", { flag("np_times", true) }, { setFlag("np_times", it) }))
        list.add(BoolSetting("Controls in menus", { flag("np_controls", true) }, { setFlag("np_controls", it) }))
        list.add(BoolSetting("Hide when paused", { flag("np_hide_paused", false) }, { setFlag("np_hide_paused", it) }))
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        if (now - lastPollMs < POLL_MS) return
        lastPollMs = now
        runCatching {
            if (!bridgeFile.exists()) { track = null; return }
            val t = gson.fromJson(Files.readString(bridgeFile), Track::class.java)
            track = t
            readAtMs = now
            if (t != null) {
                val id = "${t.title}|${t.artist}|${t.duration}"
                // Re-anchor on a new track, while paused (so the clock is fresh on resume),
                // or when Spotify actually moved the position. Otherwise keep the anchor
                // and let elapsed() tick between pushes.
                if (id != anchorKey || !t.playing || t.position != lastRawPos) {
                    anchorKey = id
                    anchorPos = t.position
                    anchorAt = now
                    lastRawPos = t.position
                }
            }
        }.onFailure { track = null }
    }

    /** Fallback mark (transparent Spotify logo) shown only when there is no album art. */
//? if >=1.21.11 {
    private fun logo(): Identifier? {
//?} else {
/*    private fun logo(): ResourceLocation? {
*///?}
        if (!logoTried) {
            logoTried = true
            logoTexture = runCatching {
                val stream = javaClass.getResourceAsStream("/assets/clientcore/textures/spotify_logo.png")
                    ?: return@runCatching null
                val image = stream.use { NativeImage.read(it) }
//? if >=1.21.5 {
                val id = McId.fromNamespaceAndPath("clientcore", "spotify_logo_dynamic")
                Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "spotify-logo" }, image))
//?} else {
/*                val id = ResourceLocation.fromNamespaceAndPath("clientcore", "spotify_logo_dynamic")
                Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
*///?}
                id
            }.getOrNull()
        }
        return logoTexture
    }

//? if >=1.21.11 {
    private fun cover(artFile: String): Identifier? {
//?} else {
/*    private fun cover(artFile: String): ResourceLocation? {
*///?}
        if (artFile.isBlank()) return null
        val file = runCatching { Path.of(artFile) }.getOrNull() ?: return null
        if (!file.exists()) return null
        val key = runCatching { "$artFile:${Files.getLastModifiedTime(file).toMillis()}" }.getOrDefault(artFile)
        if (key == coverKey && coverTexture != null) return coverTexture
        return runCatching {
            val bytes = Files.readAllBytes(file)
            val image = ByteArrayInputStream(bytes).use { NativeImage.read(it) }
//? if >=1.21.5 {
            val id = McId.fromNamespaceAndPath("clientcore", "spotify_cover_dynamic")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "spotify-cover" }, image))
//?} else {
/*            val id = ResourceLocation.fromNamespaceAndPath("clientcore", "spotify_cover_dynamic")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
*///?}
            coverTexture = id
            coverKey = key
            coverW = image.width
            coverH = image.height
            id
        }.getOrNull()
    }

    private fun elapsed(t: Track): Int {
        if (!t.playing) return t.position
        val e = anchorPos + ((System.currentTimeMillis() - anchorAt) / 1000L).toInt()
        return e.coerceIn(0, if (t.duration > 0) t.duration else Int.MAX_VALUE)
    }

    private fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val scale = scaleFactor()
        return Pair((150 * scale).toInt(), ((6 + COVER) * scale).toInt())
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        poll()
        val t = track ?: return
        if (t.title.isBlank()) return
        if (System.currentTimeMillis() - readAtMs > STALE_MS) return
        if (!t.playing && flag("np_hide_paused", false)) return
        draw(client, graphics, t)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, Track(playing = true, title = "Electrified", artist = "David Kushner", position = 72, duration = 164))
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, t: Track) {
        val font = client.font
        val showArt = flag("np_art", true)
        val showBar = flag("np_progress", true) && t.duration > 0
        val showTimes = flag("np_times", true) && t.duration > 0
        // Controls only when a real menu is open (not chat), and enabled.
        val inMenu = client.screen != null && client.screen !is ChatScreen
        val showControls = inMenu && flag("np_controls", true)

        val art = if (showArt) cover(t.artFile) else null
        val coverW2 = if (showArt) COVER else 0
        val tx = x + coverW2 + (if (showArt) 6 else 0)
        val barW = font.width(t.title).coerceIn(70, 150)

        // Album cover - the song icon. Falls back to the Spotify mark until art arrives.
        if (showArt) {
            if (art != null) {
//? if >=1.21.6 {
                graphics.riverBlit(art, x, y, 0f, 0f, COVER, COVER, coverW, coverH, coverW, coverH)
//?} else {
/*                graphics.riverBlit(art, x, y, 0f, 0f, COVER, COVER, coverW, coverH, coverW, coverH)
*///?}
            } else {
                ClientUi.fillRounded(graphics, x, y, COVER, COVER, ClientUi.RADIUS_CARD, ClientUi.PANEL_ALT)
//? if >=1.21.6 {
                logo()?.let { graphics.riverBlit(it, x + 6, y + 6, 0f, 0f, COVER - 12, COVER - 12, 64, 64, 64, 64) }
//?} else {
/*                logo()?.let { graphics.riverBlit(it, x + 6, y + 6, 0f, 0f, COVER - 12, COVER - 12, 64, 64, 64, 64) }
*///?}
            }
        }

        // Track name (no app branding), then progress + times, then controls in a menu.
        var ty = y
        graphics.drawString(font, trim(font, t.title, barW), tx, ty, ClientUi.TEXT, true)
        ty += font.lineHeight + 2

        if (showBar) {
            val done = elapsed(t)
            val barY = ty
            val ratio = (done.toFloat() / t.duration.toFloat()).coerceIn(0f, 1f)
            val fill = (barW * ratio).toInt().coerceAtLeast(1)
            ClientUi.fillRounded(graphics, tx, barY, barW, BAR_H, 1, 0x66000000)
            ClientUi.fillRounded(graphics, tx, barY, fill, BAR_H, 1, if (t.playing) SPOTIFY_GREEN else ClientUi.DIM)
            ty = barY + BAR_H + 2
            if (showTimes) {
                graphics.drawString(font, clock(done), tx, ty, ClientUi.DIM, true)
                val right = clock(t.duration)
                graphics.drawString(font, right, tx + barW - font.width(right), ty, ClientUi.DIM, true)
                ty += font.lineHeight + 1
            }
        }

        controlsShown = showControls
        if (showControls) {
            drawControls(graphics, font, tx, ty + 1, t)
            pollControlClick(client)
        }
    }

    /**
     * Detects a fresh left-click over one of the control buttons. The HUD keeps
     * drawing behind an open menu, so we can read the real cursor here without a
     * Screen mixin - we just watch for the up->down edge and test the hitboxes in
     * GUI-scaled space.
     */
    private fun pollControlClick(client: Minecraft) {
//? if >=1.21.11 {
        val down = GLFW.glfwGetMouseButton(client.window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
//?} else {
/*        val down = GLFW.glfwGetMouseButton(client.window.getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
*///?}
        if (down && !wasMouseDown) {
//? if >=1.21.11 {
            val mx = client.mouseHandler.getScaledXPos(client.window)
            val my = client.mouseHandler.getScaledYPos(client.window)
//?} else {
/*            // 1.21.4's MouseHandler only exposes raw window-pixel xpos()/ypos(); scale to
            // GUI space manually (the window,-scoped scaled accessors are a later addition).
            val mx = client.mouseHandler.xpos() / client.window.guiScale
            val my = client.mouseHandler.ypos() / client.window.guiScale
*///?}
            onControlClick(mx, my)
        }
        wasMouseDown = down
    }

    /** Prev / play-pause / next, drawn as simple glyphs; hitboxes captured for clicks. */
    private fun drawControls(graphics: GuiGraphics, font: net.minecraft.client.gui.Font, cx: Int, cy: Int, t: Track) {
        val gap = 6
        fun box(bx: Int) = intArrayOf(bx, cy, bx + BTN, cy + BTN)
        prevBox = box(cx)
        playBox = box(cx + BTN + gap)
        nextBox = box(cx + (BTN + gap) * 2)

        for (b in listOf(prevBox, playBox, nextBox)) {
            ClientUi.fillRounded(graphics, b[0], b[1], BTN, BTN, 2, ClientUi.alpha(ClientUi.PANEL_ALT, 0.85f))
        }
        // Glyphs: previous, play/pause, next. Simple filled shapes read at HUD scale.
        drawPrev(graphics, prevBox)
        if (t.playing) drawPause(graphics, playBox) else drawPlay(graphics, playBox)
        drawNext(graphics, nextBox)
    }

    private fun drawPlay(g: GuiGraphics, b: IntArray) {
        val cx = b[0] + BTN / 2 - 1; val cy = b[1] + BTN / 2
        for (i in 0 until 4) g.fill(cx + i, cy - (3 - i), cx + i + 1, cy + (3 - i) + 1, ClientUi.TEXT)
    }
    private fun drawPause(g: GuiGraphics, b: IntArray) {
        val cy = b[1] + 3; val h = BTN - 6
        g.fill(b[0] + 3, cy, b[0] + 5, cy + h, ClientUi.TEXT)
        g.fill(b[0] + 6, cy, b[0] + 8, cy + h, ClientUi.TEXT)
    }
    private fun drawNext(g: GuiGraphics, b: IntArray) {
        val cx = b[0] + 3; val cy = b[1] + BTN / 2
        for (i in 0 until 3) g.fill(cx + i, cy - (2 - i), cx + i + 1, cy + (2 - i) + 1, ClientUi.TEXT)
        for (i in 0 until 3) g.fill(cx + 3 + i, cy - (2 - i), cx + 4 + i, cy + (2 - i) + 1, ClientUi.TEXT)
        g.fill(b[0] + BTN - 3, b[1] + 3, b[0] + BTN - 2, b[1] + BTN - 3, ClientUi.TEXT)
    }
    private fun drawPrev(g: GuiGraphics, b: IntArray) {
        val cx = b[0] + BTN - 3; val cy = b[1] + BTN / 2
        for (i in 0 until 3) g.fill(cx - i, cy - (2 - i), cx - i + 1, cy + (2 - i) + 1, ClientUi.TEXT)
        for (i in 0 until 3) g.fill(cx - 3 - i, cy - (2 - i), cx - 2 - i, cy + (2 - i) + 1, ClientUi.TEXT)
        g.fill(b[0] + 2, b[1] + 3, b[0] + 3, b[1] + BTN - 3, ClientUi.TEXT)
    }

    /**
     * Tests a click against the control buttons. Returns true if a control was hit
     * (and writes the command to the bridge for the launcher to run).
     */
    private fun onControlClick(mx: Double, my: Double): Boolean {
        if (!controlsShown) return false
        val action = when {
            hit(prevBox, mx, my) -> "prev"
            hit(playBox, mx, my) -> "playpause"
            hit(nextBox, mx, my) -> "next"
            else -> return false
        }
        writeCommand(action)
        return true
    }

    private fun hit(b: IntArray, mx: Double, my: Double) = mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]

    private fun writeCommand(action: String) {
        runCatching {
            Files.createDirectories(bridgeDir)
            Files.writeString(bridgeDir.resolve("nowplaying-command.json"), "{\"action\":\"$action\",\"ts\":${System.currentTimeMillis()}}")
        }
    }

    private fun trim(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var out = text
        while (out.isNotEmpty() && font.width("$out...") > maxWidth) out = out.dropLast(1)
        return "$out..."
    }
}
