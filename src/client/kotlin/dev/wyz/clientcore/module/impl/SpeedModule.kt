package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.sqrt

class SpeedModule : Module("speed", "Speedometer", "Horizontal speed in blocks/s", ModuleCategory.HUD, "speed", 8, 252, false) {
    private var lastX = 0.0
    private var lastZ = 0.0
    private var speed = 0.0

    override fun tick(client: Minecraft) {
        val player = client.player ?: return
        val dx = player.x - lastX
        val dz = player.z - lastZ
        speed = sqrt(dx * dx + dz * dz) * 20.0
        lastX = player.x
        lastZ = player.z
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (client.player == null) return
        drawStat(client, graphics, "Speed", "%.2f b/s".format(speed))
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Speed", "5.61 b/s")
    }
}
