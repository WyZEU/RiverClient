package dev.wyz.clientcore.hud

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.perf.PerfStats
import dev.wyz.clientcore.ui.screen.RiverHudEditorScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

object HudRenderer {
    fun initialize() = Unit

    fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (client.level == null || client.player == null || client.options.hideGui) return
        // Minecraft keeps drawing the HUD behind an open screen, so while the HUD editor
        // is up the live HUD would render underneath the editor's own previews and every
        // module would draw twice (text looks doubled/bold and overlaps itself). The
        // editor draws all the modules itself, so stand down while it's open.
        if (client.screen is RiverHudEditorScreen) return
        // Time River's whole HUD pass so the Perf Stats readout reflects real cost.
        val start = System.nanoTime()
        HudLayout.apply(client)
        ModuleRegistry.render(client, graphics, tickDelta)
        PerfStats.recordHudRender(System.nanoTime() - start)
    }
}
