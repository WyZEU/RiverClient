package dev.wyz.clientcore.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.abs

/**
 * Word-timed lyrics from NetEase Music's public web API: when every word starts and how long it
 * is held, which line-timed lyrics (LRCLIB) cannot give. The song is looked up by title and
 * artist, and only a result whose title, artist and length match is used, so a cover or a
 * different song of the same name is not taken. Returns null when NetEase has no word timing
 * for the song. Called from LyricsService's background thread.
 */
object NetEaseLyrics {
    private const val SEARCH = "https://music.163.com/api/search/get?type=1&limit=10&s="
    private const val LYRIC = "https://music.163.com/api/song/lyric/v1?lv=1&yv=1&tv=0&id="
    private const val MAX_TRIES = 4

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun find(title: String, artist: String, durationSec: Int): List<LyricLine>? {
        val name = normalize(LyricsService.cleanTitle(title))
        val artists = artist.split(',', '&', ';').map { normalize(it) }.filter { it.isNotEmpty() }
        if (name.isEmpty()) return null
        val query = URLEncoder.encode("${LyricsService.cleanTitle(title)} ${artists.firstOrNull().orEmpty()}".trim(), StandardCharsets.UTF_8)
        val songs = get(SEARCH + query)?.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return null

        var tries = 0
        for (element in songs) {
            val song = element as? JsonObject ?: continue
            // The same song, allowing for a tag one side adds ("Song (Remastered)" and "Song").
            val songName = normalize(LyricsService.cleanTitle(song.get("name")?.asString.orEmpty()))
            if (songName != name && !(songName.startsWith(name) || name.startsWith(songName)) || songName.isEmpty()) continue
            val songArtists = song.getAsJsonArray("artists")?.map { normalize((it as JsonObject).get("name")?.asString.orEmpty()) }.orEmpty()
            if (artists.isNotEmpty() && songArtists.none { a -> a.isNotEmpty() && artists.any { it.contains(a) || a.contains(it) } }) continue
            val lengthMs = song.get("duration")?.asLong ?: 0L
            if (durationSec > 0 && lengthMs > 0 && abs(lengthMs - durationSec * 1000L) > 4000L) continue
            if (++tries > MAX_TRIES) break
            val yrc = get(LYRIC + song.get("id").asLong)?.getAsJsonObject("yrc")?.get("lyric")?.takeIf { !it.isJsonNull }?.asString
            if (yrc.isNullOrBlank()) continue
            val lines = LrcParser.parseYrc(yrc)
            if (lines.size >= 4) return lines
        }
        return null
    }

    /** Lower case, letters and digits only: "Blinding Lights" and "blinding lights!" match. */
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private fun get(url: String): JsonObject? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0 (River Client)")
            .header("Referer", "https://music.163.com/")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("netease answered ${response.statusCode()}")
        return JsonParser.parseString(response.body()) as? JsonObject
    }
}
