package dev.wyz.clientcore.cosmetic

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which gated capes this account has redeemed.
 *
 * Most capes are free. A gated one is made for a creator and claimed with a code they
 * hand to their audience, which is what "only their viewers get it" comes down to in
 * practice: a referral link counts a click and then serves the same installer to
 * everybody, so there is no way to tell an install came through one.
 *
 * This is the copy the wardrobe reads, and it decides what is offered, not what is
 * allowed. The real check is on the server, which drops a gated cape from the presence
 * roster unless the wearer has redeemed it - so editing a config to wear one shows it to
 * yourself and to nobody else.
 */
object CapeEntitlements {

    private const val BASE = "https://updates.riverclient.xyz"

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    /*
      What ships knowing it is gated. The server is the authority and replaces this on the
      first refresh, but the refresh is a network call and the wardrobe draws immediately:
      starting empty meant a gated cape was on the list, ungreyed, for as long as the fetch
      took. Since the mod ships the texture anyway, it can ship the fact that it is locked.
    */
    private val DEFAULT_GATED = setOf("axie")

    @Volatile
    private var gated: Set<String> = DEFAULT_GATED

    @Volatile
    private var owned: Set<String> = emptySet()

    private val refreshing = AtomicBoolean(false)

    /** True when this cape needs redeeming and has not been. */
    fun locked(style: String): Boolean = style in gated && style !in owned

    fun owns(style: String): Boolean = style in owned

    private fun selfUuid(): String? =
        Minecraft.getInstance().user?.profileId?.toString()

    /**
     * Refreshes both lists in the background. Called when the wardrobe opens rather than
     * on a timer: it is the only screen that cares, and a cosmetic list does not need to
     * be fresh to the second.
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        Thread({
            try {
                fetchGated()
                fetchOwned()
            } catch (_: Exception) {
                // Offline, or the worker is down. Whatever was last known stays; a failed
                // refresh must not silently take a cape off somebody who owns it.
            } finally {
                refreshing.set(false)
            }
        }, "river-cape-entitlements").apply { isDaemon = true }.start()
    }

    private fun fetchGated() {
        val body = get("$BASE/cosmetics/gated") ?: return
        val array = JsonParser.parseString(body).asJsonObject.getAsJsonArray("gated") ?: return
        gated = array.map { it.asString }.toSet()
    }

    private fun fetchOwned() {
        val uuid = selfUuid() ?: return
        val body = get("$BASE/cosmetics/owned?uuid=$uuid") ?: return
        val array = JsonParser.parseString(body).asJsonObject.getAsJsonArray("owned") ?: return
        owned = array.map { it.asString }.toSet()
    }

    private fun get(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(6))
            .header("User-Agent", "RiverClient")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() in 200..299) response.body() else null
    }

    /**
     * Sends a code. [onResult] is handed a message to show and whether it worked, on the
     * network thread, so callers must not touch the game from it directly.
     */
    fun redeem(rawCode: String, onResult: (Boolean, String) -> Unit) {
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }.take(24)
        if (code.isEmpty()) {
            onResult(false, "Enter a code.")
            return
        }
        val uuid = selfUuid()
        if (uuid == null) {
            onResult(false, "Sign in first.")
            return
        }

        Thread({
            try {
                val payload = """{"code":"$code","uuid":"$uuid"}"""
                val request = HttpRequest.newBuilder(URI.create("$BASE/cosmetics/redeem"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "RiverClient")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                val json = runCatching { JsonParser.parseString(response.body()).asJsonObject }.getOrNull()

                if (response.statusCode() in 200..299 && json?.get("ok")?.asBoolean == true) {
                    val cosmetic = json.get("cosmetic")?.asString.orEmpty()
                    if (cosmetic.isNotEmpty()) owned = owned + cosmetic
                    val already = json.get("already")?.asBoolean == true
                    onResult(true, if (already) "You already have that one." else "Unlocked.")
                } else {
                    onResult(false, json?.get("message")?.asString ?: "That code did not work.")
                }
            } catch (_: Exception) {
                onResult(false, "Could not reach the server.")
            }
        }, "river-cape-redeem").apply { isDaemon = true }.start()
    }
}
