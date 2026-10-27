package dev.wyz.clientcore.ui

import java.time.LocalDate

/**
 * Seasonal themes. Each one runs for a fixed window of the calendar and switches itself on
 * and off, so nothing has to be shipped on the day. The launcher keeps the same windows
 * (renderer-react/lib/seasons.js) - change both together.
 *
 * A window that crosses New Year (end before start) is supported. The system property
 * -Driver.season=halloween forces a season for testing, and -Driver.season=off turns them off.
 */
object Seasons {

    data class Season(
        val id: String,
        val label: String,
        val fromMonth: Int,
        val fromDay: Int,
        val toMonth: Int,
        val toDay: Int,
        /** The [RiverTheme] preset the season uses as its accent. */
        val paletteKey: String
    )

    val all: List<Season> = listOf(
        Season("halloween", "Halloween", 10, 1, 10, 31, "halloween")
    )

    private var checkedAt = 0L
    private var cached: Season? = null

    /** The season that applies today, or null. Re-read at most once a minute: it is asked every frame. */
    fun active(): Season? {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 60_000L && checkedAt != 0L) return cached
        checkedAt = now
        cached = resolve()
        return cached
    }

    /** True when a season's theme and cape can be used today: it is on, or was earned for good. */
    fun unlocked(seasonId: String): Boolean = active()?.id == seasonId || SeasonEvent.kept(seasonId)

    /**
     * Run once at start-up. The first time a season is on, a player still on River's default
     * theme is switched to the season's theme; anyone who picked their own keeps it. Either
     * way the season is marked offered, so switching away afterwards sticks.
     */
    fun offerOnce(config: dev.wyz.clientcore.config.ClientCoreConfig): Boolean {
        val season = active() ?: return false
        if (config.seasonOffered == season.id) return false
        config.seasonOffered = season.id
        val current = config.theme ?: "river"
        if (current == "river") config.theme = season.paletteKey
        return true
    }

    private fun resolve(): Season? {
        when (val forced = System.getProperty("river.season")?.trim()?.lowercase()) {
            null, "" -> Unit
            "off" -> return null
            else -> all.firstOrNull { it.id == forced }?.let { return it }
        }
        val today = LocalDate.now()
        val here = today.monthValue * 100 + today.dayOfMonth
        return all.firstOrNull {
            val from = it.fromMonth * 100 + it.fromDay
            val to = it.toMonth * 100 + it.toDay
            if (from <= to) here in from..to else here >= from || here <= to
        }
    }
}
