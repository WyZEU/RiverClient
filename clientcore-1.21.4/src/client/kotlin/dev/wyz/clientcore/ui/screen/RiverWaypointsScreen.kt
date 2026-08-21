package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.config.WaypointData
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.WaypointsModule
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.world.WaypointShare
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Waypoint manager: add a named waypoint at your position, rename, recolor
 * and delete existing ones. Opened with the Waypoints module keybind.
 */
class RiverWaypointsScreen(private val parent: Screen?) : Screen(Component.literal("Waypoints")), RiverScreen {

    private var input = ""
    private var inputFocused = true
    private var renaming: WaypointData? = null
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

    private fun module(): WaypointsModule? = ModuleRegistry.get("waypoints")

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, 0x66040507)
        ClientUi.beginFrame()
        hits.clear()

        val client = minecraft ?: return
        val module = module() ?: return
        val pw = min(370, width - 20)
        val ph = min(240, height - 30)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        // Header
        RiverIcons.draw(g, "flag", px + 12, py + 10, 13, ClientUi.ACCENT_B)
        g.drawString(font, "Waypoints", px + 31, py + 12, ClientUi.TEXT, true)
        val recentFlash = System.currentTimeMillis() - flashAt < 2500
        if (recentFlash) {
            g.drawString(font, trim(flash, pw - 200), px + 96, py + 12, ClientUi.POSITIVE, true)
        } else if (!module.enabled) {
            ClientUi.drawTag(g, font, px + 96, py + 9, "module off", ClientUi.WARNING)
        }
        val closeX = px + pw - 24
        val closeHovered = mouseX in closeX..(closeX + 18) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 3, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }
        // Import from clipboard
        val importW = 74
        val importX = closeX - importW - 6
        val importHovered = mouseX in importX..(importX + importW) && mouseY in (py + 7)..(py + 25)
        ClientUi.drawFlatButton(g, font, importX, py + 7, importW, 18, "Import", importHovered, false)
        hit(importX, py + 7, importW, 18) { importFromClipboard() }
        g.fill(px + 10, py + 30, px + pw - 10, py + 31, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        // List
        val listTop = py + 34
        val listBottom = py + ph - 34
        listRect = intArrayOf(px, listTop, px + pw, listBottom)
        val waypoints = module.waypointsHere(client)
        val player = client.player

        ClientUi.withScissor(g, px + 4, listTop, px + pw - 4, listBottom) {
            var cy = listTop + 2 - scroll
            if (waypoints.isEmpty()) {
                g.drawString(font, "No waypoints here yet. Add your first one below.", px + 14, listTop + 10, ClientUi.DIM, true)
            }
            waypoints.toList().forEach { wp ->
                val rowX = px + 10
                val rowW = pw - 20
                val hovered = mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + 22) && mouseY in listTop..listBottom
                ClientUi.drawListRow(g, rowX, cy, rowW, 22, ClientUi.hover("wprow:${wp.hashCode()}", hovered), renaming === wp)

                // Color dot — click to cycle.
                val color = WaypointsModule.colorOf(wp)
                ClientUi.fillRounded(g, rowX + 7, cy + 7, 8, 8, 4, color)
                run {
                    val yy = cy
                    hit(rowX + 3, yy + 3, 16, 16) { wp.color = (wp.color + 1).mod(WaypointsModule.COLORS.size) }
                }

                // Name (or rename input)
                if (renaming === wp) {
                    val caret = if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
                    g.drawString(font, trim(input, rowW - 150) + caret, rowX + 22, cy + 7, ClientUi.TEXT, true)
                } else {
                    // A hidden waypoint is still listed, just dimmed, so it reads as
                    // "switched off" rather than looking identical to a live one.
                    val nameColor = if (wp.hidden) ClientUi.DIM else ClientUi.TEXT
                    g.drawString(font, trim(wp.name, rowW - 180), rowX + 22, cy + 7, nameColor, true)
                    val yy = cy
                    hit(rowX + 18, yy, rowW - 190, 22) {
                        renaming = wp
                        input = wp.name
                        inputFocused = false
                    }
                }

                // Coords + distance
                val dist = player?.let {
                    val dx = wp.x + 0.5 - it.x
                    val dz = wp.z + 0.5 - it.z
                    sqrt(dx * dx + dz * dz).roundToInt()
                }
                val info = "${wp.x} ${wp.y} ${wp.z}" + (dist?.let { "  (${it}m)" } ?: "")
                g.drawString(font, info, rowX + rowW - font.width(info) - 59, cy + 7, ClientUi.DIM, true)

                // Show/hide in the world. Keeps the waypoint and its position - only the
                // in-world beam, block highlight and label stop drawing.
                val eyeX = rowX + rowW - 51
                val eyeHovered = mouseX in eyeX..(eyeX + 14) && mouseY in (cy + 4)..(cy + 18)
                val eyeColor = when {
                    wp.hidden -> if (eyeHovered) ClientUi.TEXT else ClientUi.alpha(ClientUi.DIM, 0.5f)
                    eyeHovered -> ClientUi.ACCENT_B
                    else -> ClientUi.TEXT
                }
                RiverIcons.draw(g, "eye", eyeX, cy + 5, 11, eyeColor)
                run {
                    val yy = cy
                    hit(eyeX - 3, yy + 1, 18, 20) {
                        wp.hidden = !wp.hidden
                        flash = if (wp.hidden) "Hid ${wp.name}" else "Showing ${wp.name}"
                        flashAt = System.currentTimeMillis()
                        RiverRuntime.saveConfig()
                    }
                }

                // Share (copy to clipboard)
                val shareX = rowX + rowW - 34
                val shareHovered = mouseX in shareX..(shareX + 14) && mouseY in (cy + 4)..(cy + 18)
                RiverIcons.draw(g, "copy", shareX, cy + 5, 11, if (shareHovered) ClientUi.ACCENT_B else ClientUi.DIM)
                run {
                    val yy = cy
                    hit(shareX - 3, yy + 1, 18, 20) {
                        minecraft?.keyboardHandler?.clipboard = WaypointShare.encode(wp)
                        flash = "Copied ${wp.name} to clipboard"
                        flashAt = System.currentTimeMillis()
                    }
                }

                // Delete
                val trashX = rowX + rowW - 17
                val trashHovered = mouseX in trashX..(trashX + 14) && mouseY in (cy + 4)..(cy + 18)
                RiverIcons.draw(g, "trash", trashX, cy + 5, 11, if (trashHovered) 0xFFFF8080.toInt() else ClientUi.DIM)
                run {
                    val yy = cy
                    hit(trashX - 3, yy + 1, 20, 20) {
                        waypoints.remove(wp)
                        if (renaming === wp) renaming = null
                        RiverRuntime.saveConfig()
                    }
                }

                cy += 25
            }
            contentHeight = waypoints.size * 25 + 4
        }
        ClientUi.drawScrollbar(g, px + pw - 8, listTop, listBottom - listTop, contentHeight, listBottom - listTop, scroll)

        // Footer: name input + add button
        val fy = py + ph - 28
        val addW = 76
        val inputW = pw - 20 - addW - 6
        ClientUi.drawListRow(g, px + 10, fy, inputW, 20, 0f, inputFocused && renaming == null)
        val placeholder = renaming != null
        val text = if (placeholder) "press Enter to rename" else input
        val caret = if (inputFocused && renaming == null && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        val textColor = when {
            placeholder -> ClientUi.DIM
            input.isEmpty() && !inputFocused -> ClientUi.DIM
            else -> ClientUi.TEXT
        }
        val shown = if (!placeholder && input.isEmpty() && !inputFocused) "waypoint name" else text
        g.drawString(font, trim(shown, inputW - 14) + caret, px + 16, fy + 6, textColor, true)
        hit(px + 10, fy, inputW, 20) {
            inputFocused = true
            renaming = null
        }

        val addX = px + 10 + inputW + 6
        val addHovered = mouseX in addX..(addX + addW) && mouseY in fy..(fy + 20)
        val addEnabled = player != null && renaming == null
        ClientUi.drawFlatButton(g, font, addX, fy, addW, 20, "Add here", addHovered && addEnabled, addEnabled)
        hit(addX, fy, addW, 20) { addWaypoint() }
    }

    private fun addWaypoint() {
        if (renaming != null) return
        val client = minecraft ?: return
        val module = module() ?: return
        if (client.player == null) return
        val name = input.trim().ifEmpty { "Waypoint ${module.waypointsHere(client).size + 1}" }
        module.addHere(client, name.take(20))
        input = ""
        RiverRuntime.saveConfig()
    }

    private fun importFromClipboard() {
        val client = minecraft ?: return
        val module = module() ?: return
        val clip = runCatching { client.keyboardHandler.clipboard }.getOrNull()
        val parsed = WaypointShare.decode(clip)
        if (parsed == null) {
            flash = "No River waypoint on the clipboard"
            flashAt = System.currentTimeMillis()
            return
        }
        // Land it in the current world/dimension so it shows up immediately.
        parsed.dimension = module.currentDimension(client)
        module.waypointsHere(client).add(parsed)
        flash = "Imported ${parsed.name}"
        flashAt = System.currentTimeMillis()
        RiverRuntime.saveConfig()
    }

    private fun commitRename() {
        val wp = renaming ?: return
        val name = input.trim()
        if (name.isNotEmpty()) wp.name = name.take(20)
        renaming = null
        input = ""
        RiverRuntime.saveConfig()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        val mx = mouseX
        val my = mouseY
        if (renaming != null) {
            // Clicking anywhere else commits the rename first.
            commitRename()
        }
        hits.asReversed().forEach { h ->
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                h.onClick()
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
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

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_ESCAPE -> {
                if (renaming != null) { renaming = null; input = ""; return true }
                onClose()
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (renaming != null) commitRename() else addWaypoint()
                return true
            }
            GLFW.GLFW_KEY_BACKSPACE -> {
                input = input.dropLast(1)
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (input.length < 20) input += codePoint.toString()
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
