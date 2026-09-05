package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * A rolling graph of your ping over the last ~30 seconds - not just the number the Ping
 * module already shows. Each bar is one sample, coloured by latency (green / amber / red),
 * so a spike or a climbing trend is visible at a glance. Purely reads connection latency
 * and renders; nothing is sent, so it is server-safe everywhere.
 */
class PingGraphModule : Module("ping_graph", "Ping Graph", "Latency over time", ModuleCategory.HUD, "wifi", 8, 214) {

    private companion object {
        const val SAMPLES = 60
        const val SAMPLE_MS = 500L        // 60 * 500ms = ~30s window
        const val GRAPH_H = 18
        const val GOOD = 80               // ms thresholds for the colour ramp
        const val OKAY = 150
    }

    // Ring buffer, -1 = empty slot. No per-frame allocation.
    private val history = IntArray(SAMPLES) { -1 }
    private var head = 0
    private var filled = 0
    private var lastSampleMs = 0L

    private fun latencyColor(ms: Int): Int = when {
        ms < GOOD -> ClientUi.POSITIVE
        ms < OKAY -> ClientUi.WARNING
        else -> 0xFFFF6B7A.toInt()
    }

    private fun currentPing(client: Minecraft): Int {
        val player = client.player ?: return 0
        return client.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
    }

    private fun sample(ping: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSampleMs < SAMPLE_MS) return
        lastSampleMs = now
        history[head] = ping.coerceAtLeast(0)
        head = (head + 1) % SAMPLES
        if (filled < SAMPLES) filled++
    }

    /** Oldest-to-newest value at visible column [i] (0 until filled), or -1. */
    private fun valueAt(i: Int): Int {
        if (i >= filled) return -1
        val start = (head - filled + SAMPLES) % SAMPLES
        return history[(start + i) % SAMPLES]
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val ping = currentPing(client)
        sample(ping)
        draw(client, graphics, ping) { valueAt(it) }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        // A gentle wave so the graph is legible in the editor without a live connection.
        val demo = IntArray(SAMPLES) { (60 + 45 * kotlin.math.sin(it / 6.0)).toInt() }
        draw(client, graphics, 42) { if (it < SAMPLES) demo[it] else -1 }
    }

    private inline fun draw(client: Minecraft, graphics: GuiGraphics, ping: Int, valueAt: (Int) -> Int) {
        val font = client.font
        val pad = 4
        val label = "Ping"
        val value = "${ping}ms"
        val header = pad + 4 + font.width(label) + 4 + font.width(value) + pad
        val graphW = SAMPLES
        val w = maxOf(header, pad + 4 + graphW + pad)
        val h = pad * 2 + font.lineHeight + 3 + GRAPH_H

        drawPillBackground(graphics, w, h)

        val tx = x + pad + 4
        val ty = y + pad
        graphics.drawString(font, label, tx, ty, 0xFF97A0B5.toInt(), false)
        graphics.drawString(font, value, tx + font.width(label) + 4, ty, latencyColor(ping), false)

        // Scale bars to the tallest sample in view (min 100ms so a calm graph isn't all
        // full-height), and floor at 1px so live-but-low samples still read.
        val gx = x + pad + 4
        val gy = y + pad + font.lineHeight + 3
        var peak = 100
        for (i in 0 until SAMPLES) { val v = valueAt(i); if (v > peak) peak = v }
        // baseline
        graphics.fill(gx, gy + GRAPH_H, gx + graphW, gy + GRAPH_H + 1, 0x40FFFFFF)
        for (i in 0 until graphW) {
            val v = valueAt(i)
            if (v < 0) continue
            val barH = ((v.toFloat() / peak) * GRAPH_H).toInt().coerceIn(1, GRAPH_H)
            val bx = gx + i
            graphics.fill(bx, gy + GRAPH_H - barH, bx + 1, gy + GRAPH_H, latencyColor(v))
        }
    }
}
