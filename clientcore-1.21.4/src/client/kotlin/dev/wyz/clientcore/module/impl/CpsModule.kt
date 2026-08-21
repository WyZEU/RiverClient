package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import java.util.ArrayDeque

class CpsModule : Module("cps", "CPS", "Clicks per second", ModuleCategory.HUD, "pointer", 8, 44) {
    private val clicks = ArrayDeque<Long>()
    private var wasAttackDown = false

    override fun tick(client: Minecraft) {
        val attackDown = client.options.keyAttack.isDown
        val now = System.currentTimeMillis()

        if (attackDown && !wasAttackDown) {
            clicks.addLast(now)
        }

        while (clicks.isNotEmpty() && now - clicks.first > 1000L) {
            clicks.removeFirst()
        }

        wasAttackDown = attackDown
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "CPS", "${clicks.size}")
    }
}
