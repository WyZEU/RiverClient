package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class KeystrokesModule : Module("keystrokes", "Keystrokes", "WASD, space and mouse buttons", ModuleCategory.HUD, "keys", 8, 62) {

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.KEYSTROKES
    override val hudStack: HudStack = HudStack.TOP_RIGHT

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val g = effectiveStyle().spacing.coerceIn(0, 8)
        val kw = 20
        val kh = 16
        val totalW = kw * 3 + g * 2
        val totalH = (kh + g) * 4 - g
        val s = scaleFactor()
        return Pair((totalW * s).toInt().coerceAtLeast(1), (totalH * s).toInt().coerceAtLeast(1))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val opt  = client.options
        val font = client.font

        val kw = 20   // key box width
        val kh = 16   // key box height
        val g  = effectiveStyle().spacing.coerceIn(0, 8)

        val totalW = kw * 3 + g * 2

        // column x positions
        val col0 = x
        val col1 = x + kw + g
        val col2 = x + (kw + g) * 2

        // row y positions
        val row0 = y
        val row1 = y + kh + g
        val row2 = y + (kh + g) * 2
        val row3 = y + (kh + g) * 3

        // WASD
        drawKey(graphics, font, col1, row0, kw, kh, "W", opt.keyUp.isDown)
        drawKey(graphics, font, col0, row1, kw, kh, "A", opt.keyLeft.isDown)
        drawKey(graphics, font, col1, row1, kw, kh, "S", opt.keyDown.isDown)
        drawKey(graphics, font, col2, row1, kw, kh, "D", opt.keyRight.isDown)

        // Space bar spans full width
        drawKey(graphics, font, col0, row2, totalW, kh, "SPACE", opt.keyJump.isDown)

        // LMB / RMB split
        val halfW = (totalW - g) / 2
        val rmbW  = totalW - halfW - g
        drawKey(graphics, font, col0,          row3, halfW, kh, "LMB", opt.keyAttack.isDown)
        drawKey(graphics, font, col0 + halfW + g, row3, rmbW,  kh, "RMB", opt.keyUse.isDown)
    }
}
