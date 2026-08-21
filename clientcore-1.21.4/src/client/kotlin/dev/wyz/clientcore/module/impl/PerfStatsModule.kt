package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.perf.PerfStats
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * Debug performance readout so before/after comparisons are honest and measured, not
 * claimed. Shows FPS, frame time, JVM memory, River's own HUD render cost, how many
 * entities River is culling, and the live particle count. Toggle it, change a
 * Performance setting, and watch the numbers move.
 */
class PerfStatsModule : Module("perf_stats", "Perf Stats", "FPS, frame time, memory and River render costs", ModuleCategory.HUD, "gauge", 8, 380, false) {

    override val multiLine: Boolean = true

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawPanelLabeled(client, graphics, buildLines(client), tickDelta)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawPanelLabeled(client, graphics, listOf(
            "FPS" to "142",
            "Frame" to "7.0 ms",
            "Memory" to "2140 / 8192 MB",
            "River HUD" to "0.06 ms",
            "Entities" to "18 hidden / 63",
            "Particles" to "240"
        ), tickDelta)
    }

    private fun buildLines(client: Minecraft): List<Pair<String, String>> {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val frameMs = client.frameTimeNs / 1_000_000.0
        val hudMs = PerfStats.hudRenderNanos / 1_000_000.0

        return listOf(
            "FPS" to client.fps.toString(),
            "Frame" to "%.1f ms".format(frameMs),
            "Memory" to "$usedMb / $maxMb MB",
            "River HUD" to "%.2f ms".format(hudMs),
            "Entities" to "${PerfStats.entitiesCulled} hidden / ${PerfStats.entitiesConsidered}",
            "Particles" to particleCount(client)
        )
    }

    private fun particleCount(client: Minecraft): String {
        // countParticles() returns the total as a string; keep only digits.
        val raw = runCatching { client.particleEngine.countParticles() }.getOrNull() ?: return "-"
        return raw.filter { it.isDigit() }.ifEmpty { "0" }
    }
}
