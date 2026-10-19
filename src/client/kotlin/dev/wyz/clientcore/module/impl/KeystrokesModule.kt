package dev.wyz.clientcore.module.impl

import net.minecraft.client.KeyMapping
import dev.wyz.clientcore.pvp.CpsTracker
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

class KeystrokesModule : Module("keystrokes", "Keystrokes", "WASD, space and mouse buttons", ModuleCategory.HUD, "keyboard", 8, 62) {

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.KEYSTROKES
    override val hudStack: HudStack = HudStack.TOP_RIGHT

    /*
      Laid out like Lunar's: movement keys, then the mouse buttons with clicks per second
      under each, then the space bar. Letters come from your actual keybinds, so someone
      on ESDF sees E S D F rather than a WASD that is not what they press.
    */
    private fun showMouse() = flag("mouse", true)
    private fun showCps() = flag("cps", true)
    private fun showSpace() = flag("space", true)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Keys"))
        list.add(BoolSetting("Mouse buttons", { flag("mouse", true) }, { setFlag("mouse", it) }))
        if (showMouse()) list.add(BoolSetting("Clicks per second", { flag("cps", true) }, { setFlag("cps", it) }))
        list.add(BoolSetting("Space bar", { flag("space", true) }, { setFlag("space", it) }))
    }

    private val kw = 20
    private val kh = 16
    private fun mouseH() = if (showCps()) 22 else kh

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val g = effectiveStyle().spacing.coerceIn(0, 8)
        val totalW = kw * 3 + g * 2
        var totalH = kh * 2 + g
        if (showMouse()) totalH += g + mouseH()
        if (showSpace()) totalH += g + kh
        val s = scaleFactor()
        return Pair((totalW * s).toInt().coerceAtLeast(1), (totalH * s).toInt().coerceAtLeast(1))
    }

    /** The key a mapping is bound to, short enough for a 20px box. */
    private fun label(mapping: KeyMapping): String {
        val name = mapping.translatedKeyMessage.string.uppercase()
        return if (name.length <= 3) name else name.take(3)
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, CpsTracker.left(), CpsTracker.right())
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, 12, 4)
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, leftCps: Int, rightCps: Int) {
        val opt = client.options
        val font = client.font
        val g = effectiveStyle().spacing.coerceIn(0, 8)
        val totalW = kw * 3 + g * 2
        val col0 = x
        val col1 = x + kw + g
        val col2 = x + (kw + g) * 2
        var rowY = y

        drawKey(graphics, font, col1, rowY, kw, kh, label(opt.keyUp), opt.keyUp.isDown)
        rowY += kh + g
        drawKey(graphics, font, col0, rowY, kw, kh, label(opt.keyLeft), opt.keyLeft.isDown)
        drawKey(graphics, font, col1, rowY, kw, kh, label(opt.keyDown), opt.keyDown.isDown)
        drawKey(graphics, font, col2, rowY, kw, kh, label(opt.keyRight), opt.keyRight.isDown)
        rowY += kh + g

        if (showMouse()) {
            val halfW = (totalW - g) / 2
            val rmbW = totalW - halfW - g
            val h = mouseH()
            val cps = showCps()
            drawKey(graphics, font, col0, rowY, halfW, h, "LMB", opt.keyAttack.isDown, if (cps) "$leftCps CPS" else null)
            drawKey(graphics, font, col0 + halfW + g, rowY, rmbW, h, "RMB", opt.keyUse.isDown, if (cps) "$rightCps CPS" else null)
            rowY += h + g
        }

        if (showSpace()) {
            // A bar rather than a word, like the key it stands for.
            val pressed = opt.keyJump.isDown
            drawKey(graphics, font, col0, rowY, totalW, kh, "", pressed)
            val barW = totalW / 3
            val barColor = if (pressed) 0xFFFFFFFF.toInt() else 0xFFB9C0D2.toInt()
            graphics.fill(col0 + (totalW - barW) / 2, rowY + kh / 2, col0 + (totalW + barW) / 2, rowY + kh / 2 + 1, barColor)
        }
    }
}
