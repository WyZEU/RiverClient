package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.hud.HudLayout
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleRegistry
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
//?} else {
/**///?}
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drag-and-drop HUD layout editor. Elements snap to screen edges, centre lines,
 * and to each other's edges/centres (nearest alignment wins)
 * with visible alignment guides; positions save into the active profile. Selecting
 * an element opens its full settings panel at the bottom of the screen.
 */
class RiverHudEditorScreen(private val parent: Screen?) : Screen(Component.literal("HUD Editor")), RiverScreen {

    /** The editor has to show the HUD at exactly the scale the game draws it at. */
    override fun capsGuiScale(): Boolean = false

    private var selected: Module? = null
    private var dragging: Module? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    /**
     * Corner-drag resize.
     *
     * Scrolling to resize works but is invisible - nothing on screen says it is
     * possible. Grabbing the bottom-right corner is what every editor does, so the
     * handle is drawn on the selected module and dragging it scales from the size
     * the drag started at.
     */
    private var resizing: Module? = null
    private var resizeStartScale = 100
    private var resizeStartDist = 1.0

    /** Side of the square grab handle, in GUI pixels. */
    private val handleSize = 7

//? if >=1.21.9 {
     // Square grab handle on the module's bottom-right corner.
     // Drawn only on the selected module so the editor does not turn into a grid of
     // handles, and brightened on hover so it is obvious it can be taken hold of.
    private fun drawResizeHandle(g: GuiGraphics, module: Module, mw: Int, mh: Int, mx: Double, my: Double) {
//?} else {
/*    // True when (mx,my) is inside the resize handle of [module].
    private fun overHandle(module: Module, mw: Int, mh: Int, mx: Double, my: Double): Boolean {
*///?}
        val hx = module.x + mw - handleSize / 2
        val hy = module.y + mh - handleSize / 2
//? if >=1.21.9 {
        val hot = overHandle(module, mw, mh, mx, my) || resizing === module
        val fill = if (hot) ClientUi.ACCENT_B else ClientUi.alpha(ClientUi.ACCENT_B, 0.75f)
        g.fill(hx, hy, hx + handleSize, hy + handleSize, fill)
        g.fill(hx, hy, hx + handleSize, hy + 1, ClientUi.alpha(ClientUi.TEXT, 0.55f))
        g.fill(hx, hy, hx + 1, hy + handleSize, ClientUi.alpha(ClientUi.TEXT, 0.55f))
//?} else {
/*        val pad = 3
        return mx >= hx - pad && mx <= hx + handleSize + pad &&
               my >= hy - pad && my <= hy + handleSize + pad
*///?}
    }

    /**
     * Distance from the module's anchor to the cursor.
     *
     * Modules scale about their (x, y) anchor, so the ratio of this distance
     * before and during the drag is exactly the ratio to scale by - which makes
     * the corner follow the mouse instead of drifting away from it.
     */
    private fun handleDistance(module: Module, mx: Double, my: Double): Double {
        val dx = mx - module.x
        val dy = my - module.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

//? if >=1.21.9 {
    // True when (mx,my) is inside the resize handle of [module].
    private fun overHandle(module: Module, mw: Int, mh: Int, mx: Double, my: Double): Boolean {
//?} else {
/*     // Square grab handle on the module's bottom-right corner.
     // Drawn only on the selected module so the editor does not turn into a grid of
     // handles, and brightened on hover so it is obvious it can be taken hold of.
    private fun drawResizeHandle(g: GuiGraphics, module: Module, mw: Int, mh: Int, mx: Double, my: Double) {
*///?}
        val hx = module.x + mw - handleSize / 2
        val hy = module.y + mh - handleSize / 2
//? if >=1.21.9 {
        val pad = 3
        return mx >= hx - pad && mx <= hx + handleSize + pad &&
               my >= hy - pad && my <= hy + handleSize + pad
//?} else {
/*        val hot = overHandle(module, mw, mh, mx, my) || resizing === module
        val fill = if (hot) ClientUi.ACCENT_B else ClientUi.alpha(ClientUi.ACCENT_B, 0.75f)
        g.fill(hx, hy, hx + handleSize, hy + handleSize, fill)
        g.fill(hx, hy, hx + handleSize, hy + 1, ClientUi.alpha(ClientUi.TEXT, 0.55f))
        g.fill(hx, hy, hx + 1, hy + handleSize, ClientUi.alpha(ClientUi.TEXT, 0.55f))
*///?}
    }
    private var guideX = -1
    private var guideY = -1
    private var resetArmedAt = 0L



    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    /** Only enabled elements appear in the editor; disabled ones vanish from the layout. */
    private fun editableModules(): List<Module> =
        ModuleRegistry.all.filter { it.acceptsDraggablePosition() && it.enabled }

    private fun select(module: Module?) {
        selected = module
    }

    /** The cog on a hovered element: its settings live in the menu, not in here. */
    private fun openSettings(module: Module) {
        minecraft?.setScreen(RiverMenuScreen(parent, module))
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        ClientUi.beginFrame()
        hits.clear()

        val client = minecraft ?: return
        // Very light veil so the real world stays clearly visible for placement.
        // When there is no world (opened from a menu), renderBackground drew the
        // panorama instead, so keep this subtle either way.
        g.fillGradient(0, 0, width, height, 0x22050608, 0x33050608)

        // Keep un-placed elements flowing in their stacks.
        HudLayout.apply(client, includeDisabled = false)
        selected?.let { if (!it.enabled) select(null) }

        val snapMargin = 4

        // Safe-area frame
        ClientUi.drawRoundedBorder(g, snapMargin - 1, snapMargin - 1, width - snapMargin * 2 + 2, height - snapMargin * 2 + 2, 6, ClientUi.alpha(ClientUi.BORDER, 0.35f))

        // Alignment guides while dragging
        if (dragging != null) {
            if (guideX >= 0) g.fill(guideX, 0, guideX + 1, height, ClientUi.alpha(ClientUi.ACCENT_B, 0.85f))
            if (guideY >= 0) g.fill(0, guideY, width, guideY + 1, ClientUi.alpha(ClientUi.ACCENT_B, 0.85f))
        }

        editableModules().forEach { module ->
            val (mw, mh) = module.editorApproximateSize(client)
            // Generous hover zone that includes the corner X, so it doesn't flicker away.
            val hovered = mouseX in (module.x - 6)..(module.x + mw + 14) && mouseY in (module.y - 16)..(module.y + mh + 6)
            val isSelected = selected === module
            val isDragging = dragging === module

            module.renderScaled(g) { module.renderEditorPreview(client, g, partialTick) }

            if (isSelected) drawResizeHandle(g, module, mw, mh, mouseX.toDouble(), mouseY.toDouble())

            val border = when {
                isDragging -> ClientUi.ACCENT_B
                isSelected -> ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
                hovered -> ClientUi.alpha(ClientUi.BORDER_STRONG, 0.8f)
                else -> ClientUi.alpha(ClientUi.BORDER, 0.55f)
            }
            ClientUi.drawRoundedBorder(g, module.x - 3, module.y - 3, mw + 6, mh + 6, 6, border)
            if (hovered && !isDragging) {
                g.drawString(font, module.displayName, module.x, module.y - 12, ClientUi.MUTED, true)

                // Two corner buttons: the cog opens this module's settings in the menu,
                // the X switches it off. Both sit on the element you are pointing at, so
                // nothing has to cover the middle of the screen to configure it.
                val xbX = module.x + mw - 4
                val xbY = module.y - 11
                val cogX = xbX - 16
                val cogHovered = mouseX in (cogX - 3)..(cogX + 17) && mouseY in (xbY - 3)..(xbY + 17)
                ClientUi.fillRounded(g, cogX, xbY, 14, 14, ClientUi.RADIUS_BUTTON, if (cogHovered) 0xF02B3350.toInt() else 0xE61B1E28.toInt())
                ClientUi.drawRoundedBorder(g, cogX, xbY, 14, 14, 5, if (cogHovered) ClientUi.ACCENT_B else ClientUi.BORDER_STRONG)
                RiverIcons.draw(g, "gear", cogX + 2, xbY + 2, 10, if (cogHovered) ClientUi.TEXT else ClientUi.MUTED)
                hit(cogX - 4, xbY - 4, 22, 22) { openSettings(module) }

                val xHovered = mouseX in (xbX - 3)..(xbX + 17) && mouseY in (xbY - 3)..(xbY + 17)
                ClientUi.fillRounded(g, xbX, xbY, 14, 14, ClientUi.RADIUS_BUTTON, if (xHovered) 0xF0C33B4E.toInt() else 0xE61B1E28.toInt())
                ClientUi.drawRoundedBorder(g, xbX, xbY, 14, 14, 5, if (xHovered) 0xFFFF8FA3.toInt() else ClientUi.BORDER_STRONG)
                RiverIcons.draw(g, "x", xbX + 2, xbY + 2, 10, if (xHovered) ClientUi.TEXT else ClientUi.MUTED)
                hit(xbX - 4, xbY - 4, 22, 22) {
                    module.enabled = false
                    if (selected === module) select(null)
                    RiverRuntime.saveConfig()
                }
            }
        }

        ClientUi.withOverlayLayer(g) { drawTopBar(g, mouseX, mouseY) }
    }

    private fun drawTopBar(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Minimal, content-width, centred: just an icon, the two actions, and a thin hint
        // line underneath - no wide bar spanning the screen.
        val resetArmed = System.currentTimeMillis() - resetArmedAt < 3000
        val resetLabel = if (resetArmed) "Confirm" else "Reset"
        val doneLabel = "Done"
        val pad = 8
        val gap = 6
        val btnH = 18
        val barH = 26
        val resetW = font.width(resetLabel) + 16
        val doneW = font.width(doneLabel) + 16
        val iconGap = 6
        val labelW = 12 + iconGap + font.width("HUD Editor")

        val barW = pad + labelW + 12 + resetW + gap + doneW + pad
        val barX = (width - barW) / 2
        val barY = 8
        ClientUi.drawPanel(g, barX, barY, barW, barH)

        RiverIcons.draw(g, "layout", barX + pad, barY + (barH - 12) / 2, 12, ClientUi.ACCENT_B)
        g.drawString(font, "HUD Editor", barX + pad + 12 + iconGap, barY + (barH - font.lineHeight) / 2, ClientUi.TEXT, true)

        val btnY = barY + (barH - btnH) / 2
        val doneX = barX + barW - pad - doneW
        val doneHovered = mouseX in doneX..(doneX + doneW) && mouseY in btnY..(btnY + btnH)
        ClientUi.drawFlatButton(g, font, doneX, btnY, doneW, btnH, doneLabel, doneHovered, true)
        hit(doneX, btnY, doneW, btnH) { onClose() }

        val resetX = doneX - gap - resetW
        val resetHovered = mouseX in resetX..(resetX + resetW) && mouseY in btnY..(btnY + btnH)
        ClientUi.drawFlatButton(g, font, resetX, btnY, resetW, btnH, resetLabel, resetHovered, false)
        hit(resetX, btnY, resetW, btnH) {
            if (resetArmed) {
                ModuleRegistry.all.filter { it.acceptsDraggablePosition() }.forEach { it.resetPosition() }
                resetArmedAt = 0
            } else {
                resetArmedAt = System.currentTimeMillis()
            }
        }

        val hint = "drag to move · drag the corner to resize · cog for settings"
        g.drawString(font, hint, (width - font.width(hint)) / 2, barY + barH + 5, ClientUi.DIM, true)
    }

    // ------------------------------------------------------------------ input

//? if >=1.21.9 {
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val mx = event.x()
        val my = event.y()
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
//?} else {
/*    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX
        val my = mouseY
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
*///?}

        hits.asReversed().forEach { h ->
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                h.onClick()
                return true
            }
        }

        val client = minecraft ?: return false

        // The handle is only drawn on the selected module, so it gets first refusal
        // on the click - otherwise the module underneath starts moving instead.
        selected?.let { module ->
            val (mw, mh) = module.editorApproximateSize(client)
            if (overHandle(module, mw, mh, mx, my)) {
                resizing = module
                resizeStartScale = module.editorScalePercent()
                resizeStartDist = handleDistance(module, mx, my)
                return true
            }
        }

        // Topmost module under cursor (iterate reversed so later-rendered wins).
        editableModules().asReversed().forEach { module ->
            val (mw, mh) = module.editorApproximateSize(client)
            if (mx >= module.x && mx <= module.x + mw && my >= module.y && my <= module.y + mh) {
                select(module)
                dragging = module
                dragOffsetX = (mx - module.x).toInt()
                dragOffsetY = (my - module.y).toInt()
                return true
            }
        }

        select(null)
//? if >=1.21.9 {
        return super.mouseClicked(event, doubled)
//?} else {
/*        return super.mouseClicked(mouseX, mouseY, button)
*///?}
    }

    /** Nearest snap candidate within [snap] px, or null. Returns (snappedPos, guideCoord). */
    private fun snapAxis(current: Int, candidates: List<Pair<Int, Int>>, snap: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDist = snap + 1
        for ((target, guide) in candidates) {
            val dist = abs(current - target)
            if (dist <= snap && dist < bestDist) {
                bestDist = dist
                best = target to guide
            }
        }
        return best
    }

//? if >=1.21.9 {
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
//?} else {
/*    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
*///?}

        // Resizing takes priority; the module is not moving while its corner is held.
        resizing?.let { module ->
//? if >=1.21.9 {
            val dist = handleDistance(module, event.x(), event.y())
//?} else {
/*            val dist = handleDistance(module, mouseX, mouseY)
*///?}
            val ratio = if (resizeStartDist <= 0.5) 1.0 else dist / resizeStartDist
            module.editorSetScalePercent((resizeStartScale * ratio).roundToInt())
            return true
        }

//? if >=1.21.9 {
        val module = dragging ?: return super.mouseDragged(event, dragX, dragY)
//?} else {
/*        val module = dragging ?: return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
*///?}
        val client = minecraft ?: return false
        val (mw, mh) = module.editorApproximateSize(client)

//? if >=1.21.9 {
        var nx = (event.x() - dragOffsetX).roundToInt()
        var ny = (event.y() - dragOffsetY).roundToInt()
//?} else {
/*        var nx = (mouseX - dragOffsetX).roundToInt()
        var ny = (mouseY - dragOffsetY).roundToInt()
*///?}

        val snap = 6
        val margin = 4
        guideX = -1
        guideY = -1

        // Each candidate is (position the module would snap to, screen coord to draw the
        // guide at). Screen edges/centre first, then alignment against every other element
        // so elements line up with each other, not just with the screen.
        val candidatesX = ArrayList<Pair<Int, Int>>(16)
        candidatesX.add(margin to margin)
        candidatesX.add((width - mw) / 2 to width / 2)
        candidatesX.add(width - mw - margin to width - margin)

        val candidatesY = ArrayList<Pair<Int, Int>>(16)
        candidatesY.add(margin to margin)
        candidatesY.add((height - mh) / 2 to height / 2)
        candidatesY.add(height - mh - margin to height - margin)

        for (other in editableModules()) {
            if (other === module) continue
            val (ow, oh) = other.editorApproximateSize(client)
            val ox = other.x
            val oy = other.y
            // Shared left / right / centre, plus edge-to-edge so elements can sit flush.
            candidatesX.add(ox to ox)
            candidatesX.add(ox + ow - mw to ox + ow)
            candidatesX.add(ox + ow / 2 - mw / 2 to ox + ow / 2)
            candidatesX.add(ox + ow to ox + ow)
            candidatesX.add(ox - mw to ox)

            candidatesY.add(oy to oy)
            candidatesY.add(oy + oh - mh to oy + oh)
            candidatesY.add(oy + oh / 2 - mh / 2 to oy + oh / 2)
            candidatesY.add(oy + oh to oy + oh)
            candidatesY.add(oy - mh to oy)
        }

        // Nearest wins (not first match), so the closest alignment is the one that takes.
        snapAxis(nx, candidatesX, snap)?.let { (target, guide) -> nx = target; guideX = guide }
        snapAxis(ny, candidatesY, snap)?.let { (target, guide) -> ny = target; guideY = guide }

        module.x = nx.coerceIn(0, (width - mw).coerceAtLeast(0))
        module.y = ny.coerceIn(0, (height - mh).coerceAtLeast(0))
        module.placed = true
        // Re-capture on every drag step: HudLayout re-resolves anchors each frame, so the
        // stored anchor must always reproduce the position the user is currently holding.
        module.captureAnchor(client, width, height)
        return true
    }

//? if >=1.21.9 {
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
//?} else {
/*    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
*///?}
        var acted = false
        if (resizing != null) {
            resizing = null
            acted = true
        }
        if (dragging != null) {
            dragging = null
            guideX = -1
            guideY = -1
            acted = true
        }
        if (acted) {
            RiverRuntime.saveConfig()
            return true
        }
//? if >=1.21.9 {
        return super.mouseReleased(event)
//?} else {
/*        return super.mouseReleased(mouseX, mouseY, button)
*///?}
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val client = minecraft ?: return false
        editableModules().asReversed().forEach { module ->
            val (mw, mh) = module.editorApproximateSize(client)
            if (mouseX >= module.x && mouseX <= module.x + mw && mouseY >= module.y && mouseY <= module.y + mh) {
                module.editorSetScalePercent(module.editorScalePercent() + (deltaY * 5).roundToInt())
                return true
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
*///?}
//? if >=1.21.9 {
        when (event.key()) {
//?} else {
/*        when (keyCode) {
*///?}
            GLFW.GLFW_KEY_ESCAPE -> {
                if (selected != null) { select(null); return true }
                onClose()
                return true
            }
            GLFW.GLFW_KEY_RIGHT_SHIFT -> { onClose(); return true }
        }
        // Arrow-key nudging for precision.
        selected?.let { module ->
            fun nudge(dx: Int, dy: Int): Boolean {
                module.moveBy(dx, dy)
                module.placed = true
                minecraft?.let { module.captureAnchor(it, width, height) }
                return true
            }
//? if >=1.21.9 {
            when (event.key()) {
//?} else {
/*            when (keyCode) {
*///?}
                GLFW.GLFW_KEY_LEFT -> return nudge(-1, 0)
                GLFW.GLFW_KEY_RIGHT -> return nudge(1, 0)
                GLFW.GLFW_KEY_UP -> return nudge(0, -1)
                GLFW.GLFW_KEY_DOWN -> return nudge(0, 1)
            }
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

    override fun onClose() {
        RiverRuntime.saveConfig()
        minecraft?.setScreen(parent)
    }

    override fun removed() {
        RiverRuntime.saveConfig()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * In a world: draw nothing so the live world shows behind the editor. Outside a
     * world (opened from a menu) there's no world to show, so paint the rotating
     * panorama instead of a black screen. Never blur (one blur per frame).
     */
//? if >=26.1 {
/*    override fun extractBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        if (minecraft?.level == null) {
//? if >=26.1 {
/*            extractPanorama(g, partialTick)
*///?} else {
            renderPanorama(g, partialTick)
//?}
        }
    }
}
