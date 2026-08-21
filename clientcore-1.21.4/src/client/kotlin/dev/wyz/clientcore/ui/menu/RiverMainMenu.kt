package dev.wyz.clientcore.ui.menu

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.input.ClientKeybinds
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.screen.RiverContentBrowserScreen
import dev.wyz.clientcore.ui.screen.RiverCosmeticsScreen
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean River Client main menu, drawn over the vanilla TitleScreen (which
 * TitleScreenMixin strips down): RIVER title, valley background + dark overlay,
 * a column of dark-transparent buttons with a blue hover fade, and the client name /
 * version in the bottom corners. Immediate-mode so it doesn't fight the many menu
 * mods that add their own widgets.
 *
 * Vanilla's entries come first, then Quit set slightly apart, then a row of icon
 * buttons for River's own two destinations (the River menu and Cosmetics) - the same
 * shape vanilla uses for its Language/Accessibility icons.
 */
object RiverMainMenu {

    private data class MenuButton(val label: String, val action: (TitleScreen) -> Unit)

    /** Column order. Quit is last, and River's icon row is drawn under it. */
    private val buttons = listOf(
        MenuButton("Singleplayer") { p -> Minecraft.getInstance().setScreen(SelectWorldScreen(p)) },
        MenuButton("Multiplayer") { p -> Minecraft.getInstance().setScreen(JoinMultiplayerScreen(p)) },
        MenuButton("Mods") { p -> openMods(p) },
        MenuButton("Options") { p -> Minecraft.getInstance().let { it.setScreen(OptionsScreen(p, it.options)) } },
        MenuButton("Quit Game") { _ -> Minecraft.getInstance().stop() }
    )

    /**
     * River's own destinations, as a centred icon row under Quit. [icon] is a
     * RiverIcons name, or null to draw the River logo texture. Order is left to right.
     */
    private data class IconButton(val tooltip: String, val icon: String?, val action: (TitleScreen) -> Unit)

    /** Flashback (moulberry's replay mod) replay browser - shown only if installed. */
    private const val FLASHBACK_SCREEN = "com.moulberry.flashback.screen.select_replay.SelectReplayScreen"

    /**
     * Whether the Flashback mod is actually loaded. Class.forName was unreliable here: in the
     * agent-injected runtime its classloader can resolve the Flashback class even when the mod
     * is not really loaded, so the icon never hid. Fabric's own mod list is the source of truth.
     * Evaluated fresh each time the row is built so it reflects the current instance.
     */
    private fun flashbackAvailable(): Boolean =
        runCatching { net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("flashback") }.getOrDefault(false)

    private val iconButtons: List<IconButton>
        get() = buildList {
            add(IconButton("River Settings", null) { _ -> Minecraft.getInstance().let { ClientKeybinds.openMenu(it) } })
            add(IconButton("Cosmetics", "shirt") { p -> Minecraft.getInstance().setScreen(RiverCosmeticsScreen(p)) })
            add(IconButton("Download content", "box") { p -> Minecraft.getInstance().setScreen(RiverContentBrowserScreen(p)) })
            // Only surface Flashback when the mod is actually loaded; it just opens the
            // mod's own replay browser.
            if (flashbackAvailable()) add(IconButton("Flashback", "camera") { p -> openFlashback(p) })
        }

    private fun openFlashback(parent: TitleScreen) {
        runCatching {
            val cls = Class.forName(FLASHBACK_SCREEN)
            val screen = cls.getConstructor(Screen::class.java).newInstance(parent) as Screen
            Minecraft.getInstance().setScreen(screen)
        }
    }

    private const val BTN_W = 212
    private const val BTN_H = 22
    private const val BTN_GAP = 5
    private const val QUIT_EXTRA_GAP = 10
    private const val ICON_BTN = 22

    /** Horizontal gap between the icon buttons. */
    private const val ICON_GAP = 6

    /** Vertical gap between Quit and the icon row under it. */
    private const val ICON_ROW_GAP = 10

    /** Deliberate offset from dead centre, so the column sits just below the middle. */
    private const val COLUMN_NUDGE = 10

    /** Minimum clearance under the title art, if centring would ride up into it. */
    private const val MIN_TITLE_GAP = 12

    /** Screen-space rects for the current frame, filled by [render], read by [mouseClicked]. */
    private val rects = IntArray(buttons.size * 4)
    private val iconRects = IntArray(iconButtons.size * 4)

    /** When the title screen was (re)opened; drives the entrance animation. */
    private var openedAt = System.currentTimeMillis()

    private fun easeOut(t: Float): Float {
        val c = 1f - t.coerceIn(0f, 1f)
        return 1f - c * c * c
    }

    // Splash lines: short, no em dashes, a mix of River tips and light humor.
    private val splashes = listOf(
        "Right Shift opens the menu!",
        "Try cinematic mode!",
        "Drag your HUD in the editor!",
        "Go with the flow!",
        "Press F3 less!",
        "Customize your theme!",
        "Waypoints included!",
        "Zoom with C!",
        "No cheats, just vibes!",
        "River runs deep!",
        "Now with real water!",
        "Blue looks good on you!",
        "Fully sending it!"
    )
    private var splash = splashes.first()

    /** Weighted so the friends shortcut actually gets discovered - see [pickSplash]. */
    private const val FRIENDS_TIP = "Left Shift + Tab opens friends!"
    private const val FRIENDS_TIP_CHANCE = 0.4

    /**
     * Called when the title screen (re)opens: re-rolls the splash and restarts the entrance.
     *
     * The friends shortcut is deliberately weighted rather than being one line among many:
     * as a plain list entry it would surface about 7% of the time, which is not enough for
     * anyone to learn a keybind that has no other signpost in game.
     */
    fun pickSplash() {
        splash = if (Math.random() < FRIENDS_TIP_CHANCE) FRIENDS_TIP else splashes.random()
        openedAt = System.currentTimeMillis()
    }

    // ---- textures (loaded via the classloader so agent injection works) ----

    private var titleTex: ResourceLocation? = null
    private var titleW = 0
    private var titleH = 0
    private var bgTex: ResourceLocation? = null
    private var bgW = 0
    private var bgH = 0
    private var logoTex: ResourceLocation? = null
    private var loaded = false

    private fun ensureTextures() {
        if (loaded) return
        loaded = true
        loadTexture("/assets/clientcore/textures/menu_title.png", "river_menu_title")?.let { (id, w, h) ->
            titleTex = id; titleW = w; titleH = h
        }
        loadTexture("/assets/clientcore/textures/menu_background.png", "river_menu_bg")?.let { (id, w, h) ->
            bgTex = id; bgW = w; bgH = h
        }
        loadTexture("/assets/clientcore/textures/watermark_logo.png", "river_menu_logo")?.let { (id, _, _) ->
            logoTex = id
        }
    }

    private fun loadTexture(path: String, name: String): Triple<ResourceLocation, Int, Int>? = runCatching {
        val stream = RiverMainMenu::class.java.getResourceAsStream(path) ?: return@runCatching null
        val image = stream.use { NativeImage.read(it) }
        val id = ResourceLocation.fromNamespaceAndPath("clientcore", name)
        Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
        Triple(id, image.width, image.height)
    }.getOrNull()

    // ---- rendering ----

    fun renderBackground(client: Minecraft, g: GuiGraphics) {
        ensureTextures()
        val w = client.window.guiScaledWidth
        val h = client.window.guiScaledHeight
        val bg = bgTex
        if (bg != null && bgW > 0 && bgH > 0) {
            // Slow Ken Burns drift: the art zooms and pans gently over ~50s. The source
            // rect is cover-fit to the screen aspect so the image is cropped, not stretched.
            val t = (System.currentTimeMillis() % 50000L) / 50000f * (Math.PI.toFloat() * 2f)
            val zoom = 1.10f + 0.045f * sin(t)
            var srcW = bgW / zoom
            var srcH = bgH / zoom
            val screenAspect = w.toFloat() / h.toFloat()
            if (srcW / srcH > screenAspect) srcW = srcH * screenAspect else srcH = srcW / screenAspect
            val maxDriftX = (bgW - srcW) / 2f
            val maxDriftY = (bgH - srcH) / 2f
            val u = maxDriftX + sin(t * 0.9f) * maxDriftX * 0.35f
            val v = maxDriftY + sin(t * 0.6f) * maxDriftY * 0.25f
            g.blit(RenderType::guiTextured, bg, 0, 0, u, v, w, h, srcW.roundToInt(), srcH.roundToInt(), bgW, bgH)
        } else {
            g.fillGradient(0, 0, w, h, 0xFF12161F.toInt(), 0xFF090B10.toInt())
        }
        // Vignette instead of a flat overlay: darker top and bottom bands keep the
        // title, buttons, and corner labels readable while the art stays vivid.
        g.fill(0, 0, w, h, 0x38000000)
        g.fillGradient(0, 0, w, (h * 0.32f).roundToInt(), 0x77000000, 0x00000000)
        g.fillGradient(0, (h * 0.55f).roundToInt(), w, h, 0x00000000, 0x90000000.toInt())
    }

    fun render(screen: TitleScreen, client: Minecraft, g: GuiGraphics, mouseX: Int, mouseY: Int) {
        ensureTextures()
        ClientUi.beginFrame()
        val sw = client.window.guiScaledWidth
        val sh = client.window.guiScaledHeight
        val font = client.font
        val elapsed = (System.currentTimeMillis() - openedAt).coerceAtLeast(0L)

        // Title slides down and fades in. Its resting bottom edge anchors the column
        // below, so it is computed from the settled position, not the animated one -
        // otherwise the buttons would slide around during the entrance.
        val titleRestY = (sh * 0.11f).roundToInt()
        var titleBottom = titleRestY
        titleTex?.let { tex ->
            val tIn = easeOut((elapsed - 60L) / 450f)
            val drawW = (sw * 0.26f).roundToInt().coerceIn(150, 260)
            val drawH = (drawW * titleH.toFloat() / titleW.toFloat()).roundToInt()
            val tx = (sw - drawW) / 2
            val ty = titleRestY - ((1f - tIn) * 14f).roundToInt()
            titleBottom = titleRestY + drawH
            val tint = ((tIn * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            g.blit(RenderType::guiTextured, tex, tx, ty, 0f, 0f, drawW, drawH, titleW, titleH, titleW, titleH, tint)
            if (tIn > 0.95f) drawSplash(g, font, tx + drawW - 8, ty + drawH - 6)
        }

        // Column layout, top to bottom: the vanilla entries, a gap, Quit, then River's
        // icon row. Centred on the screen (the old version centred it and THEN pushed it
        // down a further 8% of screen height, which is what opened the big gap under the
        // title), plus a small deliberate nudge.
        val totalH = buttons.size * BTN_H + (buttons.size - 1) * BTN_GAP +
            QUIT_EXTRA_GAP + ICON_ROW_GAP + ICON_BTN
        // Both clamps only bite on short windows / large GUI scale: the floor keeps the
        // column out of the title art, the ceiling keeps the icon row from running over
        // the corner labels and off the bottom. The floor yields to the ceiling when the
        // window is too short to honour both.
        val ceilingY = (sh - totalH - 6).coerceAtLeast(4)
        val floorY = (titleBottom + MIN_TITLE_GAP).coerceAtMost(ceilingY)
        val startY = ((sh - totalH) / 2 + COLUMN_NUDGE).coerceIn(floorY, ceilingY)
        val x = (sw - BTN_W) / 2
        buttons.forEachIndexed { i, button ->
            val isQuit = i == buttons.size - 1
            val bIn = easeOut((elapsed - 140L - i * 45L) / 380f)
            val y = startY + i * (BTN_H + BTN_GAP) + (if (isQuit) QUIT_EXTRA_GAP else 0) +
                ((1f - bIn) * 8f).roundToInt()
            rects[i * 4] = x; rects[i * 4 + 1] = y; rects[i * 4 + 2] = BTN_W; rects[i * 4 + 3] = BTN_H
            val hovered = mouseX in x..(x + BTN_W) && mouseY in y..(y + BTN_H)
            drawButton(g, font, button.label, x, y, hovered, bIn, isQuit)
        }

        // River's own destinations: a centred icon row under Quit.
        val iconRowY = startY + (buttons.size - 1) * (BTN_H + BTN_GAP) + QUIT_EXTRA_GAP +
            BTN_H + ICON_ROW_GAP
        val iconRowW = iconButtons.size * ICON_BTN + (iconButtons.size - 1) * ICON_GAP
        val iconRowX = (sw - iconRowW) / 2
        iconButtons.forEachIndexed { i, button ->
            val bIn = easeOut((elapsed - 140L - buttons.size * 45L) / 380f)
            val bx = iconRowX + i * (ICON_BTN + ICON_GAP)
            val by = iconRowY + ((1f - bIn) * 8f).roundToInt()
            iconRects[i * 4] = bx; iconRects[i * 4 + 1] = by
            iconRects[i * 4 + 2] = ICON_BTN; iconRects[i * 4 + 3] = ICON_BTN
            val hovered = mouseX in bx..(bx + ICON_BTN) && mouseY in by..(by + ICON_BTN)
            drawIconButton(g, font, button, bx, by, hovered, bIn)
        }

        // Corner labels: accent tick + dimmed text.
        ClientUi.fillRounded(g, 6, sh - 13, 2, 9, 1, ClientUi.ACCENT_B)
        g.drawString(font, "River Client", 12, sh - 12, 0xE6C7CDDC.toInt(), true)
        val version = SharedConstants.getCurrentVersion().name
        g.drawString(font, version, sw - 6 - font.width(version), sh - 12, 0xB3B9C0D2.toInt(), true)
    }

    /** Bouncing yellow splash, angled off the corner of the title (vanilla-style). */
    private fun drawSplash(g: GuiGraphics, font: net.minecraft.client.gui.Font, anchorX: Int, anchorY: Int) {
        val t = (System.currentTimeMillis() % 1000L) / 1000f
        val pulse = 1.25f - abs(sin(t * (Math.PI.toFloat() * 2f))) * 0.1f
        val pose = g.pose()
        pose.pushPose()
        pose.translate(anchorX.toFloat(), anchorY.toFloat(), 0f)
        pose.mulPose(com.mojang.math.Axis.ZP.rotation(-0.3491f)) // ~ -20 degrees
        pose.scale(pulse, pulse, 1f)
        g.drawString(font, splash, -font.width(splash) / 2, -font.lineHeight / 2, 0xFFFFFF00.toInt(), true)
        pose.popPose()
    }

    private fun drawButton(
        g: GuiGraphics, font: net.minecraft.client.gui.Font, label: String,
        x: Int, y: Int, hovered: Boolean, fadeIn: Float, isQuit: Boolean
    ) {
        if (fadeIn <= 0.02f) return
        val anim = ClientUi.hover("mainmenu:$label", hovered)
        // The button grows a couple of pixels outward on hover.
        val grow = (anim * 3f).roundToInt()
        val bx = x - grow
        val bw = BTN_W + grow * 2

        // Dark transparent fill, lifting slightly on hover.
        val bg = ClientUi.mix(0xB0090C12.toInt(), 0xC0141A26.toInt(), anim)
        ClientUi.fillRounded(g, bx, y, bw, BTN_H, 4, ClientUi.alpha(bg, fadeIn))
        // A soft accent wash creeps in under the label on hover (red for Quit).
        if (anim > 0.01f) {
            val washA = if (isQuit) 0xFF7A2430.toInt() else ClientUi.ACCENT_A
            val washB = if (isQuit) 0xFFA33A44.toInt() else ClientUi.ACCENT_B
            ClientUi.fillRoundedGradient(g, bx, y, bw, BTN_H, 4, ClientUi.alpha(washA, anim * 0.28f), ClientUi.alpha(washB, anim * 0.28f))
            val borderColor = if (isQuit) 0xFFE06868.toInt() else ClientUi.ACCENT_B
            ClientUi.drawRoundedBorder(g, bx, y, bw, BTN_H, 4, ClientUi.alpha(borderColor, anim * fadeIn))
        } else {
            ClientUi.drawRoundedBorder(g, bx, y, bw, BTN_H, 4, ClientUi.alpha(0x40FFFFFF, fadeIn))
        }
        val hoverText = if (isQuit) 0xFFFFD9D9.toInt() else 0xFFFFFFFF.toInt()
        val textColor = ClientUi.alpha(ClientUi.mix(0xFFD7DCE6.toInt(), hoverText, anim), fadeIn.coerceAtLeast(0.1f))
        g.drawString(font, label, x + (BTN_W - font.width(label)) / 2, y + (BTN_H - font.lineHeight) / 2 + 1, textColor, true)
    }

    private fun drawIconButton(
        g: GuiGraphics, font: net.minecraft.client.gui.Font, button: IconButton,
        x: Int, y: Int, hovered: Boolean, fadeIn: Float
    ) {
        if (fadeIn <= 0.02f) return
        val anim = ClientUi.hover("mainmenu:icon:${button.tooltip}", hovered)
        val bg = ClientUi.mix(0xB0090C12.toInt(), 0xC0141A26.toInt(), anim)
        ClientUi.fillRounded(g, x, y, ICON_BTN, ICON_BTN, 4, ClientUi.alpha(bg, fadeIn))
        val border = if (anim > 0.01f) ClientUi.alpha(ClientUi.ACCENT_B, anim * fadeIn) else ClientUi.alpha(0x40FFFFFF, fadeIn)
        ClientUi.drawRoundedBorder(g, x, y, ICON_BTN, ICON_BTN, 4, border)

        val size = 12
        val ix = x + (ICON_BTN - size) / 2
        val iy = y + (ICON_BTN - size) / 2
        val logo = logoTex
        if (button.icon != null) {
            val tint = ClientUi.alpha(ClientUi.mix(0xFFD7DCE6.toInt(), 0xFFFFFFFF.toInt(), anim), fadeIn)
            RiverIcons.draw(g, button.icon, ix, iy, size, tint)
        } else if (logo != null) {
            // The logo is full-colour art, so it only gets an alpha fade, not a tint.
            val tint = ((fadeIn * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            g.blit(RenderType::guiTextured, logo, ix, iy, 0f, 0f, size, size, 500, 500, 500, 500, tint)
        } else {
            RiverIcons.draw(g, "sparkle", ix, iy, size, ClientUi.alpha(0xFFD7DCE6.toInt(), fadeIn))
        }

        // Tooltip under the button, centred on it and clamped to the screen.
        if (anim > 0.5f) {
            val tw = font.width(button.tooltip)
            val tx = (x + (ICON_BTN - tw) / 2).coerceIn(4, (g.guiWidth() - tw - 4).coerceAtLeast(4))
            val ty = y + ICON_BTN + 4
            ClientUi.fillRounded(g, tx - 4, ty - 3, tw + 8, font.lineHeight + 5, 3, ClientUi.alpha(0xE0090C12.toInt(), anim))
            g.drawString(font, button.tooltip, tx, ty, ClientUi.alpha(0xFFFFFFFF.toInt(), anim), true)
        }
    }

    fun mouseClicked(screen: TitleScreen, mouseX: Double, mouseY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        for (i in buttons.indices) {
            val bx = rects[i * 4]; val by = rects[i * 4 + 1]; val bw = rects[i * 4 + 2]; val bh = rects[i * 4 + 3]
            if (mx in bx..(bx + bw) && my in by..(by + bh)) {
                clickSound()
                buttons[i].action(screen)
                return true
            }
        }
        for (i in iconButtons.indices) {
            val bx = iconRects[i * 4]; val by = iconRects[i * 4 + 1]
            val bw = iconRects[i * 4 + 2]; val bh = iconRects[i * 4 + 3]
            if (mx in bx..(bx + bw) && my in by..(by + bh)) {
                clickSound()
                iconButtons[i].action(screen)
                return true
            }
        }
        return false
    }

    private fun clickSound() {
        Minecraft.getInstance().soundManager.play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f
            )
        )
    }

    private fun openMods(parent: Screen) {
        runCatching {
            val cls = Class.forName("com.terraformersmc.modmenu.gui.ModsScreen")
            val screen = cls.getConstructor(Screen::class.java).newInstance(parent) as Screen
            Minecraft.getInstance().setScreen(screen)
        }.onFailure {
            // ModMenu not present: fall back to the River menu.
            Minecraft.getInstance().let { ClientKeybinds.openMenu(it) }
        }
    }
}
