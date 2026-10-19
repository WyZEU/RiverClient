package dev.wyz.clientcore.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.wyz.clientcore.ui.menu.SeasonalFx
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import java.nio.file.Files
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The Halloween 2026 event: play ten hours in a world with River, on a server or in
 * singleplayer, before Halloween is over and the Halloween theme and cape stay after October.
 *
 * River's server counts the time from the signed-in heartbeat (RiverSocial), which carries
 * whether a world is open, and answers each heartbeat with the progress. This shows it:
 *
 *  - a bar under the pause menu's River button while the event is still to finish,
 *  - a countdown on the main menu to when Halloween leaves for anyone who hasn't finished,
 *    which turns into "yours for good" once they have. After finishing, the main menu is
 *    the only place it shows.
 *
 * The last progress is kept in `config/river-client/rewards.json`, outside the profiles,
 * so the main menu can show it before any world is open, and a season kept for good is
 * there from the first frame and survives switching profiles. Whether other players see
 * the cape is still decided by the server, which only lets it through for those who earned it.
 */
object SeasonEvent {

    private const val HOUR_MS = 3_600_000L
    private const val ORANGE = 0xFFE0702B.toInt()
    private const val ORANGE_LIGHT = 0xFFF29A45.toInt()
    private const val TRACK = 0xFF2A2E37.toInt()

    /** Until the server says otherwise. 31 October is over in every time zone by then. */
    private val DEFAULT_ENDS_AT = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli()
    private const val DEFAULT_GOAL_MS = 10 * HOUR_MS

    data class Status(
        val id: String,
        val label: String,
        val cosmetic: String,
        val playedMs: Long,
        val goalMs: Long,
        val endsAt: Long,
        val done: Boolean
    )

    private data class Rewards(
        var keptSeasons: MutableList<String> = mutableListOf(),
        var playedMs: Long = 0,
        var goalMs: Long = DEFAULT_GOAL_MS,
        var endsAt: Long = 0,
        var done: Boolean = false
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** The latest progress from the server, or null before the first heartbeat answered. */
    @Volatile var status: Status? = null
        private set

    private var rewards: Rewards? = null
    private var savedAt = 0L
    private var toldProgress = false
    private var pendingMessage: String? = null

    private fun rewardsPath() = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir
        .resolve("config").resolve("river-client").resolve("rewards.json")

    private fun rewards(): Rewards = rewards ?: runCatching {
        Files.newBufferedReader(rewardsPath()).use { gson.fromJson(it, Rewards::class.java) }
    }.getOrNull().let { it ?: Rewards() }.also {
        @Suppress("SENSELESS_COMPARISON")
        if (it.keptSeasons == null) it.keptSeasons = mutableListOf()
        rewards = it
    }

    private fun save() {
        savedAt = System.currentTimeMillis()
        runCatching {
            Files.createDirectories(rewardsPath().parent)
            Files.newBufferedWriter(rewardsPath()).use { gson.toJson(rewards(), it) }
        }
    }

    /** True when this season's theme and cape were earned for good. Cheap: asked every frame. */
    fun kept(seasonId: String): Boolean = runCatching { seasonId in rewards().keptSeasons }.getOrDefault(false)

    /** Called from the heartbeat's network thread with the response's `event` object. */
    fun apply(json: JsonObject?) {
        json ?: return
        status = runCatching {
            Status(
                id = json.get("id").asString,
                label = json.get("label")?.asString ?: "Halloween",
                cosmetic = json.get("cosmetic")?.asString ?: "halloween",
                playedMs = json.get("playedMs").asLong,
                goalMs = json.get("goalMs").asLong,
                endsAt = json.get("endsAt").asLong,
                done = json.get("done").asBoolean
            )
        }.getOrNull() ?: return
    }

    /** Dev screenshots only (see dev.DevShot): shows this progress without touching rewards.json. */
    private var preview = false

    internal fun devPreview(playedMs: Long, done: Boolean) {
        preview = true
        status = Status("halloween2026", "Halloween 2026", "halloween", playedMs, DEFAULT_GOAL_MS, DEFAULT_ENDS_AT, done)
    }

    fun tick(client: Minecraft) {
        if (preview) return
        val current = status
        if (current != null) {
            val stored = rewards()
            if (current.done && !stored.done) {
                stored.done = true
                if (current.cosmetic !in stored.keptSeasons) stored.keptSeasons.add(current.cosmetic)
                dev.wyz.clientcore.cosmetic.CapeEntitlements.grantLocal(current.cosmetic)
                save()
                pendingMessage = "${current.label} event complete. The Halloween theme and cape stay after October."
            }

            // Kept for the main menu. Written when something changes that it shows, at most
            // once a minute otherwise: a heartbeat lands every ten seconds.
            val changed = stored.goalMs != current.goalMs || stored.endsAt != current.endsAt || stored.done != current.done
            if (changed || (stored.playedMs != current.playedMs && System.currentTimeMillis() - savedAt > 60_000L)) {
                stored.playedMs = current.playedMs
                stored.goalMs = current.goalMs
                stored.endsAt = current.endsAt
                stored.done = current.done
                save()
            }

            if (!current.done && !toldProgress && System.currentTimeMillis() < current.endsAt) {
                toldProgress = true
                pendingMessage = "${current.label} event: play ${duration(current.goalMs - current.playedMs)} more before November 1 " +
                    "to keep the Halloween theme and cape."
            }
        }
        val message = pendingMessage ?: return
        val player = client.player ?: return
        pendingMessage = null
//? if >=26.1 {
/*        player.sendSystemMessage(Component.literal(message))
*///?} else {
        player.displayClientMessage(Component.literal(message), false)
//?}
    }

    // ------------------------------------------------------------------ what the screens read

    private fun playedMs() = status?.playedMs ?: rewards().playedMs
    private fun goalMs() = status?.goalMs ?: rewards().goalMs
    private fun endsAt() = status?.endsAt ?: rewards().endsAt.takeIf { it > 0 } ?: DEFAULT_ENDS_AT
    private fun done() = status?.done ?: rewards().done
    private fun running() = System.currentTimeMillis() < endsAt()

    /** One line for the theme card in River's menu, or null when there is nothing to say. */
    fun menuLine(): String? {
        if (done()) return "Halloween theme and cape unlocked."
        if (!running()) return null
        return "Play ${duration(goalMs() - playedMs())} more before November 1 to keep Halloween."
    }

    // ------------------------------------------------------------------ drawing

    /** The bar under the pause menu's River button, while the event is still to finish. */
    fun renderScreen(client: Minecraft, screen: Screen, g: GuiGraphics) {
        val isPause = screen is PauseScreen || (preview && screen.title.string == "Pause preview")
        if (!isPause || done() || !running()) return
        val anchor = screen.children().firstOrNull {
            it is AbstractWidget && it.message.string == "River Client"
        } as? AbstractWidget ?: return
        val font = client.font
        val x = anchor.x
        val w = anchor.width
        val y = anchor.y + anchor.height + 10

        val left = "Halloween event"
        val right = "${duration(playedMs())} / ${duration(goalMs())}"
        SeasonalFx.drawPumpkinIcon(g, x, y - 2, 10)
        g.drawString(font, left, x + 13, y, ORANGE_LIGHT, true)
        g.drawString(font, right, x + w - font.width(right), y, ClientUi.MUTED, true)
        drawBar(g, x, y + 12, w, 5, playedMs().toFloat() / goalMs())
    }

    /**
     * Top left of the main menu: how long until Halloween leaves for good, or that it is
     * already theirs. Nothing once the event is over for somebody who didn't finish.
     */
    fun renderMainMenu(client: Minecraft, g: GuiGraphics) {
        val finished = done()
        if (!running()) return
        val font = client.font
        val x = 8
        val y = 8
        val note = "Play ${duration(goalMs() - playedMs())} more to keep it"
        // As wide as the longest line it holds, so nothing runs past the edge.
        val lines = if (finished) listOf("Halloween unlocked", "Theme and cape stay after October")
            else listOf("Halloween ends in", note)
        val w = (lines.maxOf { font.width(it) } + 40).coerceAtLeast(150)
        val h = 50

        g.fill(x, y, x + w, y + h, ClientUi.alpha(0xFF15171C.toInt(), 0.88f))
        ClientUi.drawRoundedBorder(g, x, y, w, h, 0, 0xFF3A2A1E.toInt())
        g.fill(x, y, x + 2, y + h, ORANGE)
        SeasonalFx.drawPumpkinIcon(g, x + 8, y + 8, 16)

        if (finished) {
            g.drawString(font, "Halloween unlocked", x + 30, y + 8, ClientUi.TEXT, true)
            g.drawString(font, "Theme and cape stay after October", x + 30, y + 19, ClientUi.MUTED, false)
            drawBlocks(g, x + 8, y + 36, w - 16, 6, 1f, 1f)
            return
        }

        g.drawString(font, "Halloween ends in", x + 30, y + 7, ClientUi.MUTED, false)
        g.drawString(font, countdown(endsAt() - System.currentTimeMillis()), x + 30, y + 18, ORANGE_LIGHT, true)
        g.drawString(font, note, x + 30, y + 30, ClientUi.DIM, false)
        drawBar(g, x + 8, y + 42, w - 16, 3, playedMs().toFloat() / goalMs())
    }

    private fun drawBar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, filled: Float) {
        g.fill(x, y, x + w, y + h, TRACK)
        val fw = (w * filled.coerceIn(0f, 1f)).roundToInt()
        if (fw > 0) g.fill(x, y, x + fw, y + h, ORANGE)
    }

    /** Ten blocks, one per hour, the current one part-filled. */
    private fun drawBlocks(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, filled: Float, alpha: Float) {
        val blocks = 10
        val gap = 2
        val bw = (w - gap * (blocks - 1)) / blocks
        val amount = filled.coerceIn(0f, 1f) * blocks
        for (i in 0 until blocks) {
            val bx = x + i * (bw + gap)
            g.fill(bx, y, bx + bw, y + h, ClientUi.alpha(TRACK, alpha))
            val part = (amount - i).coerceIn(0f, 1f)
            if (part > 0f) g.fill(bx, y, bx + (bw * part).roundToInt().coerceAtLeast(1), y + h, ClientUi.alpha(ORANGE, alpha))
        }
    }

    private fun countdown(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val d = total / 86_400
        val hh = (total % 86_400) / 3600
        val mm = (total % 3600) / 60
        val ss = total % 60
        return if (d > 0) "%dd %02d:%02d:%02d".format(d, hh, mm, ss) else "%02d:%02d:%02d".format(hh, mm, ss)
    }

    private fun duration(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).toInt()
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    private fun trim(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && font.width("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }
}
