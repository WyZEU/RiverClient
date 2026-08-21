package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class FpsModule : Module("fps", "FPS", "Frames per second readout", ModuleCategory.HUD, "gauge", 8, 8) {
    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "FPS", "${client.fps}")
    }
}
