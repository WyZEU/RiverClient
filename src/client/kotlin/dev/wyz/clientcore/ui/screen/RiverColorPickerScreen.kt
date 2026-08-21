package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Visual colour picker: drag inside the saturation/value box, drag the hue bar,
 * or type a hex value. Live [onPick] every change; [onDone] when closed. Reusable
 * for the custom theme, nametag gradient stops, and future colour settings.
 */
class RiverColorPickerScreen(
    private val parent: Screen?,
    private val titleText: String,
    initialRgb: Int,
    private val onPick: (Int) -> Unit,
    private val onDone: (Int) -> Unit = {}
) : Screen(Component.literal(titleText)), RiverScreen {

    private var hue: Float
    private var sat: Float
    private var value: Float
    private var hexInput = ""
    private var hexFocused = false

    private var dragSv = false
    private var dragHue = false

    // Layout, filled each render.
    private var svX = 0; private var svY = 0; private var svW = 0; private var svH = 0
    private var hueX = 0; private var hueY = 0; private var hueW = 0; private var hueH = 0

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Hit>()

    init {
        val hsb = ClientUi.rgbToHsb(initialRgb and 0xFFFFFF)
        hue = hsb[0]; sat = hsb[1]; value = hsb[2]
    }

    private fun currentRgb(): Int = ClientUi.hsbToRgb(hue, sat, value) and 0xFFFFFF

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) = hits.add(Hit(x, y, x + w, y + h, onClick))

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        ClientUi.beginFrame()
        hits.clear()

        val pw = 250
        val ph = 210
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        RiverIcons.draw(g, "palette", px + 12, py + 11, 13, ClientUi.ACCENT_B)
        g.drawString(font, titleText, px + 31, py + 12, ClientUi.TEXT, true)
        val closeX = px + pw - 22
        val closeHovered = mouseX in closeX..(closeX + 16) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 2, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }

        // Saturation / value box.
        svX = px + 12; svY = py + 32; svW = pw - 24 - 22; svH = 120
        drawSvBox(g)
        // SV knob.
        val knobX = (svX + sat * svW).roundToInt()
        val knobY = (svY + (1f - value) * svH).roundToInt()
        ClientUi.drawRoundedBorder(g, knobX - 4, knobY - 4, 8, 8, 4, 0xFF000000.toInt())
        ClientUi.drawRoundedBorder(g, knobX - 3, knobY - 3, 6, 6, 3, 0xFFFFFFFF.toInt())

        // Hue bar (vertical, to the right).
        hueX = svX + svW + 8; hueY = svY; hueW = 14; hueH = svH
        drawHueBar(g)
        val hueKnobY = (hueY + hue * hueH).roundToInt()
        ClientUi.fillRounded(g, hueX - 2, hueKnobY - 2, hueW + 4, 4, 2, 0xFFFFFFFF.toInt())
        ClientUi.drawRoundedBorder(g, hueX - 2, hueKnobY - 2, hueW + 4, 4, 2, 0xFF000000.toInt())

        // Hex field + preview + done.
        val rowY = svY + svH + 12
        g.drawString(font, "#", px + 12, rowY + 5, ClientUi.MUTED, true)
        val boxX = px + 22
        val boxW = 74
        val boxHovered = mouseX in boxX..(boxX + boxW) && mouseY in rowY..(rowY + 18)
        ClientUi.drawListRow(g, boxX, rowY, boxW, 18, ClientUi.hover("pickhex", boxHovered), hexFocused)
        val shown = if (hexFocused) hexInput else "%06X".format(currentRgb())
        val caret = if (hexFocused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        g.drawString(font, shown + caret, boxX + 6, rowY + 5, ClientUi.TEXT, true)
        hit(boxX, rowY, boxW, 18) {
            hexFocused = true
            hexInput = "%06X".format(currentRgb())
        }

        // Preview swatch.
        val swX = boxX + boxW + 8
        ClientUi.fillRounded(g, swX, rowY, 34, 18, 6, (0xFF shl 24) or currentRgb())
        ClientUi.drawRoundedBorder(g, swX, rowY, 34, 18, 6, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        val doneW = 52
        val doneX = px + pw - doneW - 12
        val doneHovered = mouseX in doneX..(doneX + doneW) && mouseY in rowY..(rowY + 18)
        ClientUi.drawFlatButton(g, font, doneX, rowY, doneW, 18, "Done", doneHovered, true)
        hit(doneX, rowY, doneW, 18) { onClose() }

        // Live drag updates.
        if (dragSv) updateSv(mouseX, mouseY)
        if (dragHue) updateHue(mouseY)
    }

    private fun drawSvBox(g: GuiGraphics) {
        val hueColor = (0xFF shl 24) or ClientUi.hsbToRgb(hue, 1f, 1f)
        // Each column: white -> hue at top, fading to black at the bottom.
        for (col in 0 until svW) {
            val t = col.toFloat() / (svW - 1).coerceAtLeast(1)
            val top = ClientUi.mix(0xFFFFFFFF.toInt(), hueColor, t)
            g.fillGradient(svX + col, svY, svX + col + 1, svY + svH, top, 0xFF000000.toInt())
        }
        ClientUi.drawRoundedBorder(g, svX - 1, svY - 1, svW + 2, svH + 2, 3, ClientUi.alpha(ClientUi.BORDER, 0.7f))
    }

    private fun drawHueBar(g: GuiGraphics) {
        for (row in 0 until hueH) {
            val h = row.toFloat() / (hueH - 1).coerceAtLeast(1)
            g.fill(hueX, hueY + row, hueX + hueW, hueY + row + 1, (0xFF shl 24) or ClientUi.hsbToRgb(h, 1f, 1f))
        }
        ClientUi.drawRoundedBorder(g, hueX - 1, hueY - 1, hueW + 2, hueH + 2, 3, ClientUi.alpha(ClientUi.BORDER, 0.7f))
    }

    private fun updateSv(mouseX: Int, mouseY: Int) {
        sat = ((mouseX - svX).toFloat() / svW).coerceIn(0f, 1f)
        value = (1f - (mouseY - svY).toFloat() / svH).coerceIn(0f, 1f)
        onPick(currentRgb())
    }

    private fun updateHue(mouseY: Int) {
        hue = ((mouseY - hueY).toFloat() / hueH).coerceIn(0f, 1f)
        onPick(currentRgb())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        hexFocused = false

        if (mx in svX..(svX + svW) && my in svY..(svY + svH)) {
            dragSv = true; updateSv(mx, my); return true
        }
        if (mx in hueX..(hueX + hueW) && my in hueY..(hueY + hueH)) {
            dragHue = true; updateHue(my); return true
        }
        hits.asReversed().forEach { h ->
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) { h.onClick(); return true }
        }
        return super.mouseClicked(event, doubled)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (dragSv) { updateSv(event.x().toInt(), event.y().toInt()); return true }
        if (dragHue) { updateHue(event.y().toInt()); return true }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragSv = false; dragHue = false
        return super.mouseReleased(event)
    }

    private fun applyHex() {
        val v = hexInput.toIntOrNull(16)
        if (v != null && hexInput.length == 6) {
            val hsb = ClientUi.rgbToHsb(v and 0xFFFFFF)
            hue = hsb[0]; sat = hsb[1]; value = hsb[2]
            onPick(currentRgb())
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (hexFocused) {
            when (event.key()) {
                GLFW.GLFW_KEY_ESCAPE -> { hexFocused = false; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { applyHex(); hexFocused = false; return true }
                GLFW.GLFW_KEY_BACKSPACE -> { hexInput = hexInput.dropLast(1); applyHex(); return true }
            }
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (hexFocused) {
            val filtered = event.codepointAsString().filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (filtered.isNotEmpty() && hexInput.length < 6) {
                hexInput += filtered.uppercase()
                applyHex()
            }
            return true
        }
        return super.charTyped(event)
    }

    override fun onClose() {
        onDone(currentRgb())
        minecraft?.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false
}
