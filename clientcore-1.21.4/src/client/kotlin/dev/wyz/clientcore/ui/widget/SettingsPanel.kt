package dev.wyz.clientcore.ui.widget

import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * Immediate-mode renderer for a list of [Setting]s inside a scissored, scrollable rect.
 * The host screen forwards mouse/key events. Hit regions are rebuilt every frame.
 */
class SettingsPanel(private val settingsProvider: () -> List<Setting>) {

    var scroll = 0
        private set
    private var contentHeight = 0
    private var viewHeight = 0

    // The settings list is rebuilt from scratch every frame, so transient UI state
    // must be tracked by LABEL, never by object identity.
    private var capturingKeybind: KeybindSetting? = null
    private var capturingLabel: String? = null
    private var openChoiceLabel: String? = null
    private var activeSlider: ((Double) -> Unit)? = null

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: (Double, Double) -> Unit)

    private val hits = ArrayList<Hit>()
    private class SliderHit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val drag: (Double) -> Unit)
    private val sliderHits = ArrayList<SliderHit>()

    val isCapturingKey: Boolean get() = capturingKeybind != null

    fun resetTransientState() {
        capturingKeybind = null
        capturingLabel = null
        openChoiceLabel = null
        activeSlider = null
    }

    fun render(g: GuiGraphics, font: Font, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        hits.clear()
        sliderHits.clear()
        viewHeight = h
        val settings = settingsProvider()

        val maxScroll = (contentHeight - h).coerceAtLeast(0)
        if (scroll > maxScroll) scroll = maxScroll

        ClientUi.withScissor(g, x, y, x + w, y + h) {
            var cy = y - scroll + 4
            settings.forEach { setting ->
                cy += renderSetting(g, font, setting, x + 10, cy, w - 26, mouseX, mouseY)
            }
            contentHeight = cy + scroll - y + 6
        }
        ClientUi.drawScrollbar(g, x + w - 7, y + 2, h - 4, contentHeight, h, scroll)
    }

    private fun renderSetting(g: GuiGraphics, font: Font, setting: Setting, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        return when (setting) {
            is SectionSetting -> {
                g.drawString(font, setting.label.uppercase(), x, y + 8, ClientUi.DIM, true)
                g.fill(x + font.width(setting.label.uppercase()) + 6, y + 11, x + w, y + 12, ClientUi.alpha(ClientUi.BORDER, 0.6f))
                22
            }
            is BoolSetting -> {
                val toggleX = x + w - 34
                val hovered = mouseY in y..(y + 18) && mouseX in x..(x + w)
                g.drawString(font, trim(font, setting.label, w - 44), x, y + 4, if (hovered) ClientUi.TEXT else ClientUi.MUTED, true)
                ClientUi.drawMinimalToggle(g, toggleX, y + 1, setting.get(), hovered, id = "set:${setting.label}")
                hits.add(Hit(x, y, x + w, y + 18) { _, _ -> setting.set(!setting.get()) })
                20
            }
            is IntSetting -> {
                val value = setting.get().coerceIn(setting.min, setting.max)
                g.drawString(font, trim(font, setting.label, w - 50), x, y + 2, ClientUi.MUTED, true)
                val valueText = "$value${setting.suffix}"
                g.drawString(font, valueText, x + w - font.width(valueText), y + 2, ClientUi.TEXT, true)
                val sliderY = y + 15
                val x0 = x
                val x1 = x + w
                val t = (value - setting.min).toFloat() / (setting.max - setting.min).coerceAtLeast(1).toFloat()
                val hovered = mouseY in sliderY - 4..(sliderY + 11) && mouseX in x0..x1
                ClientUi.drawSlider(g, x0, x1, sliderY, t, hovered)
                val drag: (Double) -> Unit = { mx ->
                    val nt = ((mx - x0) / (x1 - x0).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                    setting.set((setting.min + nt * (setting.max - setting.min)).roundToInt())
                }
                sliderHits.add(SliderHit(x0, sliderY - 5, x1, sliderY + 12, drag))
                32
            }
            is ChoiceSetting -> {
                val open = openChoiceLabel == setting.label
                val hovered = mouseY in y..(y + 18) && mouseX in x..(x + w)
                g.drawString(font, trim(font, setting.label, w - 84), x, y + 5, ClientUi.MUTED, true)
                val boxW = 78
                val boxX = x + w - boxW
                ClientUi.drawListRow(g, boxX, y, boxW, 18, if (hovered) 1f else 0f, open)
                g.drawString(font, trim(font, setting.get(), boxW - 24), boxX + 6, y + 5, ClientUi.TEXT, true)
                RiverIcons.draw(g, "chevron_down", boxX + boxW - 15, y + 4, 10, ClientUi.MUTED)
                hits.add(Hit(boxX, y, boxX + boxW, y + 18) { _, _ ->
                    openChoiceLabel = if (open) null else setting.label
                })
                var used = 22
                if (open) {
                    setting.options.forEach { option ->
                        val oy = y + used
                        val selected = option == setting.get()
                        val optHover = mouseY in oy..(oy + 16) && mouseX in boxX..(boxX + boxW)
                        ClientUi.drawListRow(g, boxX, oy, boxW, 16, if (optHover) 1f else 0f, selected)
                        g.drawString(font, trim(font, option, boxW - 12), boxX + 6, oy + 4, if (selected) ClientUi.TEXT else ClientUi.MUTED, true)
                        hits.add(Hit(boxX, oy, boxX + boxW, oy + 16) { _, _ ->
                            setting.set(option)
                            openChoiceLabel = null
                        })
                        used += 18
                    }
                    used += 2
                }
                used
            }
            is ColorSetting -> {
                val hovered = mouseY in y..(y + 18) && mouseX in x..(x + w)
                g.drawString(font, trim(font, setting.label, w - 44), x, y + 5, ClientUi.MUTED, true)
                val swX = x + w - 26
                val color = setting.get()
                ClientUi.fillRounded(g, swX, y + 1, 26, 15, 5, (0xFF shl 24) or (color and 0xFFFFFF))
                ClientUi.drawRoundedBorder(g, swX, y + 1, 26, 15, 5, if (hovered) ClientUi.BORDER_STRONG else ClientUi.BORDER)
                hits.add(Hit(x, y, x + w, y + 18) { _, _ ->
                    // Open the visual colour wheel; keep the setting's existing alpha byte.
                    val mc = net.minecraft.client.Minecraft.getInstance()
                    val parent = mc.screen
                    val alphaMask = if (setting.hasAlpha) setting.get() and 0xFF000000.toInt() else 0xFF000000.toInt()
                    mc.setScreen(dev.wyz.clientcore.ui.screen.RiverColorPickerScreen(
                        parent, setting.label, setting.get() and 0xFFFFFF,
                        onPick = { rgb -> setting.set(alphaMask or (rgb and 0xFFFFFF)) }
                    ))
                })
                20
            }
            is KeybindSetting -> {
                val capturing = capturingLabel == setting.label
                val hovered = mouseY in y..(y + 18) && mouseX in x..(x + w)
                g.drawString(font, trim(font, setting.label, w - 84), x, y + 5, ClientUi.MUTED, true)
                val label = if (capturing) "press a key" else InputNames.keyName(setting.get())
                val boxW = 78
                val boxX = x + w - boxW
                ClientUi.drawListRow(g, boxX, y, boxW, 18, if (hovered) 1f else 0f, capturing)
                val labelColor = if (capturing) ClientUi.ACCENT_B else ClientUi.TEXT
                g.drawString(font, trim(font, label, boxW - 12), boxX + 6, y + 5, labelColor, true)
                hits.add(Hit(boxX, y, boxX + boxW, y + 18) { _, _ ->
                    if (capturing) {
                        capturingKeybind = null
                        capturingLabel = null
                    } else {
                        capturingKeybind = setting
                        capturingLabel = setting.label
                    }
                })
                22
            }
            is ActionSetting -> {
                val btnW = (font.width(setting.buttonLabel) + 16).coerceAtLeast(52)
                val btnX = x + w - btnW
                val hovered = mouseY in y..(y + 18) && mouseX in btnX..(btnX + btnW)
                g.drawString(font, trim(font, setting.label, w - btnW - 8), x, y + 5, ClientUi.MUTED, true)
                val labelColor = if (setting.destructive) 0xFFFF8080.toInt() else ClientUi.TEXT
                val hoverAnim = ClientUi.hover("panelact:${setting.label}:${setting.buttonLabel}", hovered)
                ClientUi.drawListRow(g, btnX, y, btnW, 18, hoverAnim, false)
                g.drawString(font, setting.buttonLabel, btnX + (btnW - font.width(setting.buttonLabel)) / 2, y + 5, labelColor, true)
                hits.add(Hit(btnX, y, btnX + btnW, y + 18) { _, _ -> setting.action() })
                22
            }
            else -> 0
        }
    }

    fun mouseClicked(mx: Double, my: Double): Boolean {
        if (capturingKeybind != null) {
            // Clicking elsewhere cancels capture.
            capturingKeybind = null
            capturingLabel = null
        }
        sliderHits.forEach { s ->
            if (mx >= s.x1 && mx <= s.x2 && my >= s.y1 && my <= s.y2) {
                activeSlider = s.drag
                s.drag(mx)
                return true
            }
        }
        // Later hits are drawn later (on top); walk in reverse.
        hits.asReversed().forEach { hit ->
            if (mx >= hit.x1 && mx <= hit.x2 && my >= hit.y1 && my <= hit.y2) {
                hit.onClick(mx, my)
                return true
            }
        }
        return false
    }

    fun mouseDragged(mx: Double): Boolean {
        val slider = activeSlider ?: return false
        slider(mx)
        return true
    }

    fun mouseReleased(): Boolean {
        val had = activeSlider != null
        activeSlider = null
        return had
    }

    fun mouseScrolled(deltaY: Double): Boolean {
        val maxScroll = (contentHeight - viewHeight).coerceAtLeast(0)
        scroll = (scroll - (deltaY * 18).roundToInt()).coerceIn(0, maxScroll)
        return true
    }

    /** Returns true when the key press was consumed by keybind capture. */
    fun keyPressed(keyCode: Int): Boolean {
        val capture = capturingKeybind ?: return false
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            capture.set(-1)
        } else {
            capture.set(keyCode)
        }
        capturingKeybind = null
        capturingLabel = null
        return true
    }

    private fun trim(font: Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}

object InputNames {
    fun keyName(code: Int): String {
        if (code < 0) return "None"
        val special = when (code) {
            GLFW.GLFW_KEY_LEFT_SHIFT -> "LShift"
            GLFW.GLFW_KEY_RIGHT_SHIFT -> "RShift"
            GLFW.GLFW_KEY_LEFT_CONTROL -> "LCtrl"
            GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCtrl"
            GLFW.GLFW_KEY_LEFT_ALT -> "LAlt"
            GLFW.GLFW_KEY_RIGHT_ALT -> "RAlt"
            GLFW.GLFW_KEY_SPACE -> "Space"
            GLFW.GLFW_KEY_TAB -> "Tab"
            GLFW.GLFW_KEY_ENTER -> "Enter"
            GLFW.GLFW_KEY_BACKSPACE -> "Back"
            GLFW.GLFW_KEY_CAPS_LOCK -> "Caps"
            GLFW.GLFW_KEY_UP -> "Up"
            GLFW.GLFW_KEY_DOWN -> "Down"
            GLFW.GLFW_KEY_LEFT -> "Left"
            GLFW.GLFW_KEY_RIGHT -> "Right"
            else -> null
        }
        if (special != null) return special
        if (code in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F25) return "F${code - GLFW.GLFW_KEY_F1 + 1}"
        val name = runCatching { GLFW.glfwGetKeyName(code, 0) }.getOrNull()
        return name?.uppercase() ?: "Key $code"
    }
}
