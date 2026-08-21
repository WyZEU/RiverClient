package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class SessionTimeModule : Module("session_time", "Session Time", "How long you have been playing", ModuleCategory.HUD, "hourglass", 8, 294, false) {
    private val startedAt = System.currentTimeMillis()

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val totalSeconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        drawStat(client, graphics, "Session", "%02d:%02d".format(minutes, seconds))
    }
}
