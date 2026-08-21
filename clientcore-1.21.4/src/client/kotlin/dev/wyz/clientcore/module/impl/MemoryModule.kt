package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class MemoryModule : Module("memory", "Memory", "JVM memory usage", ModuleCategory.HUD, "chip", 8, 308, false) {
    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val max = runtime.maxMemory() / 1024 / 1024
        drawStat(client, graphics, "Mem", "${used} / ${max} MB")
    }
}
