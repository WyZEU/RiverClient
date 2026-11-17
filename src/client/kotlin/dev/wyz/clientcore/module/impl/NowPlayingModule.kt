package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.bridge.DesktopBridge
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import dev.wyz.clientcore.compat.riverBlit

/**
 * Now playing.
 *
 * Reads the launcher's file bridge or the game's platform media reader. Shows the
 * available album art, track name and timestamp - no app branding text.
 *
 * When a menu is open (inventory, pause, etc.) it also shows previous / play-pause /
 * next controls; clicking one sends a command through the reader providing the
 * displayed track. Purely a readout otherwise - server-safe.
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

    // Text color is the song title, the label color its times, the accent the progress bar.
    override val styleOpts = setOf(StyleOpt.PADDING, StyleOpt.BORDER, StyleOpt.TEXT, StyleOpt.LABEL, StyleOpt.ACCENT, StyleOpt.SHADOW)
    override val labelColorName: String = "Times color"
    override val labelColorHint: String? = null

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
    // Written by the reader thread (see poll), read by the render thread.
    @Volatile private var track: Track? = null
    private var lastPollMs = 0L
    @Volatile private var readAtMs = 0L
    @Volatile private var reading = false
    /** True while the song comes from MediaSession rather than the launcher's feed. */
    @Volatile private var fromSession = false

    // Playback clock anchor. Spotify only pushes its timeline position every few seconds,
    // so we anchor to the last real position and interpolate between pushes; otherwise the
    // progress would only move when Spotify pushes (roughly every 5 seconds).
    @Volatile private var anchorPos = 0
    @Volatile private var anchorAt = 0L
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

    private val bridgeDir: Path by lazy { DesktopBridge.riverDataRoot().resolve("River Client").resolve("bridge") }
    private val bridgeFile: Path by lazy { bridgeDir.resolve("nowplaying.json") }
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "River Now Playing").apply { isDaemon = true }
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Now Playing"))
        list.add(BoolSetting("Album art", { flag("np_art", true) }, { setFlag("np_art", it) }))
        list.add(BoolSetting("Progress bar", { flag("np_progress", true) }, { setFlag("np_progress", it) }))
        list.add(BoolSetting("Times", { flag("np_times", true) }, { setFlag("np_times", it) }))
        list.add(BoolSetting("Controls in menus", { flag("np_controls", true) }, { setFlag("np_controls", it) }))
        list.add(BoolSetting("Hide when paused", { flag("np_hide_paused", false) }, { setFlag("np_hide_paused", it) }))
    }

    /*
      Reading and parsing the bridge file used to happen right here on the render thread,
      twice a second: a disk read and a JSON parse in the middle of a frame, which is a
      small stutter every half second on a slow disk. It now runs on a reader thread and
      the frame only ever looks at the last result.
    */
    private fun poll() {
        val now = System.currentTimeMillis()
        if (now - lastPollMs < POLL_MS || reading) return
        lastPollMs = now
        reading = true
        reader.execute {
            try {
                read(now)
            } finally {
                reading = false
            }
        }
    }

    /**
     * The song from the launcher's feed while it is fresh (the launcher started the game),
     * otherwise through MediaSession, so the module also works from other launchers.
     * The macOS reader also supplies artwork; unavailable artwork uses the fallback mark.
     */
    private fun read(now: Long) {
        runCatching {
            val fresh = bridgeFile.exists() && now - Files.getLastModifiedTime(bridgeFile).toMillis() < STALE_MS
            // A feed with no song in it is no reason to stop looking: the launcher's helper can
            // come up empty (blocked by an antivirus, say) while this reader still finds it.
            val feed = if (fresh) gson.fromJson(Files.readString(bridgeFile), Track::class.java)?.takeIf { it.title.isNotBlank() } else null
            fromSession = feed == null
            val t = feed ?: run {
                dev.wyz.clientcore.media.MediaSession.current()?.let {
                    Track(
                        playing = it.playing, title = it.title, artist = it.artist, album = it.album,
                        position = (it.positionMs / 1000L).toInt(), duration = (it.durationMs / 1000L).toInt(),
                        artFile = it.artFile, source = it.source
                    )
                }
            }
            if (t == null) { track = null; return }
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
        /*
          The cover only changes with the track. Checking the file is two filesystem calls,
          and doing that every frame showed up in the profiler (an antivirus watching file
          access makes each one slower still), so it is looked at once a second at most.
        */
        val now = System.currentTimeMillis()
        if (artFile == coverCheckedPath && now - coverCheckedAt < 1000L) return coverChecked
        coverCheckedPath = artFile
        coverCheckedAt = now
        coverChecked = loadCover(artFile)
        return coverChecked
    }

    private var coverCheckedPath = ""
    private var coverCheckedAt = 0L
    private var coverChecked: McId? = null

    private fun loadCover(artFile: String): McId? {
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

    /** What is playing, as other modules (lyrics) need it. [positionMs] runs on between the player's pushes. */
    class Playback(
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val positionMs: Long,
        val playing: Boolean
    )

    /**
     * The current song, or null when nothing fresh is playing. Reads the bridge itself, so it
     * works with this module's own HUD switched off. The player reports whole seconds, so the
     * position is good to about a second; lyrics have a sync offset for the rest.
     */
    fun playback(): Playback? {
        poll()
        val t = track ?: return null
        if (t.title.isBlank()) return null
        val now = System.currentTimeMillis()
        if (now - readAtMs > STALE_MS) return null
        val limit = if (t.duration > 0) t.duration * 1000L else Long.MAX_VALUE
        val ms = if (t.playing) (anchorPos * 1000L + (now - anchorAt)).coerceIn(0L, limit) else t.position * 1000L
        return Playback(t.title, t.artist, t.album, t.duration, ms, t.playing)
    }

    private fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (overMenu(client)) return
        renderTrack(client, graphics)
    }

    private fun overMenu(client: Minecraft): Boolean = client.screen != null && client.screen !is ChatScreen &&
        client.screen !is dev.wyz.clientcore.ui.RiverScreen

    /** Screen backgrounds blur the HUD; draw usable controls after that background. */
    fun renderMenu(client: Minecraft, graphics: GuiGraphics) {
        controlsShown = false
        if (!active || !overMenu(client) || client.level == null || client.player == null) return
        if (dev.wyz.clientcore.dev.DevShot.hudHidden || dev.wyz.clientcore.hud.HudRenderer.inReplay()) return
//? if >=26.2 {
/*        if (client.gui.hud.isHidden()) return
*///?} else {
        if (client.options.hideGui) return
//?}
        renderScaled(graphics) { renderTrack(client, graphics) }
    }

    private fun renderTrack(client: Minecraft, graphics: GuiGraphics) {
        controlsShown = false
        poll()
        val t = track ?: return
        if (t.title.isBlank()) return
        if (System.currentTimeMillis() - readAtMs > STALE_MS) return
        if (!t.playing && flag("np_hide_paused", false)) return
        draw(client, graphics, t)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        // Deliberately the idle state: a preview that invents a song reads as a bug the
        // first time someone sees a track they are not playing.
        draw(client, graphics, Track(playing = false, title = "Nothing playing", artist = "", position = 0, duration = 0))
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, t: Track) {
        val font = client.font
        val showArt = flag("np_art", true)
        val showBar = flag("np_progress", true) && t.duration > 0
        val showTimes = flag("np_times", true) && t.duration > 0
        // Controls only when a real menu is open (not chat), and enabled.
        val inMenu = overMenu(client)
        val showControls = inMenu && flag("np_controls", true)

        val art = if (showArt) cover(t.artFile) else null
        val coverW2 = if (showArt) COVER else 0
        val barW = font.width(t.title).coerceIn(70, 150)

        // The panel behind it all, in this module's style like every other HUD module.
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val textH = font.lineHeight + 2 +
            (if (showBar) BAR_H + 2 + (if (showTimes) font.lineHeight + 1 else 0) else 0) +
            (if (showControls) BTN + 1 else 0)
        val contentW = coverW2 + (if (showArt) 6 else 0) + barW
        val contentH = maxOf(if (showArt) COVER else 0, textH)
        drawPillBackground(graphics, contentW + pad * 2, contentH + pad * 2)
        val ox = x + pad
        val oy = y + pad
        val tx = ox + coverW2 + (if (showArt) 6 else 0)
        val shadow = st.textShadow
        val times = labelColor
        val bar = if (st.accentColor >= 0) panelAccentColor() else SPOTIFY_GREEN

        // Album cover - the song icon. Falls back to the Spotify mark until art arrives.
        if (showArt) {
            if (art != null) {
//? if >=1.21.6 {
                graphics.riverBlit(art, ox, oy, 0f, 0f, COVER, COVER, coverW, coverH, coverW, coverH)
//?} else {
/*                graphics.riverBlit(art, ox, oy, 0f, 0f, COVER, COVER, coverW, coverH, coverW, coverH)
*///?}
            } else {
                ClientUi.fillRounded(graphics, ox, oy, COVER, COVER, ClientUi.RADIUS_CARD, ClientUi.PANEL_ALT)
//? if >=1.21.6 {
                logo()?.let { graphics.riverBlit(it, ox + 6, oy + 6, 0f, 0f, COVER - 12, COVER - 12, 64, 64, 64, 64) }
//?} else {
/*                logo()?.let { graphics.riverBlit(it, ox + 6, oy + 6, 0f, 0f, COVER - 12, COVER - 12, 64, 64, 64, 64) }
*///?}
            }
        }

        // Track name (no app branding), then progress + times, then controls in a menu.
        var ty = oy
        graphics.drawString(font, trim(font, t.title, barW), tx, ty, panelTextColor(), shadow)
        ty += font.lineHeight + 2

        if (showBar) {
            val done = elapsed(t)
            val barY = ty
            val ratio = (done.toFloat() / t.duration.toFloat()).coerceIn(0f, 1f)
            val fill = (barW * ratio).toInt().coerceAtLeast(1)
            ClientUi.fillRounded(graphics, tx, barY, barW, BAR_H, 1, 0x66000000)
            ClientUi.fillRounded(graphics, tx, barY, fill, BAR_H, 1, if (t.playing) bar else ClientUi.DIM)
            ty = barY + BAR_H + 2
            if (showTimes) {
                graphics.drawString(font, clock(done), tx, ty, times, shadow)
                val right = clock(t.duration)
                graphics.drawString(font, right, tx + barW - font.width(right), ty, times, shadow)
                ty += font.lineHeight + 1
            }
        }

        controlsShown = showControls
        if (showControls) {
            drawControls(graphics, font, tx, ty + 1, t)
        }
    }

    /** Consumes a menu click before a button underneath the panel can receive it. */
    fun onMenuClick(client: Minecraft): Boolean {
        if (!active || !overMenu(client)) return false
//? if >=1.21.9 {
        val mx = client.mouseHandler.getScaledXPos(client.window)
        val my = client.mouseHandler.getScaledYPos(client.window)
//?} else {
/*        val mx = client.mouseHandler.xpos() * client.window.guiScaledWidth / client.window.screenWidth
        val my = client.mouseHandler.ypos() * client.window.guiScaledHeight / client.window.screenHeight
*///?}
        return onControlClick(mx, my)
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
    private fun onControlClick(screenX: Double, screenY: Double): Boolean {
        if (!controlsShown) return false
        val mx = x + (screenX - x) / scaleFactor()
        val my = y + (screenY - y) / scaleFactor()
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
        // Without the launcher the media keys go through River's own reader.
        if (fromSession && dev.wyz.clientcore.media.MediaSession.command(action)) return
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
