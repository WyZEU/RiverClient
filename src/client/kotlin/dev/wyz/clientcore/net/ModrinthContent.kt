package dev.wyz.clientcore.net

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Tiny Modrinth API client for the in-game content browser. Searches and downloads
 * shaders, resource packs and data packs for 1.21.11. No API key is needed for Modrinth,
 * and every call runs on a small daemon pool so the render/tick loop is never blocked;
 * results are handed back on the client main thread via Minecraft.execute.
 *
 * Purely a download convenience: it fetches content the user explicitly asks for into the
 * normal game folders. It reads and writes files, nothing that affects multiplayer play.
 */
object ModrinthContent {

    private const val API = "https://api.modrinth.com/v2"
    private const val GAME_VERSION = "1.21.11"
    private const val USER_AGENT = "riverclient.xyz/river-client (in-game content browser)"
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 10000

    enum class Type(
        val projectType: String,
        val loaders: List<String>,
        val label: String,
        val icon: String,
        val categories: List<String>
    ) {
        SHADER("shader", listOf("iris", "optifine", "canvas"), "Shaders", "sparkle",
            listOf("fantasy", "realistic", "semi-realistic", "vanilla-like", "cartoon")),
        RESOURCE_PACK("resourcepack", listOf("minecraft"), "Resource Packs", "grid",
            listOf("16x", "32x", "64x", "realistic", "simplistic", "cartoon")),
        DATA_PACK("datapack", listOf("datapack"), "Data Packs", "flask",
            listOf("adventure", "game-mechanics", "utility", "worldgen", "magic", "mobs"))
    }

    enum class Sort(val index: String, val label: String) {
        RELEVANT("relevance", "Relevant"),
        POPULAR("downloads", "Popular"),
        NEWEST("newest", "Newest"),
        UPDATED("updated", "Updated")
    }

    data class Hit(
        val projectId: String,
        val slug: String,
        val title: String,
        val description: String,
        val author: String,
        val downloads: Int,
        val iconUrl: String
    )

    data class Detail(
        val body: String,
        val gallery: List<String>,
        val categories: List<String>
    )

    private val pool = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "River-Content").apply { isDaemon = true }
    }

    fun searchAsync(type: Type, query: String, sort: Sort, categories: Set<String>, onDone: (Result<List<Hit>>) -> Unit) {
        pool.submit {
            val result = runCatching { search(type, query, sort, categories) }
            Minecraft.getInstance().execute { onDone(result) }
        }
    }

    fun detailAsync(projectId: String, onDone: (Result<Detail>) -> Unit) {
        pool.submit {
            val result = runCatching { detail(projectId) }
            Minecraft.getInstance().execute { onDone(result) }
        }
    }

    fun downloadAsync(type: Type, hit: Hit, targetDir: Path, onDone: (Result<String>) -> Unit) {
        pool.submit {
            val result = runCatching { downloadNow(type, hit, targetDir) }
            Minecraft.getInstance().execute { onDone(result) }
        }
    }

    private fun search(type: Type, query: String, sort: Sort, categories: Set<String>): List<Hit> {
        val facetGroups = buildList {
            add("[\"project_type:${type.projectType}\"]")
            add("[\"versions:$GAME_VERSION\"]")
            for (category in categories) add("[\"categories:$category\"]")
        }
        val facets = "[" + facetGroups.joinToString(",") + "]"
        // Empty query with "relevance" returns almost nothing, so fall back to popularity.
        val index = if (query.isBlank() && sort == Sort.RELEVANT) Sort.POPULAR.index else sort.index
        val url = "$API/search?limit=60&index=$index&query=${enc(query)}&facets=${enc(facets)}"
        val body = get(url) ?: return emptyList()
        val hits = JsonParser.parseString(body).asJsonObject.getAsJsonArray("hits") ?: return emptyList()
        return hits.mapNotNull { element ->
            val obj = element.asJsonObject
            val id = obj.get("project_id")?.asString ?: return@mapNotNull null
            Hit(
                projectId = id,
                slug = obj.get("slug")?.asString ?: id,
                title = obj.get("title")?.asString ?: id,
                description = obj.get("description")?.asString ?: "",
                author = obj.get("author")?.asString ?: "",
                downloads = obj.get("downloads")?.asInt ?: 0,
                iconUrl = obj.get("icon_url")?.asString ?: ""
            )
        }
    }

    private fun detail(projectId: String): Detail {
        val body = get("$API/project/$projectId") ?: throw IllegalStateException("Could not reach Modrinth.")
        val obj = JsonParser.parseString(body).asJsonObject
        val gallery = obj.getAsJsonArray("gallery")?.mapNotNull { element ->
            val galleryObj = element.asJsonObject
            val url = galleryObj.get("url")?.asString ?: return@mapNotNull null
            val featured = galleryObj.get("featured")?.asBoolean == true
            url to featured
        }?.sortedByDescending { it.second }?.map { it.first } ?: emptyList()
        val categories = obj.getAsJsonArray("categories")?.mapNotNull { it.asString } ?: emptyList()
        return Detail(
            body = obj.get("body")?.asString ?: "",
            gallery = gallery,
            categories = categories
        )
    }

    /** Resolves the newest compatible file for a project and streams it into [targetDir]. */
    private fun downloadNow(type: Type, hit: Hit, targetDir: Path): String {
        val loaders = "[" + type.loaders.joinToString(",") { "\"$it\"" } + "]"
        val versionsUrl = "$API/project/${hit.projectId}/version" +
            "?loaders=${enc(loaders)}&game_versions=${enc("[\"$GAME_VERSION\"]")}"
        val body = get(versionsUrl) ?: throw IllegalStateException("Could not reach Modrinth.")
        val versions = JsonParser.parseString(body).asJsonArray
        if (versions.isEmpty) throw IllegalStateException("No 1.21.11 build is available.")
        val file = pickFile(versions) ?: throw IllegalStateException("No downloadable file was found.")
        Files.createDirectories(targetDir)
        val dest = targetDir.resolve(sanitize(file.second))
        download(file.first, dest)
        return dest.fileName.toString()
    }

    /** Versions come newest-first; take the first one that has a usable file. */
    private fun pickFile(versions: JsonArray): Pair<String, String>? {
        for (version in versions) {
            val files = version.asJsonObject.getAsJsonArray("files") ?: continue
            val chosen = files.firstOrNull { it.asJsonObject.get("primary")?.asBoolean == true }
                ?: files.firstOrNull()
            val obj = (chosen ?: continue).asJsonObject
            val url = obj.get("url")?.asString ?: continue
            val name = obj.get("filename")?.asString ?: continue
            return url to name
        }
        return null
    }

    private fun get(url: String): String? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, dest: Path) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                Files.newOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
