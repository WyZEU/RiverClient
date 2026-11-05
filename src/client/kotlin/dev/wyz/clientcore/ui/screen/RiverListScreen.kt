package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?} else {
/**///?}
import net.minecraft.network.chat.Component
//? if >=26.3 {
/*import dev.wyz.clientcore.compat.GLFW
*///?} else {
import org.lwjgl.glfw.GLFW
//?}
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A plain editable list: type a line, press Enter, tick or delete it. Used by the To-Do module
 * (with tick boxes) and the Chat Window's words (without).
 */
class RiverListScreen(
    private val parent: Screen?,
    private val heading: String,
    private val icon: String,
    private val placeholder: String,
    private val maxLength: Int,
    private val maxItems: Int,
    private val items: () -> List<String>,
    private val isChecked: ((Int) -> Boolean)?,
    private val onToggle: (Int) -> Unit,
    private val onAdd: (String) -> Unit,
    private val onRemove: (Int) -> Unit
) : Screen(Component.literal(heading)), RiverScreen {
    private var input = ""
    private var scroll = 0
    private var contentHeight = 0
    private var listRect = intArrayOf(0, 0, 0, 0)

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        g.fill(0, 0, width, height, 0x66040507)
        ClientUi.beginFrame()
        hits.clear()
        val pw = min(360, width - 20)
        val ph = min(240, height - 30)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        RiverIcons.draw(g, icon, px + 12, py + 11, 13, ClientUi.ACCENT_B)
        g.drawString(font, heading, px + 31, py + 12, ClientUi.TEXT, true)
        val closeX = px + pw - 24
        val closeHovered = mouseX in closeX..(closeX + 18) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 3, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }
        g.fill(px + 10, py + 30, px + pw - 10, py + 31, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        val listTop = py + 34
        val listBottom = py + ph - 34
        listRect = intArrayOf(px, listTop, px + pw, listBottom)
        val rows = items()
        ClientUi.withScissor(g, px + 4, listTop, px + pw - 4, listBottom) {
            var cy = listTop + 2 - scroll
            rows.forEachIndexed { index, text ->
                val rowX = px + 10
                val rowW = pw - 20
                val visible = mouseY in listTop..listBottom
                val hovered = visible && mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + 20)
                ClientUi.drawListRow(g, rowX, cy, rowW, 20, ClientUi.hover("listrow:$heading:$index", hovered), false)
                var textX = rowX + 8
                val checked = isChecked?.invoke(index)
                if (checked != null) {
                    val boxY = cy + 5
                    g.fill(textX, boxY, textX + 10, boxY + 10, ClientUi.BORDER)
                    g.fill(textX + 1, boxY + 1, textX + 9, boxY + 9, if (checked) ClientUi.ACCENT_A else ClientUi.PANEL)
                    val y0 = cy
                    hit(rowX, y0, 22, 20) { onToggle(index); RiverRuntime.saveConfig() }
                    textX += 15
                }
                g.drawString(font, trim(text, rowW - (textX - rowX) - 26), textX, cy + 6, if (checked == true) ClientUi.DIM else ClientUi.TEXT, true)
                val trashX = rowX + rowW - 17
                val trashHovered = visible && mouseX in trashX..(trashX + 14) && mouseY in (cy + 3)..(cy + 17)
                RiverIcons.draw(g, "trash", trashX, cy + 4, 11, if (trashHovered) ClientUi.DANGER else ClientUi.DIM)
                val y1 = cy
                hit(trashX - 3, y1, 20, 20) { onRemove(index); RiverRuntime.saveConfig() }
                cy += 23
            }
            contentHeight = rows.size * 23 + 4
        }
        ClientUi.drawScrollbar(g, px + pw - 8, listTop, listBottom - listTop, contentHeight, listBottom - listTop, scroll)

        val fy = py + ph - 28
        val addW = 60
        val inputW = pw - 20 - addW - 6
        ClientUi.drawListRow(g, px + 10, fy, inputW, 20, 0f, true)
        val caret = if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        val shown = if (input.isEmpty() && caret.isEmpty()) placeholder else input + caret
        g.drawString(font, trim(shown, inputW - 14), px + 16, fy + 6, if (input.isEmpty()) ClientUi.DIM else ClientUi.TEXT, true)
        val addX = px + 10 + inputW + 6
        val canAdd = rows.size < maxItems
        ClientUi.drawFlatButton(g, font, addX, fy, addW, 20, "Add", canAdd && mouseX in addX..(addX + addW) && mouseY in fy..(fy + 20), canAdd)
        hit(addX, fy, addW, 20) { add() }
    }

    private fun add() {
        val text = input.trim()
        if (text.isEmpty() || items().size >= maxItems) return
        onAdd(text.take(maxLength))
        input = ""
        RiverRuntime.saveConfig()
    }

//? if >=1.21.9 {
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        val mx = event.x()
        val my = event.y()
//?} else {
/*    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        val mx = mouseX
        val my = mouseY
*///?}
        hits.asReversed().forEach { h ->
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                h.onClick()
                return true
            }
        }
//? if >=1.21.9 {
        return super.mouseClicked(event, doubled)
//?} else {
/*        return super.mouseClicked(mouseX, mouseY, button)
*///?}
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        if (mouseX >= listRect[0] && mouseX <= listRect[2] && mouseY >= listRect[1] && mouseY <= listRect[3]) {
            val maxScroll = (contentHeight - (listRect[3] - listRect[1])).coerceAtLeast(0)
            scroll = (scroll - (deltaY * 18).roundToInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
*///?}
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> { onClose(); return true }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { add(); return true }
            GLFW.GLFW_KEY_BACKSPACE -> { input = input.dropLast(1); return true }
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
        if (input.length < maxLength) input += event.codepointAsString()
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (input.length < maxLength) input += codePoint.toString()
*///?}
        return true
    }

    override fun onClose() {
        RiverRuntime.saveConfig()
        minecraft?.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun trim(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
