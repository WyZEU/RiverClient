package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.cosmetic.CapeEntitlements
import dev.wyz.clientcore.cosmetic.RiverCape
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.module.impl.NameTagModule
import dev.wyz.clientcore.nametag.RiverBadgeState
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverText
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.Minecraft
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
import net.minecraft.client.model.geom.ModelLayers
//? if >=26.2 {
/*import net.minecraft.client.model.Model
import net.minecraft.client.renderer.rendertype.RenderTypes
*///?} else {
//? if >=1.21.11 {
import net.minecraft.client.model.player.PlayerModel
//?} else {
/*import net.minecraft.client.model.PlayerModel
*///?}
//?}
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
//?} else {
/*import net.minecraft.client.gui.screens.inventory.InventoryScreen
*///?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import net.minecraft.network.chat.Component
//? if >=1.21.9 {
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.PlayerModelType
import net.minecraft.world.phys.Vec3
//?} else {
/**///?}
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import kotlin.math.min
import dev.wyz.clientcore.compat.riverBlit

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
//? if >=1.21.9 {
//? if >=26.2 {
/*    private val wideModel = Model.Simple(mcClient.entityModels.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent)
*///?} else {
    private val wideModel = PlayerModel(mcClient.entityModels.bakeLayer(ModelLayers.PLAYER), false)
//?}
//? if >=26.2 {
/*    private val slimModel = Model.Simple(mcClient.entityModels.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent)
*///?} else {
    private val slimModel = PlayerModel(mcClient.entityModels.bakeLayer(ModelLayers.PLAYER_SLIM), true)
//?}
    private val skinLookup = mcClient.skinManager.createLookup(mcClient.gameProfile, true)
//?} else {
/**///?}

    private var manualRotY = 0f
    private var rotX = -5f
    private var draggingPreview = false
    private var previewRect = intArrayOf(0, 0, 0, 0)

    // Code redemption for capes that are given out by a creator rather than shipped free.
    private var redeemInput = ""
    private var redeemFocused = false
    private var redeemMessage = ""
    private var redeemOk = false
    private var redeeming = false

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    private fun capeModule(): CapeModule? = ModuleRegistry.get("cape")
    private fun nametagModule(): NameTagModule? = ModuleRegistry.get("nametag")

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        g.fill(0, 0, width, height, if (parentShown) 0x8C05060C.toInt() else 0x66040507)
        ClientUi.beginFrame()
        hits.clear()

        val cape = capeModule() ?: return
        val pw = min(452, width - 20)

        /*
          Worked out before the panel is drawn, because the panel has to be tall enough for
          however many rows of cards there are. Unlocking a cape adds a card, and with the
          height fixed the extra row landed outside the panel.
        */
        val cardW = 68
        val cardH = 100
        val cardGap = 8
        val unlocked = RiverCape.STYLES.withIndex().filter { (_, style) -> !CapeEntitlements.locked(style) && RiverCape.available(style) }
        val perRow = (((pw - 144 - 12) + cardGap) / (cardW + cardGap)).coerceAtLeast(1)
        val rowsOfCards = ((unlocked.size + 1) + perRow - 1) / perRow

        /*
          Measured from what the panel holds rather than carried as a number to remember.
          At 212 the last toggle finished four pixels from the bottom edge, which is what
          made it look jammed in, and any row added below the cards had to remember to
          move the constant too.
        */
        val ph = min(
            CARDS_TOP + rowsOfCards * cardH + (rowsOfCards - 1) * cardGap + BELOW_CARDS,
            height - 30
        )
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph, seeThrough = parentShown)

        // Header, in the same 34px row as the menu and the friends screen: what this is,
        // what you are wearing, and the close button.
        val equippedIndex = if (cape.enabled) cape.styleIndex() else -1
        RiverIcons.draw(g, "shirt", px + 12, py + 11, 12, ClientUi.ACCENT_B)
        ClientUi.drawGradientTitle(g, font, "Cosmetics", px + 30, py + 13)
        val wearing = if (equippedIndex >= 0) "Wearing ${RiverCape.LABELS.getOrNull(equippedIndex) ?: "a cape"}" else "No cape"
        g.drawString(font, trim(wearing, pw - 100 - RiverText.boldWidth(font, "Cosmetics")), px + 30 + RiverText.boldWidth(font, "Cosmetics") + 8, py + 13, ClientUi.DIM, false)
        val closeX = px + pw - 28
        val closeHovered = mouseX in closeX..(closeX + 20) && mouseY in (py + 7)..(py + 27)
        val closeAnim = ClientUi.hover("cosmetics:close", closeHovered)
        if (closeAnim > 0.01f) g.fill(closeX, py + 7, closeX + 20, py + 27, ClientUi.alpha(ClientUi.PANEL_ALT, closeAnim))
        RiverIcons.draw(g, "x", closeX + 5, py + 12, 10, if (closeHovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, py + 7, 20, 20) { onClose() }
        g.fill(px + 1, py + HEADER_H - 1, px + pw - 1, py + HEADER_H, ClientUi.BORDER)

        // Live 3D preview (left column)
        val prevX = px + 12
        val prevY = py + HEADER_H + 8
        val prevW = 120
        val prevH = ph - HEADER_H - 20
        drawPlayerPreview(g, prevX, prevY, prevW, prevH, cape)

        // Right column
        val rx = px + 144
        val rw = pw - 144 - 12

        // Capes. A cape you have not unlocked is not in `unlocked` at all, so it is not
        // drawn: showing it greyed out put its name next to the words "code needed", which
        // gave away what to type and advertised something you cannot have.
        sectionLabel(g, "CAPES", unlocked.size + 1, rx, py + HEADER_H + 8, rw)
        val cardsY = py + CARDS_TOP

        /*
          The cards wrap. The row fit exactly four of them, which is None plus the three
          free capes, so unlocking one pushed the new card straight out past the edge of
          the panel - the layout broke at the exact moment it had something to show.
        */
        fun cardPos(slot: Int): Pair<Int, Int> =
            Pair(rx + (slot % perRow) * (cardW + cardGap), cardsY + (slot / perRow) * (cardH + cardGap))

        val (noneX, noneY) = cardPos(0)
        drawNoneCard(g, noneX, noneY, cardW, cardH, mouseX, mouseY, equippedIndex == -1) {
            cape.enabled = false
            RiverRuntime.saveConfig()
        }
        unlocked.forEachIndexed { slot, (i, style) ->
            val (cx, cy) = cardPos(slot + 1)
            drawCapeCard(g, cx, cy, cardW, cardH, style, RiverCape.LABELS[i], mouseX, mouseY, equippedIndex == i) {
                cape.setStyleIndex(i)
                cape.enabled = true
                RiverRuntime.saveConfig()
            }
        }

        val afterCards = cardsY + rowsOfCards * cardH + (rowsOfCards - 1) * cardGap
        drawRedeemRow(g, rx, afterCards + REDEEM_TOP, rw, mouseX, mouseY)

        // Badge
        val nametag = nametagModule()
        if (nametag != null) {
            val badgeY = afterCards + BADGE_TOP
            val badgeOn = nametag.enabled && nametag.showRiverBadge()
            drawToggleRow(g, rx, badgeY, rw, "River badge", badgeOn, mouseX, mouseY, "fx:badge") { on ->
                nametag.setShowRiverBadge(on)
                if (on) nametag.enabled = true
                RiverRuntime.saveConfig()
            }
        }
    }

    private companion object {
        /** The header row, the same height on every River screen. */
        const val HEADER_H = 34
        /** The header and the CAPES label sit above the cards. */
        const val CARDS_TOP = 58
        /** Redeem row, then its message, then the badge toggle - all below the cards. */
        const val REDEEM_TOP = 12
        const val BADGE_TOP = 52
        const val TOGGLE_HEIGHT = 18
        /** The cape texture on each card, and the empty well on the None card. */
        const val PREVIEW_W = 40
        const val PREVIEW_H = 64
        /** Everything under the cards, plus real padding at the bottom. */
        const val BELOW_CARDS = BADGE_TOP + TOGGLE_HEIGHT + 14
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
//? if >=1.21.9 {
            // Render the REAL player entity through the entity pipeline: correct depth
            // (cape sits behind the body), all render layers apply (River cape included),
            // and the display name carries the River badge from the mixin.
            val renderer = mcClient.entityRenderDispatcher.getRenderer(player)
            val state = renderer.createRenderState(player, 1f)
            if (state is LivingEntityRenderState) {
                state.bodyRot = 180f + spin
                // yRot/xRot are the head's rotation RELATIVE to the body here
                // (HumanoidModel does head.yRot = state.yRot directly), so zero
                // them to keep the head locked to the spinning body.
                state.yRot = 0f
                state.xRot = 0f
                state.pose = Pose.STANDING
//?} elif >=1.21.6 {
/*            // 1.21.6 through 1.21.8 have the same classic InventoryScreen helper as
            // 1.21.4, but it takes the destination rect (x1, y1, x2, y2) rather than an
            // anchor point, so the call is spelled out separately here.
            val tilt = Quaternionf().rotateX(Math.toRadians(rotX.toDouble()).toFloat())
            val pose = Quaternionf().rotateZ(Math.PI.toFloat())
                .mul(Quaternionf().rotateY(Math.toRadians(spin.toDouble()).toFloat()))
            val scale = h / 3.1f
            InventoryScreen.renderEntityInInventory(
                g, x + inset, y + inset, x + w - inset, y + h - inset,
                scale, Vector3f(0f, 0f, 0f), pose, tilt, player
            )

            // displayName already carries the badge from PlayerDisplayNameMixin, so it is
            // not added again here - doing both is what drew it twice. The bare block keeps
            // the brace after these version branches closing something on every version: on
            // 1.21.11 it closes the render-state check instead.
            val name: Component = player.displayName ?: Component.literal(player.name.string)
            run {
*///?} else {
/*            // 1.21.4 has no GuiGraphics.submitEntityRenderState (that deferred-submit path
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

            // displayName already carries the badge from PlayerDisplayNameMixin, so it is
            // not added again here - doing both is what drew it twice. The bare block keeps
            // the brace after these version branches closing something on every version: on
            // 1.21.11 it closes the render-state check instead.
            val name: Component = player.displayName ?: Component.literal(player.name.string)
            run {
*///?}
            }
//? if >=1.21.9 {
            state.lightCoords = 0xF000F0
            state.isDiscrete = false
            state.distanceToCameraSq = 0.0
            /*
              The name is drawn by hand below rather than hung off the entity.

              A floating nametag is scaled with the model it belongs to, and the model is
              scaled to fill the box. Unlocking a cape adds a row of cards, which makes
              the panel and the box taller, which made the name grow until it ran off the
              side. Drawn separately it is the same size whatever the box does.
            */
            state.nameTag = null

            // The name sits in the headroom at the top of the box, at a fixed size, with
            // the badge that the display name already carries.
            val label = player.displayName
            val labelWidth = font.width(label)
            g.drawString(
                font, label,
                x + ((w - labelWidth) / 2).coerceAtLeast(inset),
                y + inset + 2,
                -1, true
            )

            val tilt = Quaternionf().rotateX(Math.toRadians(-6.0).toFloat())
            val pose = Quaternionf().rotateZ(Math.PI.toFloat()).mul(tilt)
            // Scale leaves just enough headroom for the name above; the slightly lower
            // anchor keeps the model inside the preview rect.
            val scale = h / 3.1f
            g.submitEntityRenderState(
                state, scale, Vector3f(0f, player.bbHeight / 2f + 0.3f, 0f), pose, tilt,
                x + inset, y + inset, x + w - inset, y + h - inset
            )
//?} else {
/*            g.drawString(font, name, x + (w - font.width(name)) / 2, y + 8, -1, true)
*///?}
        } else {
//? if >=1.21.9 {
            // No world (opened from the main menu) - flat skin-widget path, body only,
            // with the styled name drawn as 2D text where the floating tag would be.
            val skin = skinLookup.get()
            if (skin != null) {
                val model = if (skin.model() == PlayerModelType.SLIM) slimModel else wideModel
                val scale = h * 0.84f / 2.125f
                g.submitSkinRenderState(
                    model, skin.body().texturePath(),
                    scale, rotX, spin, -1.0625f,
                    x + inset, y + 18, x + w - inset, y + h - inset
                )
            }
//?} else {
/*            // No world (opened from the main menu) - no live entity to render through the
            // helper above, so just show the styled name where the 3D preview would be.
*///?}
            val nametag = nametagModule()
            var name: Component = Component.literal(mcClient.user.name)
            if (nametag?.active == true && nametag.showRiverBadge()) {
                name = Component.empty().append(RiverBadgeState.badgeComponent()).append(Component.literal(" ")).append(name)
            }
//? if >=1.21.9 {
            g.drawString(font, name, x + (w - font.width(name)) / 2, y + 8, -1, true)
//?} else {
/*            g.drawString(font, name, x + (w - font.width(name)) / 2, y + h / 2 - 4, -1, true)
*///?}
        }
        g.drawString(font, "drag to spin", x + (w - font.width("drag to spin")) / 2, y + h - 12, ClientUi.alpha(ClientUi.DIM, 0.8f), false)
    }

    // ------------------------------------------------------------------ cards + rows

    private fun drawNoneCard(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int, selected: Boolean, onClick: () -> Unit) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        drawCardBase(g, x, y, w, h, hovered, selected)
        val pvX = x + (w - PREVIEW_W) / 2
        val pvY = y + 8
        g.fill(pvX, pvY, pvX + PREVIEW_W, pvY + PREVIEW_H, wellBg())
        ClientUi.drawRoundedBorder(g, pvX, pvY, PREVIEW_W, PREVIEW_H, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        RiverIcons.draw(g, "ban", pvX + (PREVIEW_W - 14) / 2, pvY + (PREVIEW_H - 14) / 2, 14, if (selected) ClientUi.ACCENT_B else ClientUi.DIM)
        drawCardFooter(g, x, y, w, h, "None", selected)
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
        val pvW = PREVIEW_W
        val pvH = PREVIEW_H
        val pvX = x + (w - pvW) / 2
        val pvY = y + 8
        // A darker well behind the texture, so a cape with see-through parts still reads.
        g.fill(pvX, pvY, pvX + pvW, pvY + pvH, wellBg())
//? if >=1.21.6 {
        g.riverBlit(tex, pvX, pvY, srcU, srcV, pvW, pvH, srcW, srcH, texW, texH)
//?} else {
/*        g.riverBlit(tex, pvX, pvY, srcU, srcV, pvW, pvH, srcW, srcH, texW, texH)
*///?}
        ClientUi.drawRoundedBorder(g, pvX - 1, pvY - 1, pvW + 2, pvH + 2, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        /*
          A locked cape is shown rather than hidden, so people know it exists and can ask
          the creator for the code. It is dimmed and cannot be equipped; clicking it points
          at the code box instead of silently doing nothing.
        */
        drawCardFooter(g, x, y, w, h, label, selected)
        hit(x, y, w, h, onClick)
    }

    /**
     * Code box for capes a creator gives out. Always present rather than appearing only
     * when something is locked: somebody handed a code needs somewhere to put it, and
     * hunting for a field that only exists once you already know what it unlocks is worse
     * than one quiet row that is always in the same place.
     */
    private fun drawRedeemRow(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val boxW = 120
        val boxH = 18
        val btnW = 62

        g.drawString(font, "Have a cape code?", x, y + 5, ClientUi.DIM, false)
        val boxX = x + font.width("Have a cape code?") + 8
        val boxHovered = mouseX in boxX..(boxX + boxW) && mouseY in y..(y + boxH)
        val boxAnim = ClientUi.hover("redeembox", boxHovered || redeemFocused)
        g.fill(boxX, y, boxX + boxW, y + boxH, wellBg())
        ClientUi.drawRoundedBorder(
            g, boxX, y, boxW, boxH, 0,
            if (redeemFocused) ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
            else ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.BORDER), boxAnim)
        )
        val shownCode = trim(redeemInput, boxW - 12)
        if (redeemInput.isEmpty()) {
            g.drawString(font, "Cape code", boxX + 6, y + 5, if (redeemFocused) ClientUi.alpha(ClientUi.DIM, 0.6f) else ClientUi.DIM, false)
        } else {
            g.drawString(font, shownCode, boxX + 6, y + 5, ClientUi.TEXT, false)
        }
        if (redeemFocused && System.currentTimeMillis() / 530 % 2 == 0L) {
            val caretX = boxX + 6 + (if (redeemInput.isEmpty()) 0 else font.width(shownCode)) + 1
            g.fill(caretX, y + 4, caretX + 1, y + 14, ClientUi.TEXT)
        }
        hit(boxX, y, boxW, boxH) { redeemFocused = true }

        val btnX = boxX + boxW + 6
        val btnHovered = mouseX in btnX..(btnX + btnW) && mouseY in y..(y + boxH)
        val label = if (redeeming) "..." else "Redeem"
        val ready = redeemInput.isNotEmpty() && !redeeming
        val btnAnim = ClientUi.hover("redeembtn", btnHovered)
        if (ready) {
            g.fill(btnX, y, btnX + btnW, y + boxH, ClientUi.mix(ClientUi.ACCENT_A, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.ACCENT_A), btnAnim))
            g.fill(btnX, y, btnX + btnW, y + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
        } else {
            g.fill(btnX, y, btnX + btnW, y + boxH, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.03f + 0.04f * btnAnim))
            ClientUi.drawRoundedBorder(g, btnX, y, btnW, boxH, 0, ClientUi.BORDER)
        }
        g.drawString(font, label, btnX + (btnW - font.width(label)) / 2, y + 5, if (ready) ClientUi.TEXT else ClientUi.DIM, false)
        hit(btnX, y, btnW, boxH) { submitRedeem() }

        if (redeemMessage.isNotEmpty()) {
            g.drawString(
                font, trim(redeemMessage, w), x, y + boxH + 5,
                if (redeemOk) ClientUi.POSITIVE else ClientUi.MUTED, false
            )
        }
    }

    private fun submitRedeem() {
        if (redeeming || redeemInput.isEmpty()) return
        redeeming = true
        redeemMessage = "Checking..."
        redeemOk = false
        CapeEntitlements.redeem(redeemInput) { ok, message ->
            // Called from the network thread, so the result is applied on the client thread.
            mcClient.execute {
                redeeming = false
                redeemOk = ok
                redeemMessage = message
                if (ok) {
                    redeemInput = ""
                    redeemFocused = false
                }
            }
        }
    }

    private fun drawCardBase(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, selected: Boolean) {
        val anim = ClientUi.hover("cosmetics:$x:$y", hovered)
        ClientUi.drawSectionCard(g, x, y, w, h, anim, selected)
        if (selected) {
            ClientUi.drawRoundedBorder(g, x, y, w, h, ClientUi.RADIUS_CARD, ClientUi.BORDER_STRONG)
            // A check in the corner says "this is the one you are wearing" without words
            // competing with the cape's name underneath.
            g.fill(x + w - 13, y + 1, x + w - 1, y + 13, ClientUi.ACCENT_A)
            RiverIcons.draw(g, "check", x + w - 11, y + 3, 8, ClientUi.TEXT)
        }
    }

    /** The cape's name under its preview, and a quieter second line saying whether it is on. */
    private fun drawCardFooter(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, label: String, selected: Boolean) {
        val shown = trim(label, w - 8)
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + h - 22, if (selected) ClientUi.TEXT else ClientUi.MUTED, selected)
        val state = if (selected) "Equipped" else "Wear"
        g.drawString(font, state, x + (w - font.width(state)) / 2, y + h - 11, if (selected) ClientUi.ACCENT_B else ClientUi.DIM, false)
    }

    /** A small caps label, its count, and a hairline out to the edge, as in the friends list. */
    private fun sectionLabel(g: GuiGraphics, rawLabel: String, count: Int, x: Int, y: Int, w: Int) {
        val label = RiverText.small(rawLabel)
        g.drawString(font, label, x, y + 2, ClientUi.DIM, false)
        val countX = x + font.width(label) + 4
        g.drawString(font, count.toString(), countX, y + 2, ClientUi.alpha(ClientUi.DIM, 0.7f), false)
        val lineX = countX + font.width(count.toString()) + 6
        if (lineX < x + w) g.fill(lineX, y + 6, x + w, y + 7, ClientUi.alpha(ClientUi.BORDER, 0.8f))
    }

    /** A text field's well: darker than what it sits on, and see-through on glass. */
    private fun wellBg(): Int = if (ClientUi.glassFrame) ClientUi.alpha(0xFF000000.toInt(), 0.38f)
        else ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.32f), ClientUi.PANEL)

    private fun drawToggleRow(
        g: GuiGraphics, x: Int, y: Int, w: Int, label: String,
        on: Boolean, mouseX: Int, mouseY: Int, id: String, onToggle: (Boolean) -> Unit
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 20)
        val anim = ClientUi.hover("row:$id", hovered)
        ClientUi.drawSectionCard(g, x, y, w, 20, anim, false)
        RiverIcons.draw(g, "tag", x + 7, y + 5, 10, if (on) ClientUi.ACCENT_B else ClientUi.DIM)
        g.drawString(font, trim(label, w - 70), x + 22, y + 6, if (on || hovered) ClientUi.TEXT else ClientUi.MUTED, false)
        ClientUi.drawMinimalToggle(g, x + w - 40, y + 3, on, hovered, id)
        hit(x, y, w, 20) { onToggle(!on) }
    }

    // ------------------------------------------------------------------ input

//? if >=1.21.9 {
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        val mx = event.x()
        val my = event.y()
        if (mx >= previewRect[0] && mx <= previewRect[2] && my >= previewRect[1] && my <= previewRect[3]) {
//?} else {
/*    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        if (mouseX >= previewRect[0] && mouseX <= previewRect[2] && mouseY >= previewRect[1] && mouseY <= previewRect[3]) {
*///?}
            draggingPreview = true
            return true
        }
        hits.asReversed().forEach { h ->
//? if >=1.21.9 {
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
//?} else {
/*            if (mouseX >= h.x1 && mouseX <= h.x2 && mouseY >= h.y1 && mouseY <= h.y2) {
*///?}
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

//? if >=1.21.9 {
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
//?} else {
/*    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
*///?}
        if (draggingPreview) {
            manualRotY += dragX.toFloat() * 2.5f
            rotX = (rotX - dragY.toFloat() * 1.5f).coerceIn(-50f, 20f)
            return true
        }
//? if >=1.21.9 {
        return super.mouseDragged(event, dragX, dragY)
//?} else {
/*        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
*///?}
    }

//? if >=1.21.9 {
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
//?} else {
/*    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
*///?}
        draggingPreview = false
//? if >=1.21.9 {
        return super.mouseReleased(event)
//?} else {
/*        return super.mouseReleased(mouseX, mouseY, button)
*///?}
    }

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
        val text = event.codepointAsString()
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val text = codePoint.toString()
*///?}
        if (redeemFocused) {
            // Codes are letters and digits, upper case, so a code copied in any casing works.
            val filtered = text.filter { it.isLetterOrDigit() }
            if (filtered.isNotEmpty() && redeemInput.length < 24) redeemInput += filtered.uppercase()
            return true
        }
//? if >=1.21.9 {
        return super.charTyped(event)
//?} else {
/*        return super.charTyped(codePoint, modifiers)
*///?}
    }

    override fun init() {
        super.init()
        // Refreshed when the wardrobe opens rather than on a timer: this is the only screen
        // that cares, and a cosmetic list does not need to be current to the second.
        CapeEntitlements.refresh()
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode
*///?}
        /*
          The code box takes typing first, otherwise every letter of a code is read as a
          keybind and Escape closes the screen mid-entry rather than leaving the field.
        */
        if (redeemFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> {
                    redeemFocused = false
                    return true
                }
                GLFW.GLFW_KEY_BACKSPACE -> {
                    redeemInput = redeemInput.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    submitRedeem()
                    return true
                }
            }
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

    /** Whether the screen this was opened from was drawn behind it this frame. */
    private var parentShown = false

    /** The screen this was opened from - the River menu, the title screen - behind it. */
//? if >=26.1 {
/*    override fun extractBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        parentShown = ClientUi.drawParentScreen(g, parent, width, height, partialTick)
        if (parentShown) return
//? if >=26.1 {
/*        super.extractBackground(g, mouseX, mouseY, partialTick)
*///?} else {
        super.renderBackground(g, mouseX, mouseY, partialTick)
//?}
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
