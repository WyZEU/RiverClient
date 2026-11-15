package dev.wyz.clientcore.hud

import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.ModuleRegistry
import net.minecraft.client.Minecraft

/**
 * Auto-stacking HUD layout. Elements the user has never dragged flow into tidy
 * columns (top-left readouts, top-right widgets, bottom-left wide panels) with no
 * overlap; the stacks re-pack every frame as modules toggle on/off. Dragging an
 * element in the HUD editor marks it `placed` and removes it from the flow.
 */
object HudLayout {

    private const val MARGIN = 6
    private const val GAP = 4

    /**
     * Writes stack positions into every un-placed module.
     * [includeDisabled] is used by the HUD editor so previews of disabled modules
     * also line up instead of piling on top of each other.
     */
    fun apply(client: Minecraft, includeDisabled: Boolean = false) {
        val sw = client.window.guiScaledWidth
        val sh = client.window.guiScaledHeight

        var topLeftY = MARGIN
        var topRightY = MARGIN
        var bottomLeftY = sh - MARGIN

        // Indexed loop with no intermediate filtered list: this runs every frame.
        val modules = ModuleRegistry.all
        for (i in modules.indices) {
            val module = modules[i]
            if (!module.acceptsDraggablePosition()) continue
            if (!includeDisabled && !module.active) continue
            if (module.placed) {
                // Placed elements re-resolve from their anchor every frame, so a window
                // resize or GUI-scale change moves them WITH their corner instead of
                // leaving them stranded at stale absolute coordinates.
                module.resolveAnchor(client, sw, sh)
                continue
            }
            val (w, h) = module.editorApproximateSize(client)
            when (module.hudStack) {
                HudStack.TOP_LEFT -> {
                    module.x = MARGIN
                    module.y = topLeftY
                    topLeftY += h + GAP
                }
                HudStack.TOP_RIGHT -> {
                    module.x = (sw - w - MARGIN).coerceAtLeast(0)
                    module.y = topRightY
                    topRightY += h + GAP
                }
                HudStack.BOTTOM_LEFT -> {
                    bottomLeftY -= h
                    module.x = MARGIN
                    module.y = bottomLeftY.coerceAtLeast(0)
                    bottomLeftY -= GAP
                }
            }
        }
    }
}
