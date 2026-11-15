package dev.wyz.clientcore.net

import com.google.gson.JsonParser
import dev.wyz.clientcore.ui.ClientUi
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Update check for the standalone mod: River installed as a plain Fabric mod, with no
 * launcher to keep it current. Started from River's launcher the game is told so
 * (minecraft.launcher.brand) and this does nothing, since the launcher handles updates.
 *
 * Behind the latest release, the main menu says a new version is out. Too far behind
 * (below the server's minimum, see cloudflare-updates/src/update-policy.js), River turns
 * its modules off and the main menu says why, the same rule that makes the launcher update.
 */
object ModUpdateCheck {

    private const val URL = "https://updates.riverclient.xyz/version-status.json"

    private val standalone = System.getProperty("minecraft.launcher.brand") != "river-client"

    @Volatile private var latest = ""
    @Volatile private var behind = 0
    @Volatile private var maxBehind = 0

    /** Below the oldest version still allowed: every module is off (Module.active). */
    @Volatile var turnedOff = false
        private set

    private var started = false

    private val ownVersion: String by lazy {
        FabricLoader.getInstance().getModContainer("clientcore")
            .map { it.metadata.version.friendlyString.substringBefore('+') }
            .orElse("")
    }

    fun start() {
        if (!standalone || started) return
        started = true
        Thread({
            runCatching {
                val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                val request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "RiverClient-Mod/$ownVersion").GET().build()
                val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
                val json = JsonParser.parseString(body).asJsonObject
                val newest = json.get("latest")?.asString.orEmpty()
                val minimum = json.get("minimumVersion")?.asString.orEmpty()
                val releases = json.getAsJsonArray("releases")?.map { it.asString }.orEmpty()
                if (newest.isEmpty() || ownVersion.isEmpty()) return@runCatching
                maxBehind = json.get("maxBehind")?.asInt ?: 0
                behind = releases.count { compare(it, ownVersion) > 0 }
                    .coerceAtLeast(if (compare(newest, ownVersion) > 0) 1 else 0)
                latest = newest
                turnedOff = minimum.isNotEmpty() && compare(minimum, ownVersion) > 0
            }
            // Offline or the server is down: nothing is shown and nothing is turned off.
        }, "river-mod-update-check").apply { isDaemon = true }.start()
    }

    /** Top of River's main menu, while there is something to say. */
    fun renderMainMenu(client: Minecraft, g: GuiGraphics, screenWidth: Int) {
        if (latest.isEmpty() || behind <= 0) return
        val font = client.font
        val line = when {
            turnedOff -> "River Client $ownVersion is too old and is turned off. Get $latest at riverclient.xyz"
            maxBehind > 0 -> "River Client $latest is out. Update at riverclient.xyz, versions $maxBehind updates behind stop working."
            else -> "River Client $latest is out. Update at riverclient.xyz"
        }
        g.drawString(font, line, (screenWidth - font.width(line)) / 2, 6, if (turnedOff) ClientUi.DANGER else ClientUi.TEXT, true)
    }

    /** 1 when a is newer than b, -1 when older, 0 when the same; "0.2.5.1" is newer than "0.2.5". */
    private fun compare(a: String, b: String): Int {
        val pa = a.split('.', '+', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '+', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }
}
