package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class DirectionModule : Module("direction", "Direction", "Facing direction and axis", ModuleCategory.HUD, "compass", 8, 238, false) {
    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        drawStat(client, graphics, "Facing", player.direction.name.lowercase().replaceFirstChar { it.uppercase() })
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Facing", "North")
    }
}
