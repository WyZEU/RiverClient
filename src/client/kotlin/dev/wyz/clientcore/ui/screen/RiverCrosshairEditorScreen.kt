package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CrosshairModule
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
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.min

/**
 * Draw your own crosshair a pixel at a time, the way Feather and Lunar let you: left
 * click or drag to fill, right click to erase, mirrored four ways by default so a
 * symmetric crosshair takes a quarter of the clicks. Previews sit on a dark and a light
 * background at real size, because an outline that vanishes against the sky is the
 * usual surprise.
 */
class RiverCrosshairEditorScreen(
    private val parent: Screen?,
    initialTarget: Boolean = false
) : Screen(Component.literal("Crosshair editor")), RiverScreen {

    /** Which drawing is open: false for the normal crosshair, true for the one on target. */
    private var target = initialTarget
    private var grid: BooleanArray = load(initialTarget)
    private var mirror = true
    /** True while a drag fills, false while it erases, null when no button is held. */
    private var painting: Boolean? = null
    private var gridRect = intArrayOf(0, 0, 0)

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Hit>()

    /** A drawing to start from: what was drawn before, else the normal drawing, else the shape. */
    private fun load(forTarget: Boolean): BooleanArray {
        val module = module() ?: return BooleanArray(CrosshairModule.GRID * CrosshairModule.GRID)
        val s = module.editorSettings()
        val own = module.pixelsOf(s, forTarget)
        if (own.any { it }) return own
        val normal = module.pixelsOf(s, false)
        return if (forTarget && normal.any { it }) normal else module.rasterise(s)
    }

    private fun switchTo(forTarget: Boolean) {
        if (forTarget == target) return
        target = forTarget
        grid = load(forTarget)
    }

    private fun module(): CrosshairModule? = ModuleRegistry.get("crosshair")

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    private fun changed() {
        module()?.savePixels(grid, target)
    }

    private fun set(cx: Int, cy: Int, value: Boolean) {
        val n = CrosshairModule.GRID
        val cells = if (mirror) {
            listOf(cx to cy, n - 1 - cx to cy, cx to n - 1 - cy, n - 1 - cx to n - 1 - cy)
        } else {
            listOf(cx to cy)
        }
        var any = false
        for ((x, y) in cells) {
            val i = y * n + x
            if (grid[i] != value) {
                grid[i] = value
                any = true
            }
        }
        if (any) changed()
    }

    private fun cellAt(mx: Double, my: Double): Pair<Int, Int>? {
        val gx = gridRect[0]
        val gy = gridRect[1]
        val cell = gridRect[2]
        if (cell <= 0 || mx < gx || my < gy) return null
        val cx = ((mx - gx) / cell).toInt()
        val cy = ((my - gy) / cell).toInt()
        if (cx !in 0 until CrosshairModule.GRID || cy !in 0 until CrosshairModule.GRID) return null
        return cx to cy
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
        val style = module.editorSettings()
        val n = CrosshairModule.GRID
        val mid = CrosshairModule.GRID_CENTER

        val pw = min(360, width - 20)
        val ph = min(272, height - 24)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        RiverIcons.draw(g, "crosshair", px + 12, py + 11, 13, ClientUi.ACCENT_B)
        g.drawString(font, "Draw your crosshair", px + 31, py + 12, ClientUi.TEXT, true)
        val closeX = px + pw - 24
        val closeHovered = mouseX in closeX..(closeX + 18) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 3, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }
        g.fill(px + 10, py + 30, px + pw - 10, py + 31, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        // Which crosshair is being drawn.
        val tabY = py + 38
        val tabW = (pw - 28 - 6) / 2
        listOf(false to "Normal", true to "Aiming at a player or mob").forEachIndexed { i, (forTarget, label) ->
            val tx = px + 14 + i * (tabW + 6)
            val over = mouseX in tx..(tx + tabW) && mouseY in tabY..(tabY + 18)
            ClientUi.drawListRow(g, tx, tabY, tabW, 18, if (over) 1f else 0f, target == forTarget)
            val color = if (target == forTarget) ClientUi.TEXT else ClientUi.DIM
            g.drawString(font, label, tx + (tabW - font.width(label)) / 2, tabY + 5, color, true)
            hit(tx, tabY, tabW, 18) { switchTo(forTarget) }
        }

        // Canvas
        val cell = ((ph - 92) / n).coerceIn(6, 13)
        val gx = px + 14
        val gy = py + 66
        gridRect = intArrayOf(gx, gy, cell)
        val fill = 0xFF000000.toInt() or (style.red shl 16) or (style.green shl 8) or style.blue
        val hovered = cellAt(mouseX.toDouble(), mouseY.toDouble())
        g.fill(gx - 1, gy - 1, gx + n * cell + 1, gy + n * cell + 1, ClientUi.BORDER)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val cx = gx + x * cell
                val cy = gy + y * cell
                val color = when {
                    grid[y * n + x] -> fill
                    (x + y) % 2 == 0 -> 0xFF1B1D23.toInt()
                    else -> 0xFF16181D.toInt()
                }
                g.fill(cx, cy, cx + cell, cy + cell, color)
            }
        }
        // Centre marks on the frame, so the middle is findable on an empty canvas.
        val guide = ClientUi.alpha(ClientUi.ACCENT_B, 0.6f)
        g.fill(gx + mid * cell, gy - 4, gx + (mid + 1) * cell, gy - 2, guide)
        g.fill(gx - 4, gy + mid * cell, gx - 2, gy + (mid + 1) * cell, guide)
        if (hovered != null) {
            val hx = hovered.first
            val hy = hovered.second
            g.fill(gx + hx * cell, gy + hy * cell, gx + (hx + 1) * cell, gy + (hy + 1) * cell, 0x40FFFFFF)
        }

        // Keep painting while a button is held and the pointer moves.
        val mode = painting
        if (mode != null && hovered != null) set(hovered.first, hovered.second, mode)

        // Side column: previews at real size, then actions.
        val sx = gx + n * cell + 14
        val sw = px + pw - 12 - sx
        g.drawString(font, "Preview", sx, gy - 2, ClientUi.DIM, true)
        val boxW = (sw - 6) / 2
        val boxH = 40
        val by = gy + 10
        val rects = ArrayList<IntArray>()
        for (i in grid.indices) {
            if (grid[i]) rects.add(intArrayOf(i % n - mid, i / n - mid, 1, 1))
        }
        for ((bg, bx) in listOf(0xFF22252B.toInt() to sx, 0xFF8FB4E0.toInt() to sx + boxW + 6)) {
            g.fill(bx, by, bx + boxW, by + boxH, bg)
            ClientUi.withScissor(g, bx, by, bx + boxW, by + boxH) {
                module.drawRects(g, rects, bx + (boxW - 1) / 2, by + (boxH - 1) / 2, 1, style)
            }
        }

        var ry = by + boxH + 12
        fun button(label: String, primary: Boolean = false, onClick: () -> Unit) {
            val over = mouseX in sx..(sx + sw) && mouseY in ry..(ry + 18)
            ClientUi.drawFlatButton(g, font, sx, ry, sw, 18, label, over, primary)
            hit(sx, ry, sw, 18, onClick)
            ry += 22
        }
        button(if (mirror) "Mirror: on" else "Mirror: off") { mirror = !mirror }
        button("Start from shape") {
            val shape = module.rasterise(style)
            for (i in grid.indices) grid[i] = shape[i]
            changed()
        }
        button("Clear") {
            grid.fill(false)
            changed()
        }
        button("Done", primary = true) { onClose() }

        g.drawString(font, "Left click draws, right click erases.", px + 14, py + ph - 16, ClientUi.DIM, true)
    }

    private fun press(mx: Double, my: Double, button: Int): Boolean {
        val cell = cellAt(mx, my)
        if (cell != null && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            val fill = button == GLFW.GLFW_MOUSE_BUTTON_LEFT
            painting = fill
            set(cell.first, cell.second, fill)
            return true
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        for (h in hits.asReversed()) {
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                h.onClick()
                return true
            }
        }
        return false
    }

//? if >=1.21.9 {
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        return press(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubled)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        painting = null
        return super.mouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }
//?} else {
/*    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return press(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        painting = null
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
*///?}

    override fun onClose() {
        painting = null
        RiverRuntime.saveConfig()
        minecraft?.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false
}
