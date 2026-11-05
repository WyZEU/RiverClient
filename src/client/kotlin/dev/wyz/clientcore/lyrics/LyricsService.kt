package dev.wyz.clientcore.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.wyz.clientcore.config.ConfigService
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

sealed interface LyricsState {
    object Loading : LyricsState
    object Missing : LyricsState
    class Ready(val lines: List<LyricLine>) : LyricsState
}

/**
 * Finds synced lyrics for a song: word-timed ones from NetEase Music when it has the song
 * (see NetEaseLyrics), otherwise line-timed ones from LRCLIB (lrclib.net). Neither needs an
 * account; the song and artist name are the only things sent. Everything runs
 * on one background thread, and every answer is kept on disk, "no lyrics" included, so a song
 * is asked about once. A request that fails (offline, the site down) is not remembered and
 * is tried again after half a minute.
 */
object LyricsService {
    private const val API = "https://lrclib.net/api/"
    private const val USER_AGENT = "River Client (https://riverclient.xyz)"
    private const val MISSING_RETRY_MS = 6L * 60 * 60 * 1000
    private const val FAILED_RETRY_MS = 30_000L
    private const val NEVER = Long.MAX_VALUE

    private class Entry(val state: LyricsState, val retryAt: Long)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "River Lyrics").apply { isDaemon = true } }
    private val entries = ConcurrentHashMap<String, Entry>()

    /** The lyrics for a song: the answer if there is one, otherwise [LyricsState.Loading] while it is fetched. */
    fun stateFor(title: String, artist: String, album: String, durationSec: Int): LyricsState {
        val key = keyOf(title, artist, durationSec)
        val known = entries[key]
        if (known != null && System.currentTimeMillis() < known.retryAt) return known.state
        entries[key] = Entry(LyricsState.Loading, NEVER)
        pool.execute { entries[key] = load(key, title, artist, album, durationSec) }
        return LyricsState.Loading
    }

    /** A state set directly, for the dev screenshots that must not touch the network. */
    fun seed(title: String, artist: String, durationSec: Int, lines: List<LyricLine>) {
        entries[keyOf(title, artist, durationSec)] = Entry(LyricsState.Ready(lines), NEVER)
    }

    private fun keyOf(title: String, artist: String, durationSec: Int): String =
        "${title.trim().lowercase()}|${artist.trim().lowercase()}|$durationSec"

    private fun load(key: String, title: String, artist: String, album: String, durationSec: Int): Entry {
        readCache(key)?.let { return it }
        return try {
            val (song, singer) = tidySong(title, artist)
            // Word-timed lyrics from NetEase or KuGou when they have the song, otherwise line-timed ones from LRCLIB.
            val timed = runCatching { NetEaseLyrics.find(song, singer, durationSec) }.getOrNull()
                ?: runCatching { KuGouLyrics.find(song, singer, durationSec) }.getOrNull()
            val lines = timed ?: fetchSynced(song, singer, album, durationSec)?.let { LrcParser.parse(it) }.orEmpty()
            if (lines.none { it.text.isNotBlank() }) {
                writeCache(key, null)
                Entry(LyricsState.Missing, System.currentTimeMillis() + MISSING_RETRY_MS)
            } else {
                writeCache(key, lines)
                Entry(LyricsState.Ready(lines), NEVER)
            }
        } catch (_: Exception) {
            Entry(LyricsState.Missing, System.currentTimeMillis() + FAILED_RETRY_MS)
        }
    }

    // ------------------------------------------------------------------ network

    /** The synced lyrics text, or null when LRCLIB has none for this song. Throws when it cannot be reached. */
    private fun fetchSynced(title: String, artist: String, album: String, durationSec: Int): String? {
        if (album.isNotBlank() && durationSec > 0) {
            val exact = getJson(
                API + "get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                    "&album_name=${enc(album)}&duration=$durationSec"
            )
            synced(exact)?.takeIf { plausible(it, durationSec) }?.let { return it }
        }
        // Titles carry things lyrics sites leave out: "- Remastered 2011", "(feat. X)".
        val search = getJson(API + "search?track_name=${enc(cleanTitle(title))}&artist_name=${enc(artist)}")
        val results = (search as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { plausible(synced(it), durationSec) }.orEmpty()
        val best = if (durationSec > 0) {
            results.filter { abs(durationOf(it) - durationSec) <= 4 }.minByOrNull { abs(durationOf(it) - durationSec) }
        } else {
            results.firstOrNull()
        }
        return synced(best)
    }

    /**
     * Anyone can upload to LRCLIB, and the odd entry is a test ("probe") or lyrics for a different
     * song. Real lyrics have more than a few lines and do not run past the end of the track.
     */
    private fun plausible(text: String?, durationSec: Int): Boolean {
        if (text == null) return false
        val lines = LrcParser.parse(text).filter { it.text.isNotBlank() }
        if (lines.size < 4) return false
        return durationSec <= 0 || lines.last().timeMs <= durationSec * 1000L + 8000L
    }

    private fun synced(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val value = obj.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
        return value?.takeIf { it.isNotBlank() }
    }

    private fun durationOf(obj: JsonObject): Double = obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

    private fun getJson(url: String): JsonElement? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> JsonParser.parseString(response.body())
            // 404 is "no such song"; 400 is a lookup the database cannot answer with what it was given.
            400, 404 -> null
            else -> throw IOException("lrclib answered ${response.statusCode()}")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /**
     * What a browser player reports, as the song and artist a lyrics site knows: "Song (Official
     * Video)" loses the tag, "Artist - Topic" and "ArtistVEVO" lose the suffix, and "Artist - Song"
     * from the artist's own channel is split.
     */
    fun tidySong(title: String, artist: String): Pair<String, String> {
        var song = title.replace(Regex("\\s*[\\(\\[][^)\\]]*\\b(official|video|audio|lyrics?|visuali[sz]er|hd|4k)\\b[^)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE), "").trim()
        var singer = artist.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s*VEVO$", RegexOption.IGNORE_CASE), "").trim()
        val dash = song.indexOf(" - ")
        if (dash > 0) {
            val left = song.substring(0, dash).trim()
            val squash = { value: String -> value.lowercase().filter { it.isLetterOrDigit() } }
            if (singer.isBlank() || squash(left) == squash(singer)) {
                singer = left
                song = song.substring(dash + 3).trim()
            }
        }
        return Pair(song.ifEmpty { title.trim() }, singer)
    }

    /** "Song (feat. X) - Remastered 2011" or "Song (with X)" to "Song". */
    fun cleanTitle(title: String): String {
        var out = title
        out = out.replace(Regex("\\s*[\\(\\[][^)\\]]*(feat|ft\\.|with |prod|remaster|version|edit|live|mix|deluxe|sped|slowed)[^)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("\\s+-\\s+.*(remaster|version|edit|live|mix|deluxe|single).*$", RegexOption.IGNORE_CASE), "")
        return out.trim().ifEmpty { title.trim() }
    }

    // ------------------------------------------------------------------ disk cache

    private fun cacheFile(key: String): Path {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ConfigService.riverDirectory().resolve("lyrics-v4").resolve("$hash.json")
    }

    private fun readCache(key: String): Entry? = runCatching {
        val file = cacheFile(key)
        if (!Files.exists(file)) return null
        val root = JsonParser.parseString(Files.readString(file)).asJsonObject
        if (root.get("ok")?.asBoolean == true) {
            val lines = root.getAsJsonArray("lines").map { element ->
                val row = element.asJsonArray
                val words = if (row.size() > 2) row[2].asJsonArray.map { w ->
                    val word = w.asJsonArray
                    LyricWord(word[0].asLong, word[1].asLong, word[2].asString)
                } else null
                LyricLine(row[0].asLong, row[1].asString, words)
            }
            Entry(LyricsState.Ready(lines), NEVER)
        } else {
            val retryAt = root.get("at").asLong + MISSING_RETRY_MS
            if (System.currentTimeMillis() < retryAt) Entry(LyricsState.Missing, retryAt) else null
        }
    }.getOrNull()

    private fun writeCache(key: String, lines: List<LyricLine>?) {
        runCatching {
            val root = JsonObject()
            if (lines != null) {
                root.addProperty("ok", true)
                root.add("lines", JsonArray().apply {
                    lines.forEach { line ->
                        add(JsonArray().apply {
                            add(line.timeMs)
                            add(line.text)
                            line.words?.let { words ->
                                add(JsonArray().apply { words.forEach { w -> add(JsonArray().apply { add(w.startMs); add(w.durationMs); add(w.text) }) } })
                            }
                        })
                    }
                })
            } else {
                root.addProperty("ok", false)
                root.addProperty("at", System.currentTimeMillis())
            }
            val file = cacheFile(key)
            Files.createDirectories(file.parent)
            Files.writeString(file, root.toString())
        }
    }
}
