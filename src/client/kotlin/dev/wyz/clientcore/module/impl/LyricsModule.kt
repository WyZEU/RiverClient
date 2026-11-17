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
    // Switched on and set up from the Spotify module's panel; it still runs with that HUD off.
    override val showInMenu: Boolean = false

    /** The lyrics settings, shown inside the Spotify module's panel. */
    fun musicSettings(list: MutableList<Setting>) {
        val own = mutableListOf<Setting>()
        addModuleSettings(own)
        // Its own "Lyrics" heading is the Spotify panel's section already.
        list.addAll(if (own.firstOrNull() is SectionSetting) own.drop(1) else own)
    }

    private class Song(val title: String, val artist: String, val album: String, val durationSec: Int, val positionMs: Long, val playing: Boolean)

    private companion object {
        /** World size of one font pixel at 100%. */
        const val BASE_UNIT = 0.05f
        val SHOW = listOf("Words", "Lines")
        val LAYOUTS = listOf("Scatter", "Center")
    }

    /** The words in the world; LyricsWorldRenderer draws them. */
    val scene = LyricScene()

    private var trackKey = ""
    private var state: LyricsState = LyricsState.Missing
    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var playing = false
    private var songPos = 0L
    private var songPosAt = 0L

    /** Set by the dev screenshots: a made-up song on a clock, so no player or network is needed. */
    private var demoStart = 0L
    /** A real song for the demo instead (DevShot -Driver.devshot.song): title, artist, album, seconds, and where it starts. */
    private var demoSong: Song? = null

    // ------------------------------------------------------------------ settings

    fun showWorld() = flag("ly_world", true)
    /**
     * The line being sung shows in the Spotify module's compact bar instead of the song name.
     * Lyrics used to have a bar of their own (ly_screen); anyone who had it on gets this instead.
     */
    fun inSpotifyBar() = flag("ly_in_bar", false) || flag("ly_screen", false)
    fun offsetMs() = scalar("ly_offset", 0).coerceIn(-3000, 3000)
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

    /** World size of one font pixel for the chosen size and font. */
    fun unit(): Float = BASE_UNIT * sizePercent() / 100f * LyricFonts.sizeFactor(face())

    fun sceneConfig() = LyricScene.Config(lineMode(), centered(), uppercase(), near().toFloat(), far().toFloat(), follow(), face(), unit())

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Lyrics"))
        list.add(BoolSetting("In the world", { showWorld() }, { setFlag("ly_world", it) })
            .hint("Lyrics come from NetEase, KuGou and lrclib.net."))
        list.add(BoolSetting("On screen", { inSpotifyBar() }, {
            setFlag("ly_in_bar", it)
            setFlag("ly_screen", false)
        }).hint("The line being sung, in the Spotify bar in place of the song name."))
        list.add(IntSetting("Sync offset", -3000, 3000, { offsetMs() }, { setScalar("ly_offset", it) }, " ms")
            .hint("Raise it if the words come late."))

        if (showWorld()) {
            list.add(SectionSetting("Lyric text"))
            list.add(ChoiceSetting("Show", SHOW, { SHOW[if (lineMode()) 1 else 0] }, { setScalar("ly_show", SHOW.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Layout", LAYOUTS, { LAYOUTS[if (centered()) 1 else 0] }, { setScalar("ly_layout", LAYOUTS.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Font", LyricFonts.NAMES, { LyricFonts.NAMES[face()] }, { setScalar("ly_font", LyricFonts.NAMES.indexOf(it).coerceAtLeast(0)) }))
            list.add(BoolSetting("Uppercase", { uppercase() }, { setFlag("ly_upper", it) }))
            list.add(IntSetting("Lyric size", 50, 250, { sizePercent() }, { setScalar("ly_size", it) }, "%"))
            list.add(ColorSetting("Lyric color", false, { textColor() or 0xFF000000.toInt() }, { setScalar("ly_wcolor", it and 0xFFFFFF) }))

            list.add(SectionSetting("Lyric look"))
            list.add(IntSetting("Lyric opacity", 10, 100, { (opacity() * 100).toInt() }, { setScalar("ly_opacity", it) }, "%"))
            list.add(IntSetting("Glow", 0, 100, { (glow() * 100).toInt() }, { setScalar("ly_glow", it) }, "%"))
            if (glow() > 0f) {
                list.add(ColorSetting("Glow color", false, { glowColor() or 0xFF000000.toInt() }, { setScalar("ly_gcolor", it and 0xFFFFFF) }))
            }
            list.add(IntSetting("Outline", 0, 100, { (outline() * 100).toInt() }, { setScalar("ly_outline", it) }, "%"))
            list.add(IntSetting("Motion blur", 0, 100, { (blur() * 100).toInt() }, { setScalar("ly_blur", it) }, "%"))
            list.add(IntSetting("Bounce", 0, 100, { (bounce() * 100).toInt() }, { setScalar("ly_bounce", it) }, "%"))
            list.add(BoolSetting("Flicker", { flicker() }, { setFlag("ly_flicker", it) }))

            list.add(SectionSetting("Lyric placement"))
            list.add(IntSetting("Closest", 2, 12, { near() }, { setScalar("ly_near", it); if (far() < it) setScalar("ly_far", it) }, " blocks"))
            list.add(IntSetting("Farthest", 2, 16, { far() }, { setScalar("ly_far", it); if (near() > it) setScalar("ly_near", it) }, " blocks"))
            list.add(BoolSetting("Follow you", { follow() }, { setFlag("ly_follow", it) }))
            list.add(BoolSetting("Through walls", { throughWalls() }, { setFlag("ly_walls", it) }))
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
            lines = emptyList()
            currentIndex = -1
            trackKey = ""
            scene.fadeAll(now)
            return
        }
        val wasPlaying = playing
        playing = pb.playing
        val key = "${pb.title}|${pb.artist}|${pb.durationSec}"
        if (key != trackKey) {
            trackKey = key
            currentIndex = -1
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
            currentIndex = index
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
        scene.warmGlyphs(client)
    }

    // ------------------------------------------------------------------ on the screen

    // On-screen lyrics live in the Spotify bar now; this module draws only in the world.
    override fun acceptsDraggablePosition(): Boolean = false

    // ------------------------------------------------------------------ dev screenshots

    /** Starts a made-up song with its lyrics, for the hidden screenshot runs. Never used in a normal game. */
    fun startDemo(lines: Boolean = false, screen: Boolean = false, song: String? = null) {
        setScalar("ly_show", if (lines) 1 else 0)
        setFlag("ly_in_bar", screen)
        setFlag("ly_screen", false)
        scene.clear()
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
