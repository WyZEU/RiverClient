package dev.wyz.clientcore.media

import com.google.gson.Gson
import dev.wyz.clientcore.config.ConfigService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What is playing on this PC, read by the game itself. The launcher already feeds the Now
 * Playing module, but only while it is the one that started the game, and only to the second;
 * lyrics need to work from any launcher and need the position to a few hundred milliseconds.
 *
 * On Windows one hidden PowerShell process reads the system media session (the same thing
 * the volume flyout shows) and prints a line every 400 ms. It is started the first time
 * something asks, stopped when nothing has asked for a while, and quits by itself if the game
 * goes away without stopping it. On Linux MprisReader does the same job. On macOS,
 * the bundled JXA reader reads the system Now Playing session through MediaRemote.
 */
object MediaSession {
    class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val positionMs: Long, val playing: Boolean, val artFile: String = "", val source: String = "", val trackUri: String = "")

    private class Line(
        val playing: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: Boolean = false,
        val rate: Double = 1.0,
        val artFile: String = "",
        val source: String = "",
        val trackUri: String = "",
        val at: Long = 0L
    )

    private const val IDLE_STOP_MS = 10_000L
    private const val STALE_MS = 3_000L
    private const val RESTART_DELAY_MS = 15_000L
    private const val MAX_LOGGED_LINES = 10

    private val gson = Gson()
    private val windows = System.getProperty("os.name", "").lowercase().contains("win")
    private val linux = System.getProperty("os.name", "").lowercase().contains("linux")
    private val mac = System.getProperty("os.name", "").lowercase().contains("mac")
    private val macDirectory by lazy { ConfigService.riverDirectory().resolve("media").resolve("mac-${ProcessHandle.current().pid()}") }

    @Volatile private var last: Line? = null
    @Volatile private var lastAt = 0L
    @Volatile private var unsupported = false
    @Volatile private var process: Process? = null
    private var lastAsked = 0L
    private var lastStart = 0L
    private var loggedLines = 0

    /** The current track, or null when nothing is playing, it is not known yet, or this OS has no reader. */
    @Synchronized
    fun current(): Track? {
        val now = System.currentTimeMillis()
        lastAsked = now
        if (linux) return MprisReader.current()
        if ((!windows && !mac) || unsupported) return null
        if (process?.isAlive != true && now - lastStart > RESTART_DELAY_MS) start(now)
        val line = last ?: return null
        if (now - lastAt > STALE_MS || line.title.isBlank()) return null
        val rate = if (mac) line.rate.takeIf { it.isFinite() }?.coerceIn(0.0, 4.0) ?: 0.0 else 1.0
        val position = if (line.playing) line.positionMs + ((now - lastAt) * rate).toLong() else line.positionMs
        val limit = if (line.durationMs > 0) line.durationMs else Long.MAX_VALUE
        return Track(line.title, line.artist, line.album, line.durationMs, position.coerceIn(0L, limit), line.playing, line.artFile, line.source, line.trackUri)
    }

    /** Stops the reader once nobody has asked for a while. Called every tick. */
    @Synchronized
    fun tick() {
        if (System.currentTimeMillis() - lastAsked > IDLE_STOP_MS) {
            if (linux) MprisReader.stop() else if (process != null) stop()
        } else if (mac && process?.isAlive == true && System.currentTimeMillis() - maxOf(lastAt, lastStart) > 8000L) {
            stop()
        }
    }

    /** Presses a media key on the playing app: "next", "prev" or "playpause". False when the reader is not running. */
    @Synchronized
    fun command(action: String): Boolean {
        if (action !in setOf("next", "prev", "playpause")) return false
        if (linux) return MprisReader.command(action)
        if (process?.isAlive != true) return false
        if (mac) {
            val line = last ?: return false
            val now = System.currentTimeMillis()
            if (line.title.isBlank() || line.source.isBlank() || now - lastAt > STALE_MS) return false
            return runCatching {
                val temp = macDirectory.resolve("command.tmp")
                Files.writeString(temp, gson.toJson(mapOf("action" to action, "source" to line.source, "at" to now)))
                Files.move(temp, macDirectory.resolve("command.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
        }
        return runCatching {
            Files.writeString(ConfigService.riverDirectory().resolve("media").resolve("command.txt"), action)
        }.isSuccess
    }

    @Synchronized
    fun stop() {
        if (mac) process?.destroyForcibly() else process?.destroy()
        process = null
        last = null
        if (mac) runCatching { Files.deleteIfExists(macDirectory.resolve("command.json")) }
    }

    /** The host clock only controls the matching Spotify session after an explicit join. */
    fun syncJam(track: Track): Boolean {
        if (linux) return MprisReader.syncJam(track)
        if (!windows) return false
        val current = current() ?: return false
        if (!current.source.contains("spotify", true) || current.title != track.title || current.artist != track.artist) return false
        return runCatching {
            val folder = ConfigService.riverDirectory().resolve("media")
            val temp = folder.resolve("jam-command.tmp")
            Files.writeString(temp, gson.toJson(mapOf("title" to track.title, "artist" to track.artist,
                "positionMs" to track.positionMs, "playing" to track.playing, "at" to System.currentTimeMillis())))
            Files.move(temp, folder.resolve("jam-command.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }

    /** PowerShell by its full path, because a PC's PATH can have lost that folder. */
    fun powershell(): String {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        val full = java.io.File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        return if (full.exists()) full.path else "powershell.exe"
    }

    private fun start(now: Long) {
        lastStart = now
        runCatching {
            if (mac) {
                Files.createDirectories(macDirectory)
                val script = macDirectory.resolve("session.js")
                val source = MediaSession::class.java.getResourceAsStream("/assets/clientcore/media/session-macos.js")?.use { it.readBytes() } ?: return
                Files.write(script, source)
                Files.deleteIfExists(macDirectory.resolve("command.json"))
                val started = ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", script.toString(), "--stream", ProcessHandle.current().pid().toString(), macDirectory.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                process = started
                Thread({ read(started) }, "River Mac Media").apply { isDaemon = true }.start()
                return
            }
            val script = ConfigService.riverDirectory().resolve("media").resolve("session.ps1")
            val source = MediaSession::class.java.getResourceAsStream("/assets/clientcore/media/session.ps1")?.use { it.readBytes() } ?: return
            Files.createDirectories(script.parent)
            if (!Files.exists(script) || !Files.readAllBytes(script).contentEquals(source)) Files.write(script, source)
            val started = ProcessBuilder(
                powershell(), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-GamePid", ProcessHandle.current().pid().toString()
            ).redirectErrorStream(true).start()
            process = started
            Thread({ read(started) }, "River Media Session").apply { isDaemon = true }.start()
        }.onFailure { println("[River] media reader could not start: $it") }
    }

    private fun read(started: Process) {
        runCatching {
            started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (text in lines) {
                    if (process !== started) return
                    val trimmed = text.trim()
                    if (!trimmed.startsWith("{")) {
                        // Goes in the game log, so a support ticket can show why nothing was read.
                        if (trimmed.isNotEmpty() && loggedLines++ < MAX_LOGGED_LINES) println("[River] media reader: $trimmed")
                        continue
                    }
                    val line = runCatching { gson.fromJson(trimmed, Line::class.java) }.getOrNull() ?: continue
                    if (line.error) {
                        // No media session API on this Windows build: stop trying.
                        println("[River] media reader: the system media session is unavailable")
                        if (!mac) unsupported = true
                        started.destroy()
                        return
                    }
                    if (mac && (line.at <= 0 || kotlin.math.abs(System.currentTimeMillis() - line.at) > STALE_MS)) continue
                    synchronized(this) {
                        if (process !== started) return
                        last = line
                        lastAt = if (mac) line.at else System.currentTimeMillis()
                    }
                }
            }
        }
    }
}
