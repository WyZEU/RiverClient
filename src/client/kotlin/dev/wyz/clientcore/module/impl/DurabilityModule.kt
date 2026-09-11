package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

class DurabilityModule : Module("durability", "Durability", "Held item durability", ModuleCategory.HUD, "tool", 8, 266, false) {
    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val stack = client.player?.mainHandItem ?: return
        if (!stack.isDamageableItem) return
        val remaining = stack.maxDamage - stack.damageValue
        drawStat(client, graphics, "Durability", "$remaining/${stack.maxDamage}")
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Durability", "1043/1561")
    }
}
