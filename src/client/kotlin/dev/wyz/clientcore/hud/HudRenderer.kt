package dev.wyz.clientcore.hud

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.perf.PerfStats
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.screen.RiverHudEditorScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

object HudRenderer {
    fun initialize() = Unit

    /**
     * Flashback's isInReplay(), looked up once by name so River needs nothing of Flashback at
     * build time. Use it to keep live overlays out of replay editing and exports.
     */
    private val flashbackInReplay: java.lang.reflect.Method? by lazy {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("flashback")) null
        else runCatching { Class.forName("com.moulberry.flashback.Flashback").getMethod("isInReplay") }.getOrNull()
    }

    fun inReplay(): Boolean {
        val method = flashbackInReplay ?: return false
        return runCatching { method.invoke(null) == true }.getOrDefault(false)
    }

    fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
//? if >=26.2 {
/*        if (client.level == null || client.player == null || client.gui.hud.isHidden()) return
*///?} else {
        if (client.level == null || client.player == null || client.options.hideGui) return
//?}
        // Minecraft keeps drawing the HUD behind an open screen, so while the HUD editor
        // is up the live HUD would render underneath the editor's own previews and every
        // module would draw twice (text looks doubled/bold and overlaps itself). The
        // editor draws all the modules itself, so stand down while it's open.
        if (client.screen is RiverHudEditorScreen) return
        ClientUi.menuFrame = false
        // Time River's whole HUD pass so the Perf Stats readout reflects real cost.
        val start = System.nanoTime()
        HudLayout.apply(client)
        ModuleRegistry.render(client, graphics, tickDelta)
        PerfStats.recordHudRender(System.nanoTime() - start)
    }
}
