package dev.wyz.clientcore.media

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit

/**
 * What is playing on Linux, including a Steam Deck. Music players and browsers (so Spotify,
 * YouTube Music and Apple Music in a tab) publish themselves over MPRIS on the session bus, and
 * busctl, part of systemd, reads it without River shipping anything.
 *
 * One background thread asks once a second, started the first time something wants a track and
 * stopped by MediaSession when nothing has asked for a while.
 */
internal object MprisReader {
    private const val PREFIX = "org.mpris.MediaPlayer2."
    private const val PATH = "/org/mpris/MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"
    private const val POLL_MS = 1_000L
    private const val STALE_MS = 3_000L

    private class Snapshot(val track: MediaSession.Track, val at: Long)

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var chosen: String? = null
    /** busctl is missing or cannot reach a session bus: stop trying. */
    @Volatile private var unavailable = false

    fun current(): MediaSession.Track? {
        if (unavailable) return null
        if (thread?.isAlive != true) start()
        val seen = snapshot ?: return null
        val now = System.currentTimeMillis()
        if (now - seen.at > STALE_MS) return null
        val t = seen.track
        val position = if (t.playing) t.positionMs + (now - seen.at) else t.positionMs
        val limit = if (t.durationMs > 0) t.durationMs else Long.MAX_VALUE
        return MediaSession.Track(t.title, t.artist, t.album, t.durationMs, position.coerceIn(0L, limit), t.playing)
    }

    fun stop() {
        thread?.interrupt()
        thread = null
        snapshot = null
    }

    /** "next", "prev" or "playpause" on the player being read. */
    fun command(action: String): Boolean {
        val name = chosen ?: return false
        val method = when (action) {
            "next" -> "Next"
            "prev" -> "Previous"
            "playpause" -> "PlayPause"
            else -> return false
        }
        return run("call", name, PATH, PLAYER, method) != null
    }

    private fun start() {
        thread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                snapshot = runCatching { poll() }.getOrNull()
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "River MPRIS").apply { isDaemon = true; start() }
    }

    private fun poll(): Snapshot? {
        val listing = run("list", "--no-legend", "--acquired")
        if (listing == null) {
            unavailable = true
            return null
        }
        val players = listing.lineSequence()
            .map { it.trim().substringBefore(' ') }
            .filter { it.startsWith(PREFIX) }
            .toList()
        val status = players.associateWith { stringProperty(it, "PlaybackStatus") }
        val name = pick(players, status) ?: return null
        chosen = name
        val metadata = property(name, "Metadata")?.getAsJsonArray("data")?.firstOrNull()?.asJsonObject ?: return null
        val title = text(metadata, "xesam:title")
        if (title.isBlank()) return null
        val position = property(name, "Position")?.get("data")?.asLong ?: 0L
        val track = MediaSession.Track(
            title,
            text(metadata, "xesam:artist"),
            text(metadata, "xesam:album"),
            (metadata.getAsJsonObject("mpris:length")?.get("data")?.asLong ?: 0L) / 1000L,
            position / 1000L,
            status[name] == "Playing"
        )
        return Snapshot(track, System.currentTimeMillis())
    }

    /** Whatever is playing, Spotify first; with nothing playing, the one last read so a pause keeps its song. */
    private fun pick(players: List<String>, status: Map<String, String?>): String? {
        val playing = players.filter { status[it] == "Playing" }
        return playing.firstOrNull { it.contains("spotify", ignoreCase = true) }
            ?: chosen?.takeIf { it in playing }
            ?: playing.firstOrNull()
            ?: chosen?.takeIf { it in players }
            ?: players.firstOrNull()
    }

    /** A string, or the first entry of a list of strings (artists are a list). */
    private fun text(metadata: JsonObject, key: String): String {
        val data = metadata.getAsJsonObject(key)?.get("data") ?: return ""
        return when {
            data.isJsonArray -> data.asJsonArray.firstOrNull()?.asString.orEmpty()
            data.isJsonPrimitive -> data.asString
            else -> ""
        }
    }

    private fun stringProperty(name: String, property: String): String? =
        runCatching { property(name, property)?.get("data")?.asString }.getOrNull()

    private fun property(name: String, property: String): JsonObject? {
        val out = run("get-property", "--json=short", name, PATH, PLAYER, property) ?: return null
        return runCatching { JsonParser.parseString(out).asJsonObject }.getOrNull()
    }

    /** busctl's output, or null when it could not run or failed. */
    private fun run(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("busctl", "--user") + args).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() == 0) out else null
    }.getOrNull()
}
