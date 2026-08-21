package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ClockModule : Module("clock", "Clock", "Local time of day", ModuleCategory.HUD, "clock", 8, 280, false) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Time", LocalTime.now().format(formatter))
    }
}
