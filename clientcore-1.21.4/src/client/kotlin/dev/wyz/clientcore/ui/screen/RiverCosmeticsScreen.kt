package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.cosmetic.RiverCape
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.module.impl.NameTagModule
import dev.wyz.clientcore.nametag.RiverBadgeState
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.min

/**
 * The Cosmetics popup: a live 3D preview of YOUR player (real skin) wearing the selected
 * cape, cape cards with texture previews, and the River badge toggle. Opened from the
 * River menu sidebar or the main menu. Everything here is purely visual.
 *
 * The preview reuses vanilla's skin-widget render path (submitSkinRenderState) with a
 * second submit for the cape model, so the player and cape share the same transform.
 * Drag the preview to rotate; it slowly auto-spins otherwise.
 */
class RiverCosmeticsScreen(private val parent: Screen?) : Screen(Component.literal("Cosmetics")), RiverScreen {

    private val mcClient = Minecraft.getInstance()

    private var manualRotY = 0f
    private var rotX = -5f
    private var draggingPreview = false
    private var previewRect = intArrayOf(0, 0, 0, 0)

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    private fun capeModule(): CapeModule? = ModuleRegistry.get("cape")
    private fun nametagModule(): NameTagModule? = ModuleRegistry.get("nametag")

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, 0x66040507)
        ClientUi.beginFrame()
        hits.clear()

        val cape = capeModule() ?: return
        val pw = min(452, width - 20)
        val ph = min(212, height - 30)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        // Header
        RiverIcons.draw(g, "shirt", px + 12, py + 10, 13, ClientUi.ACCENT_B)
        g.drawString(font, "Cosmetics", px + 31, py + 12, ClientUi.TEXT, true)
        val closeX = px + pw - 24
        val closeHovered = mouseX in closeX..(closeX + 18) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", closeX + 3, py + 11, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, py + 6, 20, 22) { onClose() }
        g.fill(px + 10, py + 30, px + pw - 10, py + 31, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        // Live 3D preview (left column)
        val prevX = px + 12
        val prevY = py + 38
        val prevW = 120
        val prevH = ph - 50
        drawPlayerPreview(g, prevX, prevY, prevW, prevH, cape)

        // Right column
        val rx = px + 144
        val rw = pw - 144 - 12
        val equippedIndex = if (cape.enabled) cape.styleIndex() else -1

        // Capes
        g.drawString(font, "CAPES", rx, py + 38, ClientUi.DIM, true)
        val cardW = 68
        val cardH = 100
        val cardGap = 8
        val cardsY = py + 50
        drawNoneCard(g, rx, cardsY, cardW, cardH, mouseX, mouseY, equippedIndex == -1) {
            cape.enabled = false
            RiverRuntime.saveConfig()
        }
        RiverCape.STYLES.forEachIndexed { i, style ->
            val cx = rx + (i + 1) * (cardW + cardGap)
            drawCapeCard(g, cx, cardsY, cardW, cardH, style, RiverCape.LABELS[i], mouseX, mouseY, equippedIndex == i) {
                cape.setStyleIndex(i)
                cape.enabled = true
                RiverRuntime.saveConfig()
            }
        }

        // Badge
        val nametag = nametagModule()
        if (nametag != null) {
            val badgeY = cardsY + cardH + 12
            val badgeOn = nametag.enabled && nametag.showRiverBadge()
            drawToggleRow(g, rx, badgeY, rw, "River badge", badgeOn, mouseX, mouseY, "fx:badge") { on ->
                nametag.setShowRiverBadge(on)
                if (on) nametag.enabled = true
                RiverRuntime.saveConfig()
            }
        }
    }

    // ------------------------------------------------------------------ 3D preview

    private fun drawPlayerPreview(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, cape: CapeModule) {
        ClientUi.drawSectionCard(g, x, y, w, h, 0f, false)
        previewRect = intArrayOf(x, y, x + w, y + h)

        // Slow auto-spin plus whatever the user dragged. Spin 0 faces the camera.
        val autoY = (System.currentTimeMillis() % 14400L) / 40f
        val spin = manualRotY + autoY
        val inset = 4

        val player = mcClient.player
        if (player != null) {
            // 1.21.4 has no GuiGraphics.submitEntityRenderState (that deferred-submit path
            // came with a later render rewrite). InventoryScreen's classic helper draws the
            // real live entity through the normal entity render pipeline instead - same
            // effect: every render layer applies, River cape included via AvatarCapeMixin.
            // Spin is baked into the pose quaternion since there's no render-state field to
            // set it on directly here.
            val tilt = Quaternionf().rotateX(Math.toRadians(rotX.toDouble()).toFloat())
            val pose = Quaternionf().rotateZ(Math.PI.toFloat())
                .mul(Quaternionf().rotateY(Math.toRadians(spin.toDouble()).toFloat()))
            val scale = h / 3.1f
            val anchorX = (x + w / 2).toFloat()
            val anchorY = (y + h - 14).toFloat()
            InventoryScreen.renderEntityInInventory(g, anchorX, anchorY, scale, Vector3f(0f, 0f, 0f), pose, tilt, player)

            val nametag = nametagModule()
            var name: Component = player.displayName ?: Component.literal(player.name.string)
            if (nametag?.active == true && nametag.showRiverBadge()) {
                name = Component.empty().append(RiverBadgeState.badgeComponent()).append(Component.literal(" ")).append(name)
            }
            g.drawString(font, name, x + (w - font.width(name)) / 2, y + 8, -1, true)
        } else {
            // No world (opened from the main menu) - no live entity to render through the
            // helper above, so just show the styled name where the 3D preview would be.
            val nametag = nametagModule()
            var name: Component = Component.literal(mcClient.user.name)
            if (nametag?.active == true && nametag.showRiverBadge()) {
                name = Component.empty().append(RiverBadgeState.badgeComponent()).append(Component.literal(" ")).append(name)
            }
            g.drawString(font, name, x + (w - font.width(name)) / 2, y + h / 2 - 4, -1, true)
        }
        g.drawString(font, "drag to spin", x + (w - font.width("drag to spin")) / 2, y + h - 12, ClientUi.alpha(ClientUi.DIM, 0.8f), true)
    }

    // ------------------------------------------------------------------ cards + rows

    private fun drawNoneCard(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int, selected: Boolean, onClick: () -> Unit) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        drawCardBase(g, x, y, w, h, hovered, selected)
        RiverIcons.draw(g, "x", x + w / 2 - 6, y + 30, 12, if (selected) ClientUi.ACCENT_B else ClientUi.DIM)
        if (selected) {
            g.drawString(font, "equipped", x + (w - font.width("equipped")) / 2, y + h - 26, ClientUi.POSITIVE, true)
        }
        val label = "None"
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + h - 14, if (selected) ClientUi.TEXT else ClientUi.MUTED, true)
        hit(x, y, w, h, onClick)
    }

    private fun drawCapeCard(
        g: GuiGraphics, x: Int, y: Int, w: Int, h: Int,
        style: String, label: String,
        mouseX: Int, mouseY: Int, selected: Boolean, onClick: () -> Unit
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        drawCardBase(g, x, y, w, h, hovered, selected)

        // Cape back face (u 1..11, v 1..17 of the 64x32 layout).
        val tex = RiverCape.previewTexture(style)
        val size = RiverCape.textureSize(style)
        val texW = size[0]
        val texH = size[1]
        val srcU = texW * 1f / 64f
        val srcV = texH * 1f / 32f
        val srcW = texW * 10 / 64
        val srcH = texH * 16 / 32
        val pvW = 40
        val pvH = 64
        val pvX = x + (w - pvW) / 2
        val pvY = y + 6
        g.blit(RenderType::guiTextured, tex, pvX, pvY, srcU, srcV, pvW, pvH, srcW, srcH, texW, texH)
        ClientUi.drawRoundedBorder(g, pvX - 1, pvY - 1, pvW + 2, pvH + 2, 3, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        if (selected) {
            g.drawString(font, "equipped", x + (w - font.width("equipped")) / 2, y + h - 26, ClientUi.POSITIVE, true)
        }
        val shown = trim(label, w - 6)
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + h - 14, if (selected) ClientUi.TEXT else ClientUi.MUTED, true)
        hit(x, y, w, h, onClick)
    }

    private fun drawCardBase(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val anim = ClientUi.hover("cosmetics:$x:$y", hovered)
        ClientUi.drawSectionCard(g, x, y, w, h, anim, selected)
        if (selected) {
            ClientUi.drawRoundedBorder(g, x, y, w, h, ClientUi.RADIUS_CARD, ClientUi.BORDER_STRONG)
        }
    }

    private fun drawToggleRow(
        g: GuiGraphics, x: Int, y: Int, w: Int, label: String,
        on: Boolean, mouseX: Int, mouseY: Int, id: String, onToggle: (Boolean) -> Unit
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 20)
        ClientUi.drawListRow(g, x, y, w, 20, ClientUi.hover("row:$id", hovered), false)
        g.drawString(font, trim(label, w - 44), x + 7, y + 6, if (on) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 34, y + 3, on, hovered, id)
        hit(x, y, w, 20) { onToggle(!on) }
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        if (mouseX >= previewRect[0] && mouseX <= previewRect[2] && mouseY >= previewRect[1] && mouseY <= previewRect[3]) {
            draggingPreview = true
            return true
        }
        hits.asReversed().forEach { h ->
            if (mouseX >= h.x1 && mouseX <= h.x2 && mouseY >= h.y1 && mouseY <= h.y2) {
                h.onClick()
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (draggingPreview) {
            manualRotY += dragX.toFloat() * 2.5f
            rotX = (rotX - dragY.toFloat() * 1.5f).coerceIn(-50f, 20f)
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        draggingPreview = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
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
