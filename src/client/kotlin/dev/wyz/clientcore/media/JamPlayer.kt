package dev.wyz.clientcore.media

import com.google.gson.JsonParser
import dev.wyz.clientcore.config.ConfigService
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native Spotify control is opt-in for each invitation. Only track URIs can be opened. */
internal object JamPlayer {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "River Music Jam").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    private val os = System.getProperty("os.name").lowercase()
    @Volatile private var generation = 0
    @Volatile private var nextAt = 0L
    @Volatile private var opened = ""
    @Volatile private var cachedKey = ""
    @Volatile private var cachedUri = ""
    @Volatile private var process: Process? = null
    @Volatile var message = ""
        private set
    private var failures = 0

    fun stop() {
        generation++
        process?.destroyForcibly()
        opened = ""
        nextAt = 0
        failures = 0
        message = ""
        runCatching { Files.deleteIfExists(ConfigService.riverDirectory().resolve("media/jam-command.json")) }
    }

    fun uriFor(song: MusicJam.Song): String {
        JamTimeline.spotifyUri(song.uri)?.let { return it }
        if (!os.contains("mac")) return ""
        val key = "${song.title}|${song.artist}"
        if (cachedKey == key) return cachedUri
        submit {
            val json = JsonParser.parseString(mac("read")).asJsonObject
            if (json.get("title")?.asString == song.title && json.get("artist")?.asString == song.artist) {
                cachedKey = key
                cachedUri = JamTimeline.spotifyUri(json.get("uri")?.asString.orEmpty()).orEmpty()
            }
        }
        return ""
    }

    fun sync(track: MediaSession.Track) {
        val uri = JamTimeline.spotifyUri(track.trackUri) ?: run { message = "Lyrics only. The host has no Spotify track link."; return }
        submit {
            val ok = if (os.contains("mac")) {
                mac("sync", uri, track.title, track.artist, track.positionMs.toString(), track.playing.toString()) == "ok"
            } else {
                if (opened != uri) {
                    if (os.contains("win")) {
                        val child = ProcessBuilder("explorer.exe", uri).start()
                        child.waitFor(3, TimeUnit.SECONDS)
                    } else if (!MprisReader.openJam(uri)) throw IllegalStateException("Open Spotify to join playback.")
                    opened = uri
                }
                MediaSession.syncJam(track)
            }
            if (!ok) throw IllegalStateException("Waiting for Spotify. Lyrics remain synced.")
            message = ""
        }
    }

    private fun submit(action: () -> Unit) {
        val now = System.nanoTime() / 1_000_000
        if (now < nextAt || !busy.compareAndSet(false, true)) return
        val expected = generation
        nextAt = now + 5000
        worker.submit task@{
            try {
                if (expected != generation) return@task
                action()
                failures = 0
            } catch (_: Exception) {
                if (expected == generation) {
                    failures++
                    nextAt = System.nanoTime() / 1_000_000 + (5000L shl failures.coerceAtMost(3))
                    message = "Spotify is unavailable. Lyrics remain synced."
                }
            } finally { busy.set(false) }
        }
    }

    private fun mac(vararg args: String): String {
        val directory = ConfigService.riverDirectory().resolve("media")
        Files.createDirectories(directory)
        val file = directory.resolve("jam-spotify.js")
        val source = JamPlayer::class.java.getResourceAsStream("/assets/clientcore/media/jam-spotify.js")?.use { it.readBytes() }
            ?: throw IllegalStateException("Spotify helper is missing.")
        Files.write(file, source)
        val child = ProcessBuilder(listOf("/usr/bin/osascript", "-l", "JavaScript", file.toString()) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = child
        try {
            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); throw IllegalStateException("Spotify timed out.") }
            if (child.exitValue() != 0) throw IllegalStateException("Spotify is unavailable.")
            return child.inputStream.bufferedReader().use { it.readText().trim() }
        } finally { process = null }
    }
}
