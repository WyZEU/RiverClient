package dev.wyz.clientcore.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.NameTagModule
import dev.wyz.clientcore.nametag.EffectRoster
import net.minecraft.client.Minecraft
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Lets River users see each other's badges. While you're on a multiplayer server
 * with the River badge cosmetic enabled, this announces "I'm here" to the River
 * presence backend (sending only your UUID, name, badge/cape, and a HASH of the
 * server address — never the raw address) and fetches the other River users on the
 * same server, which populates [EffectRoster] so their badges render.
 *
 * Nothing is sent in singleplayer, or when the badge cosmetic is off. All network
 * work happens on a background daemon thread so it never touches the render/tick loop.
 */
object PresenceService {
    private const val ENDPOINT = "https://updates.riverclient.xyz/presence"
    private const val INTERVAL_MS = 10_000L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private data class Snapshot(
        val serverHash: String,
        val uuid: String,
        val name: String,
        val badge: Boolean,
        val cape: String
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var running = false

    private var thread: Thread? = null

    private var tickCounter = 0
    private var hashCacheIp: String? = null
    private var hashCacheValue = ""

    /** Called each client tick; the real work only runs once a second. */
    fun update(client: Minecraft) {
        if (!running) ensureThread()
        // The snapshot feeds a 10s network loop, so refreshing it every 20 ticks (1s)
        // is plenty and keeps hashing/allocation out of the per-tick path.
        tickCounter += 1
        if (tickCounter < 20) return
        tickCounter = 0

        val server = client.currentServer
        val level = client.level
        val nametag = ModuleRegistry.get<NameTagModule>("nametag")
        val badgeOn = nametag != null && nametag.active && nametag.showRiverBadge()
        val capeModule = ModuleRegistry.get<dev.wyz.clientcore.module.impl.CapeModule>("cape")
        val capeStyle = if (capeModule?.active == true) capeModule.selectedStyle() else ""
        val ip = server?.ip

        // Announce presence if the player is showing anything others should see.
        if (server == null || level == null || (!badgeOn && capeStyle.isEmpty()) || ip.isNullOrBlank()) {
            if (snapshot != null) {
                snapshot = null
                EffectRoster.clearRemoteRoster()
            }
            return
        }

        snapshot = Snapshot(
            serverHash = hashedServerKey(ip),
            uuid = client.user.profileId.toString(),
            name = client.user.name,
            badge = badgeOn,
            cape = capeStyle
        )
    }

    /** SHA-256 of the server ip, cached until the ip changes. */
    private fun hashedServerKey(ip: String): String {
        if (hashCacheIp != ip) {
            hashCacheIp = ip
            hashCacheValue = hash(ip.lowercase())
        }
        return hashCacheValue
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        EffectRoster.clearRemoteRoster()
    }

    @Synchronized
    private fun ensureThread() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "River-Presence").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop() {
        while (running) {
            runCatching {
                val snap = snapshot
                if (snap == null) EffectRoster.clearRemoteRoster() else sync(snap)
            }
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sync(snap: Snapshot) {
        val body = JsonObject().apply {
            addProperty("server", snap.serverHash)
            addProperty("uuid", snap.uuid)
            addProperty("name", snap.name)
            addProperty("badge", snap.badge)
            addProperty("cape", snap.cape)
            // "cape" carries the style id ("" = none).
        }
        val response = post(body.toString()) ?: return
        parseRoster(response, snap.uuid)
    }

    private fun parseRoster(json: String, selfUuid: String) {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        val players = root.getAsJsonArray("players") ?: return
        val badges = HashSet<UUID>()
        val capes = HashMap<UUID, String>()
        players.forEach { element ->
            val obj = element.asJsonObject
            val uuidStr = obj.get("uuid")?.asString ?: return@forEach
            if (uuidStr.equals(selfUuid, ignoreCase = true)) return@forEach
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return@forEach
            if (obj.get("badge")?.asBoolean == true) badges.add(uuid)
            val capeStyle = obj.get("cape")?.asString
            if (!capeStyle.isNullOrBlank()) capes[uuid] = capeStyle
        }
        EffectRoster.setRemoteRoster(badges, capes)
    }

    private fun post(body: String): String? {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { out: OutputStream -> out.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}
