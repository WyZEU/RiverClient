package dev.wyz.clientcore.net

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.ModOrigin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

/**
 * Self-update for the standalone mod: River as a plain Fabric jar in someone's own launcher
 * (Prism, the Modrinth App, ...), where nothing else keeps it current.
 *
 * When [ModUpdateCheck] finds a newer release, this reads the signed release manifest (the same
 * latest.json the River launcher trusts), checks its Ed25519 signature against the key built into
 * River, picks the jar for this Minecraft version and checks its SHA-256. The new jar waits next to
 * the old one as `<name>.update`, which Fabric ignores. When the game closes the old jar is removed
 * and the new one takes its place, so the next start runs the new version. Windows keeps a loaded
 * jar locked until the game has exited, so there a small hidden helper finishes the swap a few
 * seconds after Minecraft closes. Anything unexpected and it does nothing: the old jar keeps working.
 */
object ModSelfUpdate {
    private const val MANIFEST = "https://updates.riverclient.xyz/latest.json"
    private const val DOWNLOADS = "https://updates.riverclient.xyz/"
    /** The updater's public key, the same one in launcher/src/config/update-public-key.txt. */
    private const val PUBLIC_KEY = "MCowBQYDK2VwAyEAe6LtTQdFDalTTuAaoMDQ5ivkszatFz9Y4KoplagVdbw="
    private val JAR_NAME = Regex("^clientcore-[A-Za-z0-9.+_-]+\\.jar$")

    /** The version downloaded and waiting for a restart, or "" when there is none. */
    @Volatile var ready = ""
        private set

    @Volatile private var started = false

    /** Downloads [version] when it is newer than [ownVersion]. Runs on the update-check thread. */
    fun fetch(http: HttpClient, ownVersion: String, version: String) {
        if (started || FabricLoader.getInstance().isDevelopmentEnvironment) return
        started = true
        runCatching {
            val ownJar = ownJar() ?: return
            val mods = ownJar.parent
            val minecraft = FabricLoader.getInstance().getModContainer("minecraft")
                .map { it.metadata.version.friendlyString }.orElse("")
            if (minecraft.isEmpty()) return

            val manifestText = get(http, MANIFEST, ownVersion, HttpResponse.BodyHandlers.ofString()) ?: return
            val manifest = JsonParser.parseString(manifestText).asJsonObject
            if (!verified(manifest)) return
            if (manifest.get("version")?.asString != version) return

            val entry = manifest.getAsJsonArray("mods")?.map { it.asJsonObject }
                ?.firstOrNull { it.get("minecraft")?.asString == minecraft } ?: return
            val name = entry.get("name")?.asString.orEmpty()
            val url = entry.get("url")?.asString.orEmpty()
            val sha = entry.get("sha256")?.asString.orEmpty().lowercase()
            val size = entry.get("size")?.asLong ?: -1L
            if (!JAR_NAME.matches(name) || !url.startsWith(DOWNLOADS) || !sha.matches(Regex("^[0-9a-f]{64}$"))) return
            if (name == ownJar.fileName.toString()) return

            // Old leftovers from an update that never finished.
            Files.list(mods).use { files ->
                files.filter { it.fileName.toString().endsWith(".jar.update") || it.fileName.toString().endsWith(".jar.part") }
                    .forEach { runCatching { Files.delete(it) } }
            }
            val part = mods.resolve("$name.part")
            val waiting = mods.resolve("$name.update")
            val bytes = get(http, url, ownVersion, HttpResponse.BodyHandlers.ofByteArray()) ?: return
            if (size >= 0 && bytes.size.toLong() != size) return
            if (sha256(bytes) != sha) return
            Files.write(part, bytes)
            Files.move(part, waiting, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            installOnExit(ownJar, waiting, mods.resolve(name))
            ready = version
        }
    }

    /** This jar, when River runs from a single jar in a mods folder; null in a dev run or anything odd. */
    private fun ownJar(): Path? {
        val container = FabricLoader.getInstance().getModContainer("clientcore").orElse(null) ?: return null
        val origin = container.origin
        if (origin.kind != ModOrigin.Kind.PATH) return null
        val path = origin.paths.singleOrNull()?.toAbsolutePath() ?: return null
        if (!Files.isRegularFile(path) || !JAR_NAME.matches(path.fileName.toString())) return null
        if (path.parent == null || !Files.isWritable(path.parent)) return null
        return path
    }

    private fun installOnExit(oldJar: Path, waiting: Path, newJar: Path) {
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching {
                if (!Files.exists(waiting)) return@runCatching
                val deleted = runCatching { Files.deleteIfExists(oldJar); true }.getOrDefault(false)
                if (deleted) {
                    Files.move(waiting, newJar, StandardCopyOption.REPLACE_EXISTING)
                } else if (System.getProperty("os.name").lowercase().contains("win")) {
                    // The running game still holds the jar. Once it has exited, remove the old jar and
                    // only then move the new one in, so the two never both load.
                    val old = oldJar.toString()
                    val script = "ping -n 6 127.0.0.1 >nul & del /f /q \"$old\" & if not exist \"$old\" move /y \"$waiting\" \"$newJar\""
                    ProcessBuilder("cmd.exe", "/d", "/c", script)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
            }
        }, "river-self-update"))
    }

    private fun <T> get(http: HttpClient, url: String, ownVersion: String, handler: HttpResponse.BodyHandler<T>): T? {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
            .header("User-Agent", "RiverClient-Mod/$ownVersion").GET().build()
        val response = http.send(request, handler)
        return if (response.statusCode() == 200) response.body() else null
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** True only when the manifest's Ed25519 signature checks out against River's public key. */
    private fun verified(manifest: JsonObject): Boolean = runCatching {
        val signature = manifest.get("signature")?.asString ?: return false
        val unsigned = JsonObject().also { copy -> manifest.entrySet().forEach { (k, v) -> if (k != "signature") copy.add(k, v) } }
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(canonical(unsigned).toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    /**
     * The exact text the publisher signed: launcher/src/update-signature.js `canonicalize`. Keys
     * sorted (by UTF-16 units, as JavaScript sorts), no spaces, strings escaped like JSON.stringify
     * and numbers kept exactly as written.
     */
    internal fun canonical(value: JsonElement): String = when {
        value.isJsonNull -> "null"
        value is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        value is JsonObject -> value.keySet().sorted().joinToString(",", "{", "}") { "${quote(it)}:${canonical(value.get(it))}" }
        value is JsonPrimitive && value.isString -> quote(value.asString)
        value is JsonPrimitive && value.isBoolean -> value.asBoolean.toString()
        else -> value.asJsonPrimitive.asNumber.toString()
    }

    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                // Other control characters as \u00XX; everything else, non-ASCII included, as is.
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
