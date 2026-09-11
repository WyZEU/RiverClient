package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import kotlin.math.sqrt

/**
 * Shows the distance of your last landed hit. Purely informational — it measures,
 * it never extends. Standard on legit clients.
 */
class ReachDisplayModule : Module("reach_display", "Reach Display", "Distance of your last hit", ModuleCategory.HUD, "target", 8, 226, false) {
    private var wasAttackDown = false
    private var lastReach = -1.0
    private var lastHitAt = 0L

    override fun tick(client: Minecraft) {
        val player = client.player ?: return
        val attackDown = client.options.keyAttack.isDown
        if (attackDown && !wasAttackDown) {
            val target = client.crosshairPickEntity
            if (target != null && target.isAlive) {
                val distSq = target.boundingBox.distanceToSqr(player.eyePosition)
                lastReach = sqrt(distSq)
                lastHitAt = System.currentTimeMillis()
            }
        }
        wasAttackDown = attackDown
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val stale = System.currentTimeMillis() - lastHitAt > 3000L
        if (lastReach < 0 || stale) return
        drawStat(client, graphics, "Reach", "%.2fm".format(lastReach))
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Reach", "2.87m")
    }
}
