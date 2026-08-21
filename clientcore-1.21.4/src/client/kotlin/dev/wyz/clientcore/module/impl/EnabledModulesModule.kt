package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class EnabledModulesModule : Module("enabled_modules", "Module List", "List of active modules", ModuleCategory.HUD, "list", 8, 322, false) {

    override val multiLine: Boolean = true
    /** Rebuilt at the configured HUD update rate; rendering every frame reuses the cache. */
    private var lines: List<String> = emptyList()
    private var lastRebuild = 0L

    override fun tick(client: Minecraft) {
        val hz = (ModuleRegistry.get<PerformanceModule>("performance")?.hudRateHz() ?: 20).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        if (now - lastRebuild < 1000L / hz) return
        lastRebuild = now
        lines = ModuleRegistry.all
            .filter { it.active && it.id != id }
            .map { it.displayName }
            .sortedBy { it.lowercase() }
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (lines.isEmpty()) return
        drawPanel(client, graphics, lines, tickDelta)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawPanel(client, graphics, listOf("FPS", "Keystrokes", "Zoom"), tickDelta)
    }
}
