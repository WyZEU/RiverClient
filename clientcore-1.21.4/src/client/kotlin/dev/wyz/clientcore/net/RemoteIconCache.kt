package dev.wyz.clientcore.net

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Loads remote project images (Modrinth icons and gallery shots) into GPU textures once
 * each. Two things make icons load reliably no matter what:
 *  - decoding falls back from NativeImage's PNG-only reader to ImageIO (JPEG, GIF, BMP),
 *    which is re-encoded to PNG so NativeImage can take it. Modrinth serves plenty of
 *    non-PNG icons, and the old code dropped every one of them.
 *  - a failed fetch/decode is retried (up to a cap, with a cooldown) instead of being
 *    given up on forever, so a transient hiccup never leaves a permanent blank.
 * The network fetch and decode run off-thread; the texture upload is hopped to the client
 * main thread, where textures must be registered.
 */
object RemoteIconCache {

    data class Icon(val id: ResourceLocation, val width: Int, val height: Int)

    private class State {
        @Volatile var icon: Icon? = null
        @Volatile var inFlight = false
        @Volatile var lastAttempt = 0L
        val attempts = AtomicInteger(0)
    }

    private const val MAX_ATTEMPTS = 8
    private const val COOLDOWN_MS = 4000L

    private val states = ConcurrentHashMap<String, State>()
    private val counter = AtomicInteger()

    private val pool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "River-Icons").apply { isDaemon = true }
    }

    fun get(url: String): Icon? {
        if (url.isBlank()) return null
        val state = states.computeIfAbsent(url) { State() }
        state.icon?.let { return it }

        val now = System.currentTimeMillis()
        if (!state.inFlight && state.attempts.get() < MAX_ATTEMPTS && now - state.lastAttempt > COOLDOWN_MS) {
            state.inFlight = true
            state.lastAttempt = now
            state.attempts.incrementAndGet()
            load(url, state)
        }
        return null
    }

    private fun load(url: String, state: State) {
        pool.submit {
            val image = runCatching {
                val bytes = fetch(url)
                decode(bytes)
            }.getOrNull()

            if (image == null) {
                state.inFlight = false
                return@submit
            }

            Minecraft.getInstance().execute {
                runCatching {
                    val id = ResourceLocation.fromNamespaceAndPath("clientcore", "content_icon_${counter.incrementAndGet()}")
                    Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
                    state.icon = Icon(id, image.width, image.height)
                }.onFailure { image.close() }
                state.inFlight = false
            }
        }
    }

    private fun fetch(url: String): ByteArray {
        val connection = (URL(proxied(url)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 12000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "riverclient.xyz/river-client")
        }
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Modrinth serves almost every icon and gallery image as WebP, which neither
     * NativeImage nor stock ImageIO can decode - so nothing loaded. Route the fetch
     * through the weserv image proxy, which re-encodes any format to PNG (that
     * NativeImage reads natively) and caps the size so gallery shots stay light.
     */
    private fun proxied(url: String): String {
        if (url.startsWith("https://wsrv.nl/")) return url
        val encoded = URLEncoder.encode(url, StandardCharsets.UTF_8)
        // w=768 caps big gallery shots; &we ("without enlargement") keeps small icons at
        // their native size instead of blowing a 96px icon up into a half-megabyte PNG.
        return "https://wsrv.nl/?url=$encoded&output=png&w=768&we"
    }

    /** PNG fast path, then an ImageIO fallback (re-encoded to PNG) for anything else. */
    private fun decode(bytes: ByteArray): NativeImage? {
        runCatching { return ByteArrayInputStream(bytes).use { NativeImage.read(it) } }
        return runCatching {
            val buffered = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return null
            val png = ByteArrayOutputStream().use { out ->
                ImageIO.write(buffered, "PNG", out)
                out.toByteArray()
            }
            ByteArrayInputStream(png).use { NativeImage.read(it) }
        }.getOrNull()
    }
}
