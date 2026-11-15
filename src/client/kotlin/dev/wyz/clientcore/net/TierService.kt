package dev.wyz.clientcore.net

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * MCTiers lookups for the PvP tier tags.
 *
 * `GET https://mctiers.com/api/v2/profile/<uuid>` returns a player's tier in each of the
 * eight ranked modes, or 404 when they have never been ranked. The endpoint sits behind
 * Cloudflare with a four hour max-age, so caching for the same four hours costs nothing
 * in freshness and keeps River from re-asking for the same tab list every frame - and an
 * unranked answer is cached too, because "not ranked" is the common case and asking again
 * every frame for a whole lobby would be the rude version of this feature.
 *
 * Read-only and per-UUID: it looks up people you can already see. Nothing is sent about
 * the player running River, and the module that uses this is off until switched on.
 */
object TierService {

    private const val API = "https://mctiers.com/api/v2/profile/"
    private const val USER_AGENT = "riverclient.xyz/river-client (tier tags)"
    private const val CONNECT_TIMEOUT = 6000
    private const val READ_TIMEOUT = 8000

    /** Matches the CDN's own max-age. */
    private const val FRESH_MS = 4 * 60 * 60 * 1000L
    private const val UNRANKED_MS = 60 * 60 * 1000L
    private const val FAILED_MS = 5 * 60 * 1000L

    /**
     * How many lookups may be waiting at once. A busy lobby would otherwise queue a
     * hundred on the first frame; anything skipped is simply asked for again next frame,
     * which paces the whole thing without a scheduler.
     */
    private const val MAX_QUEUED = 8

    /** API keys, in the order the settings dropdown lists them. */
    val MODES = listOf("vanilla", "sword", "axe", "pot", "nethop", "smp", "uhc", "mace")

    val MODE_LABELS = listOf("Vanilla", "Sword", "Axe", "Pot", "Neth pot", "SMP", "UHC", "Mace")

    class Ranking(val tier: Int, val pos: Int, val retired: Boolean) {
        /** MCTiers splits every tier into a high and a low half; pos 0 is the high one. */
        val label: String get() = (if (pos == 0) "HT" else "LT") + tier
    }

    private class Entry(val rankings: Map<String, Ranking>?, val at: Long, val failed: Boolean)

    private val cache = ConcurrentHashMap<UUID, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    private val pool = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "River-Tiers").apply { isDaemon = true }
    }

    /**
     * Cached rankings for a player, or null when nothing is known yet - in which case a
     * lookup is scheduled, so the tag appears a moment later. Cheap enough to call from
     * a render path: a miss is one map lookup and a set insert.
     */
    fun rankings(uuid: UUID): Map<String, Ranking>? {
        val entry = cache[uuid]
        if (entry == null) {
            schedule(uuid)
            return null
        }
        val ttl = when {
            entry.failed -> FAILED_MS
            entry.rankings == null -> UNRANKED_MS
            else -> FRESH_MS
        }
        if (System.currentTimeMillis() - entry.at > ttl) schedule(uuid)
        return entry.rankings
    }

    fun ranking(uuid: UUID, mode: String): Ranking? = rankings(uuid)?.get(mode)

    /**
     * Best tier across every mode, with the mode it is in: lowest tier number wins, then
     * the high half. The mode matters now that the tag shows that mode's icon.
     */
    fun best(uuid: UUID): Pair<String, Ranking>? = rankings(uuid)?.entries
        ?.minWithOrNull(compareBy({ it.value.tier }, { it.value.pos }))
        ?.let { it.key to it.value }

    private fun schedule(uuid: UUID) {
        if (inFlight.size >= MAX_QUEUED) return
        if (!inFlight.add(uuid)) return
        pool.submit {
            val entry = fetch(uuid)
            cache[uuid] = entry
            inFlight.remove(uuid)
        }
    }

    private fun fetch(uuid: UUID): Entry {
        val now = System.currentTimeMillis()
        val connection = try {
            (URL(API + uuid).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
        } catch (_: Throwable) {
            return Entry(null, now, true)
        }
        return try {
            val code = connection.responseCode
            // 404 is the answer "this player has no tiers", not a failure to retry soon.
            if (code == 404) return Entry(null, now, false)
            if (code !in 200..299) return Entry(null, now, true)
            val body = connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            Entry(parse(body), now, false)
        } catch (_: Throwable) {
            Entry(null, now, true)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): Map<String, Ranking> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyMap()
        val rankings = runCatching { root.getAsJsonObject("rankings") }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Ranking>()
        rankings.entrySet().forEach { (mode, value) ->
            val entry = value as? JsonObject ?: return@forEach
            val tier = runCatching { entry.get("tier").asInt }.getOrNull() ?: return@forEach
            val pos = runCatching { entry.get("pos").asInt }.getOrNull() ?: 0
            val retired = runCatching { entry.get("retired").asBoolean }.getOrDefault(false)
            out[mode] = Ranking(tier, pos, retired)
        }
        return out
    }
}
