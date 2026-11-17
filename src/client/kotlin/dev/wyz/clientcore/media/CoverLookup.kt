package dev.wyz.clientcore.media

import com.google.gson.JsonParser
import dev.wyz.clientcore.config.ConfigService
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Album art for songs whose player gives none (Windows without River Launcher, most Linux
 * players). Looks the song up on Apple's public iTunes search by title and artist, the same
 * kind of lookup lyrics already make, and keeps the cover as a PNG (the only format the game
 * reads) in River's folder, so each song is fetched once.
 */
object CoverLookup {
    private const val RETRY_MS = 10 * 60_000L
    private val dir: Path by lazy { ConfigService.riverDirectory().resolve("media").resolve("covers") }
    /** Songs being fetched (0) or that found nothing, with when they failed. */
    private val tried = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "River Cover Lookup").apply { isDaemon = true }
    }

    /** The cover's path when it is already here, otherwise "" and a lookup starts. */
    fun fileFor(title: String, artist: String): String {
        if (title.isBlank()) return ""
        val key = keyOf(title, artist)
        val file = dir.resolve("$key.png")
        if (Files.isRegularFile(file)) return file.toString()
        val failedAt = tried[key]
        if (failedAt != null && (failedAt == 0L || System.currentTimeMillis() - failedAt < RETRY_MS)) return ""
        tried[key] = 0L
        pool.execute {
            val ok = runCatching { fetch(title, artist, file) }.getOrDefault(false)
            if (ok) tried.remove(key) else tried[key] = System.currentTimeMillis()
        }
        return ""
    }

    private fun fetch(title: String, artist: String, file: Path): Boolean {
        val term = URLEncoder.encode("$artist $title".trim(), StandardCharsets.UTF_8)
        val search = get("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=$term") ?: return false
        val results = JsonParser.parseString(String(search, StandardCharsets.UTF_8)).asJsonObject.getAsJsonArray("results") ?: return false
        // The first result by the same artist; a search on a title alone can land on a cover version.
        val wanted = simple(artist.split(",", "&", " feat", " ft.").first())
        val hit = results.map { it.asJsonObject }.firstOrNull { result ->
            val name = simple(result.get("artistName")?.asString.orEmpty())
            wanted.isEmpty() || name.contains(wanted) || wanted.contains(name)
        } ?: return false
        val small = hit.get("artworkUrl100")?.asString ?: return false
        val url = small.replace("100x100bb", "300x300bb")
        if (!URI.create(url).host.orEmpty().endsWith(".mzstatic.com")) return false
        val bytes = get(url) ?: return false
        val image = ImageIO.read(bytes.inputStream()) ?: return false
        Files.createDirectories(dir)
        val temp = dir.resolve("${file.fileName}.part")
        ImageIO.write(image, "png", temp.toFile())
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }

    private fun get(url: String): ByteArray? {
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 10000
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readNBytes(4 * 1024 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun simple(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun keyOf(title: String, artist: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${title.trim().lowercase()}|${artist.trim().lowercase()}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}
