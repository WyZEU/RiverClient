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
import dev.wyz.clientcore.module.settings.hint
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.lyrics.LrcParser
import dev.wyz.clientcore.lyrics.LyricScene
import dev.wyz.clientcore.lyrics.LyricsService
import dev.wyz.clientcore.lyrics.LyricsState
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
class NowPlayingModule : Module("now_playing", "Spotify", "Now playing and lyrics from your music", ModuleCategory.HUD, "spotify", 8, 244) {

    private companion object {
        const val POLL_MS = 500L      // the launcher writes every second; poll faster so it never lags a beat
        const val COVER = 30          // album-art square, px
        const val BAR_H = 2
        const val STALE_MS = 12_000L
        const val BTN = 11            // media control button size
        const val MINI = 12           // album art in the compact bar
        const val MINI_TEXT = 150     // widest the compact bar's text gets
        const val DOTS_W = 10
        val WORD_GAP = Regex("\\s+")
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

    // The compact bar: opened by hovering it in a menu, or held open by a click on it.
    private var pinned = false
    private var panelBox = intArrayOf(0, 0, 0, 0)
    private var shownW = 0f
    private var shownH = 0f
    private var lastFrameAt = 0L
    private var wasOpen = false

    // The lyric line in the compact bar, worked out once per line.
    private class BarLine(val words: List<String>, val starts: LongArray, val ends: LongArray) {
        // Built and measured once per line, not every frame.
        val parts: List<net.minecraft.network.chat.Component> = words.map { net.minecraft.network.chat.Component.literal(it) }
        var widths: IntArray? = null
        var total = 0
        fun measure(font: net.minecraft.client.gui.Font): IntArray = widths ?: IntArray(words.size) { font.width(words[it]) }.also {
            widths = it
            total = it.sum() + font.width(" ") * (words.size - 1).coerceAtLeast(0)
        }
    }
    private var barLineKey = ""
    private var barLine: BarLine? = null
    /** The line before, fading out upward while the new one comes in. */
    private var previousBarLine: BarLine? = null
    private var lineChangedAt = 0L

    private val bridgeDir: Path by lazy { DesktopBridge.riverDataRoot().resolve("River Client").resolve("bridge") }
    private val bridgeFile: Path by lazy { bridgeDir.resolve("nowplaying.json") }
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "River Now Playing").apply { isDaemon = true }
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Now Playing"))
        list.add(BoolSetting("Compact", { flag("np_compact", true) }, { setFlag("np_compact", it) })
            .hint("A small bar. Hover or click it in a menu for the full player."))
        list.add(BoolSetting("Album art", { flag("np_art", true) }, { setFlag("np_art", it) }))
        list.add(BoolSetting("Progress bar", { flag("np_progress", true) }, { setFlag("np_progress", it) }))
        list.add(BoolSetting("Times", { flag("np_times", true) }, { setFlag("np_times", it) }))
        list.add(BoolSetting("Controls in menus", { flag("np_controls", true) }, { setFlag("np_controls", it) }))
        list.add(BoolSetting("Hide when paused", { flag("np_hide_paused", false) }, { setFlag("np_hide_paused", it) }))
        // The Lyrics module lives in here: its switch, then its own settings while it is on.
        ModuleRegistry.get<LyricsModule>("lyrics")?.let { lyrics ->
            list.add(SectionSetting("Lyrics"))
            list.add(BoolSetting("Lyrics", { lyrics.enabled }, { lyrics.enabled = it }))
            if (lyrics.enabled) lyrics.musicSettings(list)
        }
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
            // No cover from the player: the song's cover from the iTunes lookup, once it has it.
            track = if (t.artFile.isBlank() && flag("np_art", true)) t.copy(artFile = dev.wyz.clientcore.media.CoverLookup.fileFor(t.title, t.artist)) else t
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
        draw(client, graphics, t, preview = false)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        // Deliberately the idle state: a preview that invents a song reads as a bug the
        // first time someone sees a track they are not playing.
        draw(client, graphics, Track(playing = false, title = "Nothing playing", artist = "", position = 0, duration = 0), preview = true)
    }

    /**
     * Compact: a small bar with the cover, the lyric being sung (or the song) and three dots
     * that move while it plays. With the cursor free (any menu or chat), hovering the bar
     * opens the full player; a click keeps it open until the next click. The panel eases to
     * its new size and the content appears once it gets there.
     */
    private fun draw(client: Minecraft, graphics: GuiGraphics, t: Track, preview: Boolean) {
        val compact = flag("np_compact", true)
        val cursorFree = !preview && overMenu(client)
        val mouse = if (cursorFree) mouse(client) else null
        val hovered = mouse != null && hit(panelBox, mouse[0], mouse[1])
        val open = !compact || pinned || hovered

        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val line = if (compact && !open) lyricLine() else null
        val size = if (open) fullSize(client, t, cursorFree) else compactSize(client, t, line)
        val targetW = (size[0] + pad * 2).toFloat()
        val targetH = (size[1] + pad * 2).toFloat()

        val now = System.currentTimeMillis()
        val dt = ((now - lastFrameAt).coerceIn(0L, 100L)) / 1000f
        lastFrameAt = now
        if (preview || !compact || shownW <= 0f) {
            shownW = targetW; shownH = targetH
        } else {
            val k = minOf(1f, dt * 18f)
            shownW += (targetW - shownW) * k
            shownH += (targetH - shownH) * k
            if (kotlin.math.abs(targetW - shownW) < 0.5f) shownW = targetW
            if (kotlin.math.abs(targetH - shownH) < 0.5f) shownH = targetH
        }
        val w = shownW.toInt()
        val h = shownH.toInt()
        drawPillBackground(graphics, w, h)
        panelBox = intArrayOf(x, y, x + w, y + h)

        controlsShown = false
        val settled = w == targetW.toInt() && h == targetH.toInt()
        // Between two compact sizes (a new lyric line) the bar keeps its text, trimmed to the
        // width it has so far; opening or closing shows the content once the panel is there.
        if (open && settled) drawFull(client, graphics, t, x + pad, y + pad, cursorFree)
        else if (!open && (settled || !wasOpen)) drawCompact(client, graphics, t, line, x + pad, y + pad, w - pad * 2)
        if (settled) wasOpen = open
    }

    // ------------------------------------------------------------------ compact bar

    private fun compactText(t: Track): String = if (t.artist.isBlank()) t.title else "${t.title} - ${t.artist}"

    private fun compactSize(client: Minecraft, t: Track, line: Pair<BarLine, Long>?): IntArray {
        val font = client.font
        val textW = (if (line != null) line.first.run { measure(font); total } else font.width(compactText(t))).coerceIn(40, MINI_TEXT)
        val art = if (flag("np_art", true)) MINI + 5 else 0
        return intArrayOf(art + textW + 6 + DOTS_W, maxOf(if (art > 0) MINI else 0, font.lineHeight))
    }

    private fun drawCompact(client: Minecraft, graphics: GuiGraphics, t: Track, line: Pair<BarLine, Long>?, ox: Int, oy: Int, shownW: Int) {
        val font = client.font
        val size = compactSize(client, t, line).also { it[0] = minOf(it[0], shownW) }
        val showArt = flag("np_art", true)
        val shadow = effectiveStyle().textShadow
        val accent = if (effectiveStyle().accentColor >= 0) panelAccentColor() else SPOTIFY_GREEN
        if (showArt) drawCover(graphics, t, ox, oy, MINI)
        val tx = ox + if (showArt) MINI + 5 else 0
        val ty = oy + (size[1] - font.lineHeight) / 2 + 1
        val textW = size[0] - (tx - ox) - 6 - DOTS_W
        val now = System.currentTimeMillis()
        if (line != null) {
            // A new line rises in as the one before lifts away, over a fifth of a second.
            val p = ((now - lineChangedAt) / 220f).coerceIn(0f, 1f)
            val eased = 1f - (1f - p) * (1f - p) * (1f - p)
            val before = previousBarLine
            if (p < 1f && before != null) {
                drawKaraoke(graphics, font, before, Long.MAX_VALUE, tx, ty - (eased * 3f).toInt(), textW, accent, shadow, 1f - eased, false)
            }
            drawKaraoke(graphics, font, line.first, line.second, tx, ty + ((1f - eased) * 3f).toInt(), textW, accent, shadow, eased, t.playing)
        } else {
            graphics.drawString(font, trim(font, compactText(t), textW), tx, ty, panelTextColor(), shadow)
        }
        // Three dots that ripple while the song plays and rest dim when it is paused. Round and
        // placed by fractions of a pixel through the shape shader where there is one.
        val dx = ox + size[0] - DOTS_W
        val dy = oy + size[1] / 2 - 1
        for (i in 0 until 3) {
            val wave = if (t.playing) 0.5f + 0.5f * kotlin.math.sin(now / 180.0 - i * 0.9).toFloat() else 0f
            val color = ClientUi.alpha(accent, 0.35f + 0.65f * wave)
            if (dev.wyz.clientcore.ui.RiverShapes.available) {
                dev.wyz.clientcore.ui.RiverShapes.circle(graphics, dx + i * 4f - 0.2f, dy - wave * 1.2f + 0.6f, 2.4f, color)
            } else {
                graphics.fill(dx + i * 4, dy, dx + i * 4 + 2, dy + 2, color)
            }
        }
    }

    /** The lyric line being sung now with where the song is, or null when there is none to show. */
    private fun lyricLine(): Pair<BarLine, Long>? {
        // Only when Lyrics are on and set to show in this bar (Lyrics > On screen > Spotify bar).
        val lyrics = ModuleRegistry.get<LyricsModule>("lyrics") ?: return null
        if (!lyrics.enabled || !lyrics.inSpotifyBar()) return null
        // The same song source as the Lyrics module, so both share one fetched lyrics entry.
        val session = dev.wyz.clientcore.media.MediaSession.current()
        val pb = if (session != null) Playback(session.title, session.artist, session.album, (session.durationMs / 1000L).toInt(), session.positionMs, session.playing)
            else playback() ?: return null
        val lines = (LyricsService.stateFor(pb.title, pb.artist, pb.album, pb.durationSec) as? LyricsState.Ready)?.lines ?: return null
        val pos = pb.positionMs + lyrics.offsetMs()
        val index = LrcParser.indexAt(lines, pos)
        val l = lines.getOrNull(index) ?: return null
        if (l.text.isBlank()) return null
        val key = "${pb.title}|${pb.artist}|$index"
        if (key != barLineKey || barLine == null) {
            barLineKey = key
            previousBarLine = barLine
            lineChangedAt = System.currentTimeMillis()
            val timed = l.words
            barLine = if (timed != null && timed.isNotEmpty()) {
                BarLine(timed.map { it.text }, LongArray(timed.size) { timed[it].startMs }, LongArray(timed.size) { timed[it].startMs + maxOf(120L, timed[it].durationMs) })
            } else {
                val words = l.text.trim().split(WORD_GAP).filter { it.isNotEmpty() }
                val end = lines.getOrNull(index + 1)?.timeMs ?: (l.timeMs + 5000L)
                val starts = LyricScene.wordTimes(words.toTypedArray(), l.timeMs, maxOf(400L, end - l.timeMs))
                BarLine(words, starts, LongArray(words.size) { if (it + 1 < words.size) starts[it + 1] else end })
            }
        }
        return barLine!! to pos
    }

    /**
     * The line karaoke style: each word fills in from the left as it is sung, the fill gliding
     * with a soft edge (KaraokeWipe). [alpha] fades the whole line; [light] puts the shader
     * light on the edge of the word being sung. Words still to come are the accent at half strength.
     */
    private fun drawKaraoke(g: GuiGraphics, font: net.minecraft.client.gui.Font, line: BarLine, pos: Long, x0: Int, y: Int, maxW: Int, sung: Int, shadow: Boolean, alpha: Float, light: Boolean) {
        if (alpha < 0.03f) return
        val widths = line.measure(font)
        val space = font.width(" ")
        val sungColor = ClientUi.alpha(sung, alpha)
        val waitingColor = ClientUi.alpha(sung, 0.45f * alpha)
        val glow = if (light) ClientUi.alpha(sung, 0.45f * alpha) else 0
        var x = x0
        for (i in line.words.indices) {
            val w = widths[i]
            if (x + w - x0 > maxW) {
                if (x + font.width("...") - x0 <= maxW + 6) g.drawString(font, "...", x, y, waitingColor, shadow)
                break
            }
            val fill = when {
                pos <= line.starts[i] -> 0f
                pos >= line.ends[i] -> 1f
                else -> (pos - line.starts[i]).toFloat() / (line.ends[i] - line.starts[i])
            }
            dev.wyz.clientcore.lyrics.KaraokeWipe.word(g, font, line.parts[i], x, y, w, fill, sungColor, waitingColor, shadow, glow)
            x += w + space
        }
    }

    // ------------------------------------------------------------------ full player

    private fun fullBarW(client: Minecraft, t: Track): Int =
        maxOf(client.font.width(t.title), client.font.width(t.artist)).coerceIn(90, 150)

    private fun fullSize(client: Minecraft, t: Track, cursorFree: Boolean): IntArray {
        val font = client.font
        val showArt = flag("np_art", true)
        val showBar = flag("np_progress", true) && t.duration > 0
        val showTimes = flag("np_times", true) && t.duration > 0
        val showControls = cursorFree && flag("np_controls", true)
        val textH = font.lineHeight + 2 +
            (if (t.artist.isNotBlank()) font.lineHeight + 1 else 0) +
            (if (showBar) BAR_H + 2 + (if (showTimes) font.lineHeight + 1 else 0) else 0) +
            (if (showControls) BTN + 1 else 0)
        val w = (if (showArt) COVER + 6 else 0) + fullBarW(client, t)
        return intArrayOf(w, maxOf(if (showArt) COVER else 0, textH))
    }

    private fun drawFull(client: Minecraft, graphics: GuiGraphics, t: Track, ox: Int, oy: Int, cursorFree: Boolean) {
        val font = client.font
        val showArt = flag("np_art", true)
        val showBar = flag("np_progress", true) && t.duration > 0
        val showTimes = flag("np_times", true) && t.duration > 0
        val showControls = cursorFree && flag("np_controls", true)
        val barW = fullBarW(client, t)
        val st = effectiveStyle()
        val shadow = st.textShadow
        val times = labelColor
        val bar = if (st.accentColor >= 0) panelAccentColor() else SPOTIFY_GREEN
        val tx = ox + if (showArt) COVER + 6 else 0

        if (showArt) drawCover(graphics, t, ox, oy, COVER)

        var ty = oy
        graphics.drawString(font, trim(font, t.title, barW), tx, ty, panelTextColor(), shadow)
        ty += font.lineHeight + 2
        if (t.artist.isNotBlank()) {
            graphics.drawString(font, trim(font, t.artist, barW), tx, ty, times, shadow)
            ty += font.lineHeight + 1
        }

        if (showBar) {
            val done = elapsed(t)
            val ratio = (done.toFloat() / t.duration.toFloat()).coerceIn(0f, 1f)
            val fill = (barW * ratio).toInt().coerceAtLeast(1)
            ClientUi.fillRounded(graphics, tx, ty, barW, BAR_H, 1, 0x66000000)
            ClientUi.fillRounded(graphics, tx, ty, fill, BAR_H, 1, if (t.playing) bar else ClientUi.DIM)
            ty += BAR_H + 2
            if (showTimes) {
                graphics.drawString(font, clock(done), tx, ty, times, shadow)
                val right = clock(t.duration)
                graphics.drawString(font, right, tx + barW - font.width(right), ty, times, shadow)
                ty += font.lineHeight + 1
            }
        }

        if (showControls) {
            drawControls(graphics, font, tx, ty + 1, t)
            controlsShown = true
        }
    }

    /** Album cover at [size], or the Spotify mark until art arrives. */
    private fun drawCover(graphics: GuiGraphics, t: Track, ox: Int, oy: Int, size: Int) {
        val art = cover(t.artFile)
        if (art != null) {
            graphics.riverBlit(art, ox, oy, 0f, 0f, size, size, coverW, coverH, coverW, coverH)
        } else {
            ClientUi.fillRounded(graphics, ox, oy, size, size, if (size < COVER) 2 else ClientUi.RADIUS_CARD, ClientUi.PANEL_ALT)
            val inset = size / 5
            logo()?.let { graphics.riverBlit(it, ox + inset, oy + inset, 0f, 0f, size - inset * 2, size - inset * 2, 64, 64, 64, 64) }
        }
    }

    // ------------------------------------------------------------------ mouse

    /** The cursor in this module's own drawing space (the HUD may be scaled around x, y). */
    private fun mouse(client: Minecraft): DoubleArray {
//? if >=1.21.9 {
        val mx = client.mouseHandler.getScaledXPos(client.window)
        val my = client.mouseHandler.getScaledYPos(client.window)
//?} else {
/*        val mx = client.mouseHandler.xpos() * client.window.guiScaledWidth / client.window.screenWidth
        val my = client.mouseHandler.ypos() * client.window.guiScaledHeight / client.window.screenHeight
*///?}
        val s = scaleFactor().toDouble()
        return doubleArrayOf(x + (mx - x) / s, y + (my - y) / s)
    }

    /**
     * A menu click, before a button under the panel can take it: on a control it runs that
     * control, anywhere else on the compact bar it keeps the player open (or lets it close).
     */
    fun onMenuClick(client: Minecraft): Boolean {
        if (!active || !overMenu(client)) return false
//? if >=1.21.9 {
        val mx = client.mouseHandler.getScaledXPos(client.window)
        val my = client.mouseHandler.getScaledYPos(client.window)
//?} else {
/*        val mx = client.mouseHandler.xpos() * client.window.guiScaledWidth / client.window.screenWidth
        val my = client.mouseHandler.ypos() * client.window.guiScaledHeight / client.window.screenHeight
*///?}
        if (onControlClick(mx, my)) return true
        val local = mouse(client)
        if (flag("np_compact", true) && hit(panelBox, local[0], local[1])) {
            pinned = !pinned
            return true
        }
        return false
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
