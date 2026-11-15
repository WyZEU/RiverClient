package dev.wyz.clientcore.cosmetic

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/**
 * Creator capes whose art lives on the server (see "server capes" on the worker). A cape is
 * only here once its code has been redeemed: the token it hands back is kept on this PC, and
 * the art is fetched by that token into config/river-client/server-capes. Nothing in the mod
 * names or draws a server cape until then.
 *
 * Style ids are "sc-" plus the token, so they never collide with the built-in names.
 */
object ServerCapes {

    const val PREFIX = "sc-"
    private const val BASE = "https://updates.riverclient.xyz"

    data class Entry(val token: String, val name: String)

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    @Volatile
    private var entries: List<Entry>? = null

    private fun dir(): Path =
        Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("river-client").resolve("server-capes")

    private fun indexPath(): Path = dir().resolve("index.json")

    private fun artPath(token: String): Path = dir().resolve("$token.png")

    /** The redeemed server capes, in the order they were redeemed. Read from disk once. */
    fun all(): List<Entry> {
        entries?.let { return it }
        val loaded = runCatching {
            val file = indexPath()
            if (!Files.exists(file)) return@runCatching emptyList()
            JsonParser.parseString(Files.readString(file)).asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                val token = obj.get("token")?.asString ?: return@mapNotNull null
                if (!isToken(token)) return@mapNotNull null
                Entry(token, obj.get("name")?.asString.orEmpty())
            }
        }.getOrDefault(emptyList())
        entries = loaded
        return loaded
    }

    fun styleIds(): List<String> = all().map { PREFIX + it.token }

    fun labels(): List<String> = all().map { it.name.ifBlank { "Creator" } }

    /** The cape's PNG on disk, for the texture loader. Null when the art has not arrived yet. */
    fun open(style: String): InputStream? {
        val token = style.removePrefix(PREFIX)
        val file = artPath(token)
        return if (isToken(token) && Files.exists(file)) Files.newInputStream(file) else null
    }

    /** Remembers a redeemed cape and fetches its art in the background. */
    fun add(token: String, name: String) {
        if (!isToken(token)) return
        val current = all()
        if (current.none { it.token == token }) {
            entries = current + Entry(token, name.take(24))
            saveIndex()
        }
        fetchArt(token)
    }

    /** Fetches the art of any remembered cape that is missing it (e.g. after a failed download). */
    fun fetchMissing() {
        all().forEach { if (!Files.exists(artPath(it.token))) fetchArt(it.token) }
    }

    private fun saveIndex() {
        runCatching {
            Files.createDirectories(dir())
            val array = JsonArray()
            all().forEach { entry ->
                array.add(JsonObject().apply {
                    addProperty("token", entry.token)
                    addProperty("name", entry.name)
                })
            }
            Files.writeString(indexPath(), array.toString())
        }
    }

    private fun fetchArt(token: String) {
        Thread({
            try {
                val request = HttpRequest.newBuilder(URI.create("$BASE/cosmetics/cape?token=$token"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return@Thread
                val json = JsonParser.parseString(response.body()).asJsonObject
                if (json.get("ok")?.asBoolean != true) return@Thread
                val png = Base64.getDecoder().decode(json.get("png").asString)
                // Only a real PNG is written: a bad response must never become a cape.
                if (png.size < 8 || png[0] != 0x89.toByte() || png[1] != 'P'.code.toByte()) return@Thread
                Files.createDirectories(dir())
                Files.write(artPath(token), png)
                Minecraft.getInstance().execute { RiverCape.forget(PREFIX + token) }
            } catch (_: Exception) {
                // Offline: the next wardrobe visit tries again.
            }
        }, "river-server-cape").apply { isDaemon = true }.start()
    }

    private fun isToken(token: String): Boolean = token.length == 24 && token.all { it in '0'..'9' || it in 'a'..'f' }
}
