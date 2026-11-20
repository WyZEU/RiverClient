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
 * River Plus for the signed-in player: whether it is on.
 *
 * The server decides (it is what everyone else's roster reads too); this only remembers
 * its last answer, so your own badge shows the same thing other players see. Refreshed in
 * the background at most every ten minutes. A dev run can force it on with
 * -Driver.dev.plus=true to look at the Plus visuals without a record on the server; the
 * switch does nothing outside the dev environment.
 */
object RiverPlus {

    /**
     * Off until the store is live. While off, nothing advertises Plus: no "Get River Plus"
     * link and no locked Plus capes. Anyone the server already has as Plus still gets it.
     */
    const val LAUNCHED = true

    /** Whether Plus shows up at all for this player: launched, or they have it anyway. */
    @JvmStatic
    fun shown(): Boolean = LAUNCHED || active()

    private const val BASE = "https://updates.riverclient.xyz"
    private const val REFRESH_MS = 10 * 60 * 1000L
    private val devForced = java.lang.Boolean.getBoolean("river.dev.plus") &&
        net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment

    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    @Volatile
    private var active = false

    @Volatile
    private var lastRefresh = 0L

    private val refreshing = AtomicBoolean(false)

    /** Whether the signed-in player has River Plus right now. */
    @JvmStatic
    fun active(): Boolean {
        refreshIfStale()
        return devForced || active
    }

    private fun refreshIfStale() {
        val now = System.currentTimeMillis()
        if (devForced || now - lastRefresh < REFRESH_MS) return
        if (!refreshing.compareAndSet(false, true)) return
        lastRefresh = now
        Thread({
            try {
                val uuid = Minecraft.getInstance().user?.profileId?.toString()
                if (uuid != null) {
                    val request = HttpRequest.newBuilder(URI.create("$BASE/plus/status?uuid=$uuid"))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build()
                    val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("ok")?.asBoolean == true) {
                        active = json.get("plus")?.asBoolean == true
                    }
                }
            } catch (_: Exception) {
                // Offline or the worker is down: keep the last answer rather than flicker.
            } finally {
                refreshing.set(false)
            }
        }, "river-plus-status").apply { isDaemon = true }.start()
    }
}
