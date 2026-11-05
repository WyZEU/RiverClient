package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.lyrics.LrcParser
import dev.wyz.clientcore.lyrics.LyricFonts
import dev.wyz.clientcore.lyrics.LyricLine
import dev.wyz.clientcore.lyrics.LyricScene
import dev.wyz.clientcore.lyrics.LyricsService
import dev.wyz.clientcore.lyrics.LyricsState
import dev.wyz.clientcore.media.MediaSession
import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.settings.hint
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * Lyrics for the song that is playing, sung into the world: each word pops up around your
 * crosshair the moment it is sung, glowing, and fades a moment later (or whole lines appear and
 * light up word by word). A plain line on the screen can be shown as well.
 *
 * The song comes from the Windows media session (MediaSession), so it works whichever launcher
 * started the game; the launcher's Now Playing feed is the fallback. Off until switched on,
 * because the lyrics sites are told the song and artist name.
 */
class LyricsModule : Module("lyrics", "Lyrics", "Synced lyrics for the song you are playing", ModuleCategory.VISUAL, "sparkle", 8, 292, false) {
    override val hudStack: HudStack = HudStack.TOP_LEFT
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL

    private class Song(val title: String, val artist: String, val album: String, val durationSec: Int, val positionMs: Long, val playing: Boolean)

    private companion object {
        /** World size of one font pixel at 100%. */
        const val BASE_UNIT = 0.05f
        /** Width of the on-screen lyric box at 100%, in GUI pixels. */
        const val BOX_W = 240
        val SHOW = listOf("Words", "Lines")
        val LAYOUTS = listOf("Scatter", "Center")
        val ALIGNS = listOf("Left", "Center", "Right")
        val ANIMATIONS = listOf("Fade", "Slide up", "None")
        val STYLES = listOf("Bar", "Plain")
    }

    /** The words in the world; LyricsWorldRenderer draws them. */
    val scene = LyricScene()

    private var trackKey = ""
    private var state: LyricsState = LyricsState.Missing
    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var changedAt = 0L
    private var playing = false
    private var hasTrack = false
    private var previousIndex = -1
    private var songPos = 0L
    private var songPosAt = 0L
    private val spanCache = HashMap<Int, Spans>()
    private var shownWidth = 0f
    private var lastFrameAt = 0L

    /** Set by the dev screenshots: a made-up song on a clock, so no player or network is needed. */
    private var demoStart = 0L
    /** A real song for the demo instead (DevShot -Driver.devshot.song): title, artist, album, seconds, and where it starts. */
    private var demoSong: Song? = null

    // ------------------------------------------------------------------ settings

    fun showWorld() = flag("ly_world", true)
    private fun showScreen() = flag("ly_screen", false)
    private fun offsetMs() = scalar("ly_offset", 0).coerceIn(-3000, 3000)
    private fun lineMode() = scalar("ly_show", 0).coerceIn(0, 1) == 1
    private fun centered() = scalar("ly_layout", 0).coerceIn(0, 1) == 1
    fun face() = scalar("ly_font", LyricFonts.WIDE).coerceIn(0, LyricFonts.NAMES.size - 1)
    private fun uppercase() = flag("ly_upper", false)
    private fun sizePercent() = scalar("ly_size", 100).coerceIn(50, 250)
    fun opacity() = scalar("ly_opacity", 100).coerceIn(10, 100) / 100f
    fun glow() = scalar("ly_glow", 60).coerceIn(0, 100) / 100f
    fun outline() = scalar("ly_outline", 60).coerceIn(0, 100) / 100f
    fun blur() = scalar("ly_blur", 50).coerceIn(0, 100) / 100f
    fun bounce() = scalar("ly_bounce", 100).coerceIn(0, 100) / 100f
    private fun near() = scalar("ly_near", 4).coerceIn(2, 12)
    private fun far() = scalar("ly_far", 8).coerceIn(2, 16)
    fun follow() = flag("ly_follow", false)
    fun throughWalls(): Boolean = flag("ly_walls", false)
    fun flicker() = flag("ly_flicker", false)
    fun textColor() = scalar("ly_wcolor", 0xFFFFFF) and 0xFFFFFF
    fun glowColor() = scalar("ly_gcolor", 0xDDE9FF) and 0xFFFFFF
    private fun align() = scalar("ly_align", 1).coerceIn(0, 2)
    private fun animation() = scalar("ly_anim", 0).coerceIn(0, 2)
    private fun screenColor() = scalar("ly_color", 0xFFFFFFFF.toInt())
    private fun nextColor() = scalar("ly_next_color", 0xFF8C94A6.toInt())
    private fun screenStyle() = scalar("ly_sstyle", 0).coerceIn(0, 1)
    private fun screenFace() = scalar("ly_sfont", 3).coerceIn(0, LyricFonts.NAMES.size - 1)
    private fun waitingColor() = scalar("ly_dim", 0xFF7C8496.toInt())

    /** World size of one font pixel for the chosen size and font. */
    fun unit(): Float = BASE_UNIT * sizePercent() / 100f * LyricFonts.sizeFactor(face())

    fun sceneConfig() = LyricScene.Config(lineMode(), centered(), uppercase(), near().toFloat(), far().toFloat(), follow(), face(), unit())

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Lyrics"))
        list.add(BoolSetting("In the world", { showWorld() }, { setFlag("ly_world", it) })
            .hint("Lyrics come from NetEase, KuGou and lrclib.net."))
        list.add(BoolSetting("On screen", { showScreen() }, { setFlag("ly_screen", it) }))
        list.add(IntSetting("Sync offset", -3000, 3000, { offsetMs() }, { setScalar("ly_offset", it) }, " ms")
            .hint("Raise it if the words come late."))

        if (showWorld()) {
            list.add(SectionSetting("Text"))
            list.add(ChoiceSetting("Show", SHOW, { SHOW[if (lineMode()) 1 else 0] }, { setScalar("ly_show", SHOW.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Layout", LAYOUTS, { LAYOUTS[if (centered()) 1 else 0] }, { setScalar("ly_layout", LAYOUTS.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Font", LyricFonts.NAMES, { LyricFonts.NAMES[face()] }, { setScalar("ly_font", LyricFonts.NAMES.indexOf(it).coerceAtLeast(0)) }))
            list.add(BoolSetting("Uppercase", { uppercase() }, { setFlag("ly_upper", it) }))
            list.add(IntSetting("Size", 50, 250, { sizePercent() }, { setScalar("ly_size", it) }, "%"))
            list.add(ColorSetting("Text color", false, { textColor() or 0xFF000000.toInt() }, { setScalar("ly_wcolor", it and 0xFFFFFF) }))

            list.add(SectionSetting("Look"))
            list.add(IntSetting("Opacity", 10, 100, { (opacity() * 100).toInt() }, { setScalar("ly_opacity", it) }, "%"))
            list.add(IntSetting("Glow", 0, 100, { (glow() * 100).toInt() }, { setScalar("ly_glow", it) }, "%"))
            if (glow() > 0f) {
                list.add(ColorSetting("Glow color", false, { glowColor() or 0xFF000000.toInt() }, { setScalar("ly_gcolor", it and 0xFFFFFF) }))
            }
            list.add(IntSetting("Outline", 0, 100, { (outline() * 100).toInt() }, { setScalar("ly_outline", it) }, "%"))
            list.add(IntSetting("Motion blur", 0, 100, { (blur() * 100).toInt() }, { setScalar("ly_blur", it) }, "%"))
            list.add(IntSetting("Bounce", 0, 100, { (bounce() * 100).toInt() }, { setScalar("ly_bounce", it) }, "%"))
            list.add(BoolSetting("Flicker", { flicker() }, { setFlag("ly_flicker", it) }))

            list.add(SectionSetting("Placement"))
            list.add(IntSetting("Closest", 2, 12, { near() }, { setScalar("ly_near", it); if (far() < it) setScalar("ly_far", it) }, " blocks"))
            list.add(IntSetting("Farthest", 2, 16, { far() }, { setScalar("ly_far", it); if (near() > it) setScalar("ly_near", it) }, " blocks"))
            list.add(BoolSetting("Follow you", { follow() }, { setFlag("ly_follow", it) }))
            list.add(BoolSetting("Through walls", { throughWalls() }, { setFlag("ly_walls", it) }))
        }

        if (showScreen()) {
            list.add(SectionSetting("On screen"))
            list.add(IntSetting("Screen size", 50, 200, { editorScalePercent() }, { editorSetScalePercent(it) }, "%"))
            list.add(ChoiceSetting("Align", ALIGNS, { ALIGNS[align()] }, { setScalar("ly_align", ALIGNS.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Style", STYLES, { STYLES[screenStyle()] }, { setScalar("ly_sstyle", STYLES.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Animation", ANIMATIONS, { ANIMATIONS[animation()] }, { setScalar("ly_anim", ANIMATIONS.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Screen font", LyricFonts.NAMES, { LyricFonts.NAMES[screenFace()] }, { setScalar("ly_sfont", LyricFonts.NAMES.indexOf(it).coerceAtLeast(0)) }))
            list.add(ColorSetting("Sung color", false, { screenColor() }, { setScalar("ly_color", it or 0xFF000000.toInt()) }))
            list.add(ColorSetting("Waiting color", false, { waitingColor() }, { setScalar("ly_dim", it or 0xFF000000.toInt()) }))
            list.add(BoolSetting("Next line", { flag("ly_next", true) }, { setFlag("ly_next", it) }))
            if (flag("ly_next", true)) {
                list.add(ColorSetting("Next line color", false, { nextColor() }, { setScalar("ly_next_color", it or 0xFF000000.toInt()) }))
            }
        }
    }

    // ------------------------------------------------------------------ the song

    private fun song(now: Long): Song? {
        if (demoStart != 0L) return demoSong?.let { Song(it.title, it.artist, it.album, it.durationSec, it.positionMs + now - demoStart, true) }
            ?: Song("River Light", "Demo", "", 180, now - demoStart, true)
        MediaSession.current()?.let {
            return Song(it.title, it.artist, it.album, (it.durationMs / 1000L).toInt(), it.positionMs, it.playing)
        }
        val pb = ModuleRegistry.get<NowPlayingModule>("now_playing")?.playback() ?: return null
        return Song(pb.title, pb.artist, pb.album, pb.durationSec, pb.positionMs, pb.playing)
    }

    override fun tick(client: Minecraft) {
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val pb = song(now)
        if (pb == null) {
            hasTrack = false
            lines = emptyList()
            currentIndex = -1
            trackKey = ""
            scene.fadeAll(now)
            return
        }
        hasTrack = true
        val wasPlaying = playing
        playing = pb.playing
        val key = "${pb.title}|${pb.artist}|${pb.durationSec}"
        if (key != trackKey) {
            trackKey = key
            currentIndex = -1
            previousIndex = -1
            spanCache.clear()
            scene.fadeAll(now)
        }
        state = LyricsService.stateFor(pb.title, pb.artist, pb.album, pb.durationSec)
        lines = (state as? LyricsState.Ready)?.lines.orEmpty()

        val position = pb.positionMs + offsetMs()
        songPos = position
        songPosAt = now
        val index = LrcParser.indexAt(lines, position)
        // A resume starts the line over, so its words are sung again rather than skipped.
        if (index != currentIndex || (pb.playing && !wasPlaying)) {
            if (index != currentIndex) previousIndex = currentIndex
            currentIndex = index
            changedAt = now
            val line = lines.getOrNull(index)
            if (line == null || !pb.playing || !showWorld() || line.text.isBlank()) {
                scene.fadeAll(now)
            } else {
                val end = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5000L)
                scene.setLine(client, line, end, position, now, sceneConfig())
            }
        }
        // Paused: what is up there fades out rather than staying for as long as the pause.
        if (!pb.playing) scene.fadeAll(now)
    }

    // ------------------------------------------------------------------ on the screen

    /** One line's words with when each starts and ends, in song milliseconds. */
    private class Spans(val words: List<String>, val starts: LongArray, val ends: LongArray)

    /** Word timing for line [index]: the real one when the lyrics have it, otherwise a guess. */
    private fun spans(index: Int): Spans? {
        val line = lines.getOrNull(index) ?: return null
        if (line.text.isBlank()) return null
        return spanCache.getOrPut(index) {
            val timed = line.words
            if (timed != null) {
                Spans(timed.map { it.text }, LongArray(timed.size) { timed[it].startMs }, LongArray(timed.size) { timed[it].startMs + maxOf(120L, timed[it].durationMs) })
            } else {
                val words = line.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val end = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 5000L)
                val starts = LyricScene.wordTimes(words.toTypedArray(), line.timeMs, maxOf(400L, end - line.timeMs))
                Spans(words, starts, LongArray(words.size) { if (it + 1 < words.size) starts[it + 1] else end })
            }
        }
    }

    /** Where the song is now, in song milliseconds (offset included), carried on between ticks. */
    private fun songNow(now: Long): Long = songPos + if (playing) now - songPosAt else 0L

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val scale = scaleFactor()
        return Pair((BOX_W * scale).toInt(), (33 * scale).toInt())
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (!showScreen() || !hasTrack) return
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        when (state) {
            LyricsState.Loading -> drawScreen(client, graphics, now, null, null, null, 0L, "Finding lyrics...")
            LyricsState.Missing -> drawScreen(client, graphics, now, null, null, null, 0L, "No synced lyrics for this song")
            is LyricsState.Ready -> {
                val current = spans(currentIndex) ?: return
                val next = if (flag("ly_next", true)) lines.getOrNull(currentIndex + 1)?.text?.takeIf { it.isNotBlank() } else null
                drawScreen(client, graphics, now, current, spans(previousIndex), next, songNow(now), null)
            }
        }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val words = listOf("Your", "lyrics", "show", "up", "here")
        val preview = Spans(words, LongArray(5) { it * 400L }, LongArray(5) { it * 400L + 400L })
        drawScreen(client, graphics, System.currentTimeMillis(), preview, null, if (flag("ly_next", true)) "and the next line below" else null, 1000L, null, animate = false)
    }

    // Plain text whatever HUD style this module once had saved: lyrics have their own look.
    private fun drawScreen(client: Minecraft, g: GuiGraphics, now: Long, current: Spans?, previous: Spans?, next: String?, pos: Long, note: String?, animate: Boolean = true) =
        dev.wyz.clientcore.ui.RiverText.withHudStyle(false, false) { drawScreenPlain(client, g, now, current, previous, next, pos, note, animate) }

    /**
     * The line being sung, karaoke style: each word fills in from the left as it is sung, in the
     * time the lyrics give it. In the Bar style it sits on a frosted glass bar with a small
     * equalizer, and the bar eases to the width of each new line.
     */
    private fun drawScreenPlain(client: Minecraft, g: GuiGraphics, now: Long, current: Spans?, previous: Spans?, next: String?, pos: Long, note: String?, animate: Boolean) {
        val font = client.font
        val face = screenFace()
        val bar = screenStyle() == 0
        val padX = 8
        val eq = 9
        val gap = 6
        val maxText = BOX_W - if (bar) padX * 2 + eq + gap else 0
        val lineW = if (current != null) wordsWidth(client, current.words, face) else font.width(LyricFonts.styled(note.orEmpty(), face, false))
        // Wide enough for the next line too, so it never spills out of the bar.
        val nextW = if (next != null) font.width(LyricFonts.styled(next, face, false)) else 0
        val textW = minOf(maxOf(lineW, nextW), maxText)
        val height = if (bar) 22 + (if (next != null) 11 else 0) else 9 + (if (next != null) 12 else 0)
        val targetW = textW + if (bar) padX * 2 + eq + gap else 0

        // The bar glides to each line's width instead of jumping.
        val dt = ((now - lastFrameAt).coerceIn(0L, 100L)) / 1000f
        lastFrameAt = now
        shownWidth = if (!animate || shownWidth <= 0f) targetW.toFloat() else shownWidth + (targetW - shownWidth) * minOf(1f, dt * 14f)
        val w = shownWidth.toInt()
        val bx = when (align()) {
            0 -> x
            2 -> x + BOX_W - w
            else -> x + (BOX_W - w) / 2
        }
        if (bar) {
            dev.wyz.clientcore.ui.Glass.surface(g, bx, y, w, height, 6, 0xE60C0E13.toInt(), 0x0C0E13, 0.55f)
            drawEqualizer(g, bx + padX, y + 7, now)
        }
        val textX = bx + if (bar) padX + eq + gap else 0
        val textY = y + if (bar) 7 else 0
        val sung = screenColor() or 0xFF000000.toInt()
        val waiting = dev.wyz.clientcore.ui.RiverColor.resolve(waitingColor() or 0xFF000000.toInt())

        if (note != null) {
            g.drawString(font, LyricFonts.styled(note, face, false), textX, textY, ClientUi.alpha(waiting, 0.9f), !bar)
        } else if (current != null) {
            val t = if (!animate || animation() == 2) 1f else ((now - changedAt) / 280f).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            val slide = animation() == 1
            if (t < 1f && previous != null) {
                // The line before leaves upward (or just fades), fully sung.
                val lift = if (slide) (eased * 6f).toInt() else 0
                drawKaraoke(client, g, previous, face, textX, textY - lift, maxText, Long.MAX_VALUE, sung, waiting, 1f - eased, !bar)
            }
            val rise = if (slide) ((1f - eased) * 6f).toInt() else 0
            drawKaraoke(client, g, current, face, textX, textY + rise, maxText, pos, sung, waiting, eased, !bar)
        }
        if (next != null) {
            g.drawString(font, LyricFonts.styled(trim(client, next, maxText, face), face, false), textX, textY + if (bar) 11 else 12, ClientUi.alpha(dev.wyz.clientcore.ui.RiverColor.resolve(nextColor() or 0xFF000000.toInt()), 0.8f), !bar)
        }
    }

    /** Words drawn left to right; a word being sung is split where the singing has got to. */
    private fun drawKaraoke(client: Minecraft, g: GuiGraphics, spans: Spans, face: Int, x0: Int, y: Int, maxW: Int, pos: Long, sung: Int, waiting: Int, alpha: Float, shadow: Boolean) {
        if (alpha < 0.03f) return
        val font = client.font
        val space = font.width(LyricFonts.styled(" ", face, false))
        val waitingColor = ClientUi.alpha(waiting, alpha)
        var x = x0
        for (i in spans.words.indices) {
            val word = spans.words[i]
            val bold = pos >= spans.starts[i] && pos < spans.ends[i]
            val text = LyricFonts.styled(word, face, bold)
            val w = font.width(text)
            if (x + w - x0 > maxW) {
                g.drawString(font, LyricFonts.styled("...", face, false), x, y, waitingColor, shadow)
                break
            }
            // Rainbow runs along the line, each word a step further round the colours.
            val sungColor = ClientUi.alpha(dev.wyz.clientcore.ui.RiverColor.resolve(sung, i * 0.06f), alpha)
            val fill = when {
                pos <= spans.starts[i] -> 0f
                pos >= spans.ends[i] -> 1f
                else -> (pos - spans.starts[i]).toFloat() / (spans.ends[i] - spans.starts[i])
            }
            when {
                fill >= 1f -> g.drawString(font, text, x, y, sungColor, shadow)
                fill <= 0f -> g.drawString(font, text, x, y, waitingColor, shadow)
                else -> {
                    // Split at the letter the singing has reached.
                    var cut = 0
                    while (cut < word.length && font.width(LyricFonts.styled(word.substring(0, cut + 1), face, bold)) <= w * fill) cut++
                    val head = LyricFonts.styled(word.substring(0, cut), face, bold)
                    g.drawString(font, head, x, y, sungColor, shadow)
                    g.drawString(font, LyricFonts.styled(word.substring(cut), face, bold), x + font.width(head), y, waitingColor, shadow)
                }
            }
            x += w + space
        }
    }

    /** Three small bars that bounce while the song plays and lie flat when it is paused. */
    private fun drawEqualizer(g: GuiGraphics, x0: Int, y0: Int, now: Long) {
        val color = ClientUi.alpha(dev.wyz.clientcore.ui.RiverColor.resolve(screenColor() or 0xFF000000.toInt()), 0.9f)
        for (i in 0 until 3) {
            val level = if (playing) 0.5 + 0.5 * kotlin.math.sin(now / (150.0 + i * 37) + i * 1.9) else 0.0
            val h = 2 + (level * 7).toInt()
            val bx = x0 + i * 3
            g.fill(bx, y0 + 9 - h, bx + 2, y0 + 9, color)
        }
    }

    private fun wordsWidth(client: Minecraft, words: List<String>, face: Int): Int {
        val font = client.font
        val space = font.width(LyricFonts.styled(" ", face, false))
        return words.sumOf { font.width(LyricFonts.styled(it, face, false)) } + space * (words.size - 1).coerceAtLeast(0)
    }

    private fun trim(client: Minecraft, text: String, maxWidth: Int, face: Int): String {
        val font = client.font
        if (font.width(LyricFonts.styled(text, face, false)) <= maxWidth) return text
        var out = text
        while (out.isNotEmpty() && font.width(LyricFonts.styled("$out...", face, false)) > maxWidth) out = out.dropLast(1)
        return "$out..."
    }
    // ------------------------------------------------------------------ dev screenshots

    /** Starts a made-up song with its lyrics, for the hidden screenshot runs. Never used in a normal game. */
    fun startDemo(lines: Boolean = false, screen: Boolean = false, song: String? = null) {
        setScalar("ly_show", if (lines) 1 else 0)
        setFlag("ly_screen", screen)
        scene.clear()
        spanCache.clear()
        currentIndex = -1
        val sample = listOf(
            "Floating down the river light",
            "Every ripple takes the night",
            "Carry me where the water sings",
            "Underneath a thousand wings",
            "Follow the current home"
        )
        LyricsService.seed("River Light", "Demo", 180, sample.mapIndexed { i, text -> LyricLine(i * 2600L + 300L, text) })
        demoSong = song?.split('|')?.takeIf { it.size == 5 }?.let {
            Song(it[0], it[1], it[2], it[3].toInt(), it[4].toLong(), true)
        }
        enabled = true
        setFlag("ly_world", true)
        demoStart = dev.wyz.clientcore.dev.DevClock.millis()
    }
}
