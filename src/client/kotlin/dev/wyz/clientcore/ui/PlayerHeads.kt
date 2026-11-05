package dev.wyz.clientcore.ui

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.compat.riverBlit
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Players' faces, cut out of their own skins.
 *
 * A friend is someone you recognise by their skin long before you read their name, so the
 * friends screen and the message popups draw the face rather than a coloured initial. The
 * skin comes from Mojang's own session server, the same source the launcher uses, rather
 * than an avatar service (those are blocked on some networks and would leave a hole).
 *
 * Everything network is off the render thread; the texture is registered back on it. A
 * face that is still loading, or that failed, draws as a quiet square with the player's
 * initial, so the layout never jumps when it arrives.
 */
object PlayerHeads {

    private const val CONNECT_TIMEOUT = 5000
    private const val READ_TIMEOUT = 8000
    /** Mojang rate-limits by IP, so a failure is not retried for a while. */
    private const val RETRY_AFTER_MS = 90_000L

    private class Entry {
        @Volatile var texture: McId? = null
        @Volatile var textureHeight = 64
        @Volatile var failedAt = 0L
        @Volatile var loading = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "River-Heads").apply { isDaemon = true }
    }

    private fun key(uuid: String, name: String): String =
        uuid.replace("-", "").lowercase().ifEmpty { "name:" + name.lowercase() }

    /** True once the face for this player is ready to draw. */
    fun ready(uuid: String, name: String): Boolean = entries[key(uuid, name)]?.texture != null

    /**
     * Draws the face at [size] pixels, hat layer included. Starts loading it the first time
     * it is asked for.
     */
    /**
     * [dim] draws the face darkened, the way an offline friend or a blocked player is shown.
     */
    fun draw(g: GuiGraphics, uuid: String, name: String, x: Int, y: Int, size: Int, dim: Boolean = false, alpha: Float = 1f) {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        val entry = request(uuid, name)
        val texture = entry.texture
        if (texture == null) {
            g.fill(x, y, x + size, y + size, ClientUi.alpha(ClientUi.PANEL_ALT, alpha))
            val font = Minecraft.getInstance().font
            val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            if (size >= 12) {
                g.drawString(font, initial, x + (size - font.width(initial) + 1) / 2, y + (size - 7) / 2, ClientUi.alpha(if (dim) ClientUi.DIM else ClientUi.MUTED, alpha), false)
            }
            return
        }
        val th = entry.textureHeight
        val tint = ((if (dim) DIM_TINT else -1) and 0x00FFFFFF) or (a shl 24)
        // Face, then the hat layer over it, the way the game draws a head.
        g.riverBlit(texture, x, y, 8f, 8f, size, size, 8, 8, 64, th, tint)
        g.riverBlit(texture, x, y, 40f, 8f, size, size, 8, 8, 64, th, tint)
    }

    private const val DIM_TINT = 0xFF6F6F6F.toInt()

    private fun request(uuid: String, name: String): Entry {
        val k = key(uuid, name)
        val entry = entries.getOrPut(k) { Entry() }
        if (entry.texture != null || entry.loading) return entry
        if (entry.failedAt != 0L && System.currentTimeMillis() - entry.failedAt < RETRY_AFTER_MS) return entry
        entry.loading = true
        val client = Minecraft.getInstance()
        pool.submit {
            val bytes = runCatching { download(uuid, name) }.getOrNull()
            client.execute {
                entry.loading = false
                if (bytes == null) {
                    entry.failedAt = System.currentTimeMillis()
                    return@execute
                }
                runCatching {
                    val image = ByteArrayInputStream(bytes).use { NativeImage.read(it) }
                    val id = McId.fromNamespaceAndPath("clientcore", "river_head_" + k.replace(Regex("[^a-z0-9_]"), "_"))
//? if >=1.21.5 {
                    client.textureManager.register(id, DynamicTexture({ "river-head-$k" }, image))
//?} else {
/*                    client.textureManager.register(id, DynamicTexture(image))
*///?}
                    entry.textureHeight = image.height
                    entry.texture = id
                }.onFailure { entry.failedAt = System.currentTimeMillis() }
            }
        }
        return entry
    }

    /** The skin PNG for this player, or null. */
    private fun download(uuid: String, name: String): ByteArray? {
        val id = uuid.replace("-", "").takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) } ?: lookupUuid(name) ?: return null
        val profile = getText("https://sessionserver.mojang.com/session/minecraft/profile/$id") ?: return null
        val properties = JsonParser.parseString(profile).asJsonObject.getAsJsonArray("properties") ?: return null
        val textures = properties.firstOrNull { it.asJsonObject.get("name")?.asString == "textures" }
            ?.asJsonObject?.get("value")?.asString ?: return null
        val decoded = String(Base64.getDecoder().decode(textures), StandardCharsets.UTF_8)
        val skinUrl = JsonParser.parseString(decoded).asJsonObject
            .getAsJsonObject("textures")?.getAsJsonObject("SKIN")?.get("url")?.asString ?: return null
        return getBytes(skinUrl.replace("http://", "https://"))
    }

    private fun lookupUuid(name: String): String? {
        if (name.isBlank()) return null
        val text = getText("https://api.mojang.com/users/profiles/minecraft/${name.trim()}") ?: return null
        return JsonParser.parseString(text).asJsonObject.get("id")?.asString
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
        }

    private fun getText(url: String): String? = getBytes(url)?.toString(StandardCharsets.UTF_8)

    private fun getBytes(url: String): ByteArray? {
        val connection = open(url)
        return try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
