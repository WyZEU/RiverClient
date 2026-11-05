package dev.wyz.clientcore.lyrics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/**
 * Word-timed lyrics from KuGou's public lyrics API, for the songs NetEase has no word timing for.
 * Only a result whose title, artist and length match is used. Returns null when KuGou has nothing
 * for the song. Called from LyricsService's background thread.
 */
object KuGouLyrics {
    private const val SEARCH = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=&keyword="
    private const val DOWNLOAD = "https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8"
    private const val MAX_TRIES = 3
    /** The fixed key KRC files are scrambled with. */
    private val KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun find(title: String, artist: String, durationSec: Int): List<LyricLine>? {
        val song = LyricsService.cleanTitle(title)
        val name = normalize(song)
        val artistParts = artist.split(',', '&', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val artists = artistParts.map { normalize(it) }.filter { it.isNotEmpty() }
        if (name.isEmpty()) return null
        val keyword = listOfNotNull(artistParts.firstOrNull(), song).joinToString(" - ")
        val length = if (durationSec > 0) "&duration=${durationSec * 1000L}" else ""
        val found = get(SEARCH + enc(keyword) + length)?.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val matching = found.mapNotNull { it as? JsonObject }.filter { candidate ->
            val candidateName = normalize(LyricsService.cleanTitle(text(candidate, "song")))
            val singer = normalize(text(candidate, "singer"))
            val lengthMs = candidate.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            candidateName.isNotEmpty() && (candidateName.startsWith(name) || name.startsWith(candidateName)) &&
                (artists.isEmpty() || artists.any { singer.contains(it) || (singer.isNotEmpty() && it.contains(singer)) }) &&
                (durationSec <= 0 || lengthMs <= 0 || abs(lengthMs - durationSec * 1000L) <= 4000L)
        }.sortedBy { abs((it.get("duration")?.takeIf { d -> !d.isJsonNull }?.asLong ?: 0L) - durationSec * 1000L) }

        for (candidate in matching.take(MAX_TRIES)) {
            val content = get(DOWNLOAD + "&id=${enc(text(candidate, "id"))}&accesskey=${enc(text(candidate, "accesskey"))}")
                ?.get("content")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val krc = runCatching { decode(content) }.getOrNull() ?: continue
            val lines = LrcParser.parseKrc(krc)
            if (lines.size >= 4) return lines
        }
        return null
    }

    /** A KRC file: base64 of "krc1", then zlib data scrambled with [KEY]. */
    private fun decode(content: String): String? {
        val raw = Base64.getDecoder().decode(content)
        if (raw.size < 4 || String(raw, 0, 4, StandardCharsets.US_ASCII) != "krc1") return null
        val packed = ByteArray(raw.size - 4) { (raw[it + 4].toInt() xor KEY[it % KEY.size]).toByte() }
        return InflaterInputStream(ByteArrayInputStream(packed)).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    }

    private fun text(obj: JsonObject, key: String): String = obj.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    /** Lower case, letters and digits only: "Blinding Lights" and "blinding lights!" match. */
    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun get(url: String): JsonObject? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0 (River Client)")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("kugou answered ${response.statusCode()}")
        return JsonParser.parseString(response.body()) as? JsonObject
    }
}
