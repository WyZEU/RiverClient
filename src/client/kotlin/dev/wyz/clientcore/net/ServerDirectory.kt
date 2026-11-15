package dev.wyz.clientcore.net

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The servers River's in-game browser lists, and how each one is doing right now.
 *
 * The list comes from River's update server (servers.json), so servers can be added or
 * featured without a release; the copy below is what shows when that cannot be reached.
 * Each listed server is pinged directly from the player's own connection, so the player
 * count and the ping are what this player would actually get.
 */
object ServerDirectory {

    data class Entry(
        val name: String,
        val address: String,
        val description: String,
        val tags: List<String>,
        val featured: Boolean = false
    )

    sealed class Status {
        object Pending : Status()
        object Offline : Status()
        class Online(val result: ServerPing.Result) : Status()
    }

    private const val LIST_URL = "https://updates.riverclient.xyz/servers.json"

    /** What ships with River, used until (or unless) the live list arrives. */
    private val BUNDLED = listOf(
        Entry("Hypixel", "mc.hypixel.net", "Bed Wars, SkyBlock, Duels and the biggest minigame network there is.", listOf("Minigames", "PvP", "Skyblock")),
        Entry("Minemen Club", "minemen.club", "Competitive PvP: ranked duels, practice and events.", listOf("PvP")),
        Entry("CubeCraft", "play.cubecraft.net", "Egg Wars, Sky Wars, parkour and more minigames.", listOf("Minigames", "PvP")),
        Entry("MCC Island", "play.mccisland.net", "The MC Championship crew's own minigame island.", listOf("Minigames")),
        Entry("Wynncraft", "play.wynncraft.com", "A full MMORPG built in Minecraft: quests, classes and dungeons.", listOf("RPG")),
        Entry("ManaCube", "play.manacube.com", "Skyblock, parkour, prison and survival.", listOf("Skyblock", "Survival")),
        Entry("PikaNetwork", "play.pika-network.net", "Bed Wars, Sky Wars, survival and practice.", listOf("Minigames", "PvP", "Survival")),
        Entry("Lifesteal SMP", "lifesteal.net", "Survival where every kill takes a heart.", listOf("SMP", "PvP")),
        Entry("BlocksMC", "blocksmc.com", "Sky Wars, Bed Wars and quick minigames.", listOf("Minigames", "PvP")),
        Entry("2b2t", "2b2t.org", "The oldest anarchy server: no rules, no resets since 2010.", listOf("Anarchy", "Survival"))
    )

    @Volatile var entries: List<Entry> = BUNDLED
        private set
    @Volatile private var fetchedAt = 0L

    private val statuses = ConcurrentHashMap<String, Status>()
    private val pool = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "River-Server-Ping").apply { isDaemon = true }
    }

    fun status(entry: Entry): Status = statuses[entry.address] ?: Status.Pending

    /** Fetches the live list (at most every ten minutes) and pings everything on it. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - fetchedAt > 10 * 60_000L) {
            fetchedAt = now
            pool.submit {
                fetchList()?.let { entries = it }
                pingAll()
            }
        } else {
            pingAll()
        }
    }

    private fun pingAll() {
        for (entry in entries) {
            statuses[entry.address] = statuses[entry.address]?.takeIf { it !is Status.Offline } ?: Status.Pending
            pool.submit {
                statuses[entry.address] = runCatching { Status.Online(ServerPing.ping(entry.address)) }
                    .getOrElse { Status.Offline }
            }
        }
    }

    private fun fetchList(): List<Entry>? = runCatching {
        val connection = (URI(LIST_URL).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("User-Agent", "RiverClient")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val root = connection.inputStream.use { JsonParser.parseReader(it.reader()) }.asJsonObject
            root.getAsJsonArray("servers").mapNotNull { element ->
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString ?: return@mapNotNull null
                val address = obj.get("address")?.asString ?: return@mapNotNull null
                Entry(
                    name = name,
                    address = address,
                    description = obj.get("description")?.asString.orEmpty(),
                    tags = obj.getAsJsonArray("tags")?.map { it.asString }.orEmpty(),
                    featured = obj.get("featured")?.asBoolean == true
                )
            }.takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
