package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.config.MacroData
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.MacrosModule
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.widget.InputNames
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
import org.lwjgl.glfw.GLFW
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Macro manager: type a message or /command, bind it to a key, and it fires from that
 * key in game. Opened from the Macros module keybind or its settings panel.
 *
 * The same row does everything a macro needs - its key, its text, an on/off eye and a
 * delete - because a macro has exactly two interesting properties and hiding either
 * behind a second screen would be ceremony.
 */
class RiverMacrosScreen(private val parent: Screen?) : Screen(Component.literal("Macros")), RiverScreen {

    private var input = ""
    private var inputFocused = true
    private var editing: MacroData? = null
    private var binding: MacroData? = null
    private var scroll = 0
    private var contentHeight = 0
    private var listRect = intArrayOf(0, 0, 0, 0)
    private var flash = ""
    private var flashAt = 0L

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    private fun module(): MacrosModule? = ModuleRegistry.get("macros")

    private fun say(text: String) {
        flash = text
        flashAt = System.currentTimeMillis()
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        g.fill(0, 0, width, height, 0x66040507)
        ClientUi.beginFrame()
        hits.clear()

        val module = module() ?: return
        val pw = min(390, width - 20)
        val ph = min(240, height - 30)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        // Header
        RiverIcons.draw(g, "keyboard", px + 12, py + 11, 13, ClientUi.ACCENT_B)
        g.drawString(font, "Macros", px + 31, py + 12, ClientUi.TEXT, true)
        if (System.currentTimeMillis() - flashAt < 2500) {
            g.drawString(font, trim(flash, pw - 150), px + 84, py + 12, ClientUi.POSITIVE, true)
        } else if (!module.enabled) {
            ClientUi.drawTag(g, font, px + 84, py + 9, "module off", ClientUi.WARNING)
        }
        val closeX = px + pw - 24
        val closeHovered = mouseX in closeX..(closeX + 18) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 3, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }
        g.fill(px + 10, py + 30, px + pw - 10, py + 31, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        // List
        val listTop = py + 34
        val listBottom = py + ph - 34
        listRect = intArrayOf(px, listTop, px + pw, listBottom)
        val macros = module.macros()

        ClientUi.withScissor(g, px + 4, listTop, px + pw - 4, listBottom) {
            var cy = listTop + 2 - scroll
            if (macros.isEmpty()) {
                g.drawString(font, "No macros yet. Type a message or /command below and add it,", px + 14, listTop + 8, ClientUi.DIM, true)
                g.drawString(font, "then click its key box to bind. Use | to send more than one line.", px + 14, listTop + 20, ClientUi.DIM, true)
            }
            macros.toList().forEach { macro ->
                val rowX = px + 10
                val rowW = pw - 20
                val hovered = mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + 22) && mouseY in listTop..listBottom
                ClientUi.drawListRow(g, rowX, cy, rowW, 22, ClientUi.hover("macrorow:${macro.hashCode()}", hovered), editing === macro)

                // Key box - click to rebind, Escape while capturing clears it.
                val keyW = 58
                val keyX = rowX + 6
                val capturing = binding === macro
                val keyHovered = mouseX in keyX..(keyX + keyW) && mouseY in (cy + 3)..(cy + 19) && mouseY in listTop..listBottom
                val keyLabel = if (capturing) "press a key" else InputNames.keyName(macro.key)
                ClientUi.drawListRow(g, keyX, cy + 3, keyW, 16, if (keyHovered) 1f else 0f, capturing)
                val keyColor = when {
                    capturing -> ClientUi.ACCENT_B
                    macro.key < 0 -> ClientUi.DIM
                    else -> ClientUi.TEXT
                }
                g.drawString(font, trim(keyLabel, keyW - 8), keyX + 5, cy + 7, keyColor, true)
                run {
                    val yy = cy
                    hit(keyX, yy + 3, keyW, 16) {
                        binding = if (capturing) null else macro
                        editing = null
                    }
                }

                // Text - click to edit in place.
                val textX = keyX + keyW + 8
                val textW = rowW - (keyW + 14) - 40
                if (editing === macro) {
                    val caret = if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
                    g.drawString(font, trim(input, textW - 6) + caret, textX, cy + 7, ClientUi.TEXT, true)
                } else {
                    // A disabled macro keeps its key and text, it just never fires - so it
                    // reads as switched off rather than looking identical to a live one.
                    val textColor = if (macro.enabled) ClientUi.TEXT else ClientUi.DIM
                    g.drawString(font, trim(macro.text, textW), textX, cy + 7, textColor, true)
                    val yy = cy
                    hit(textX - 4, yy, textW + 8, 22) {
                        editing = macro
                        input = macro.text
                        binding = null
                        inputFocused = false
                    }
                }

                // On/off
                val eyeX = rowX + rowW - 34
                val eyeHovered = mouseX in eyeX..(eyeX + 14) && mouseY in (cy + 4)..(cy + 18) && mouseY in listTop..listBottom
                val eyeColor = when {
                    !macro.enabled -> if (eyeHovered) ClientUi.TEXT else ClientUi.alpha(ClientUi.DIM, 0.5f)
                    eyeHovered -> ClientUi.ACCENT_B
                    else -> ClientUi.TEXT
                }
                RiverIcons.draw(g, "eye", eyeX, cy + 5, 11, eyeColor)
                run {
                    val yy = cy
                    hit(eyeX - 3, yy + 1, 18, 20) {
                        macro.enabled = !macro.enabled
                        say(if (macro.enabled) "Enabled" else "Disabled")
                        RiverRuntime.saveConfig()
                    }
                }

                // Delete
                val trashX = rowX + rowW - 17
                val trashHovered = mouseX in trashX..(trashX + 14) && mouseY in (cy + 4)..(cy + 18) && mouseY in listTop..listBottom
                RiverIcons.draw(g, "trash", trashX, cy + 5, 11, if (trashHovered) 0xFFFF8080.toInt() else ClientUi.DIM)
                run {
                    val yy = cy
                    hit(trashX - 3, yy + 1, 20, 20) {
                        macros.remove(macro)
                        if (editing === macro) { editing = null; input = "" }
                        if (binding === macro) binding = null
                        RiverRuntime.saveConfig()
                    }
                }

                cy += 25
            }
            contentHeight = macros.size * 25 + 4
        }
        ClientUi.drawScrollbar(g, px + pw - 8, listTop, listBottom - listTop, contentHeight, listBottom - listTop, scroll)

        // Footer: text input + add button
        val fy = py + ph - 28
        val addW = 76
        val inputW = pw - 20 - addW - 6
        ClientUi.drawListRow(g, px + 10, fy, inputW, 20, 0f, inputFocused && editing == null)
        val editingSomething = editing != null
        val caret = if (inputFocused && !editingSomething && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        val empty = input.isEmpty() && !inputFocused
        val shown = when {
            editingSomething -> "press Enter to save the row above"
            empty -> "message or /command"
            else -> input
        }
        val textColor = if (editingSomething || empty) ClientUi.DIM else ClientUi.TEXT
        g.drawString(font, trim(shown, inputW - 14) + caret, px + 16, fy + 6, textColor, true)
        hit(px + 10, fy, inputW, 20) {
            inputFocused = true
            editing = null
        }

        val addX = px + 10 + inputW + 6
        val addEnabled = !editingSomething && macros.size < MacrosModule.MAX_MACROS
        val addHovered = mouseX in addX..(addX + addW) && mouseY in fy..(fy + 20)
        ClientUi.drawFlatButton(g, font, addX, fy, addW, 20, "Add", addHovered && addEnabled, addEnabled)
        hit(addX, fy, addW, 20) { addMacro() }
    }

    private fun addMacro() {
        if (editing != null) return
        val module = module() ?: return
        val macros = module.macros()
        val text = input.trim()
        if (text.isEmpty()) {
            say("Type the message first")
            return
        }
        if (macros.size >= MacrosModule.MAX_MACROS) {
            say("That is ${MacrosModule.MAX_MACROS} macros already")
            return
        }
        val macro = MacroData(key = -1, text = text.take(MacrosModule.MAX_LENGTH))
        macros.add(macro)
        input = ""
        // Straight into capture: a macro with no key does nothing, so binding is the
        // obvious next step rather than something to go looking for.
        binding = macro
        say("Now press the key for it")
        RiverRuntime.saveConfig()
    }

    private fun commitEdit() {
        val macro = editing ?: return
        val text = input.trim()
        if (text.isEmpty()) {
            module()?.macros()?.remove(macro)
        } else {
            macro.text = text.take(MacrosModule.MAX_LENGTH)
        }
        editing = null
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
        // Clicking anywhere else commits whatever row was being edited first.
        if (editing != null) commitEdit()
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
            val viewH = listRect[3] - listRect[1]
            val maxScroll = (contentHeight - viewH).coerceAtLeast(0)
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
        // Capture owns the keyboard while it is armed, so any key can be bound - including
        // Enter and Backspace, which would otherwise be eaten by the editor below.
        val capture = binding
        if (capture != null) {
            capture.key = if (keyCode == GLFW.GLFW_KEY_ESCAPE) -1 else keyCode
            binding = null
            say(if (capture.key < 0) "Cleared the key" else "Bound to ${InputNames.keyName(capture.key)}")
            RiverRuntime.saveConfig()
            return true
        }
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> {
                if (editing != null) { editing = null; input = ""; return true }
                onClose()
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (editing != null) commitEdit() else addMacro()
                return true
            }
            GLFW.GLFW_KEY_BACKSPACE -> {
                input = input.dropLast(1)
                return true
            }
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
        if (binding == null && input.length < MacrosModule.MAX_LENGTH) input += event.codepointAsString()
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (binding == null && input.length < MacrosModule.MAX_LENGTH) input += codePoint.toString()
*///?}
        return true
    }

    override fun onClose() {
        if (editing != null) commitEdit()
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
