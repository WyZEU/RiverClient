package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.input.PauseMenuSizing
import dev.wyz.clientcore.compat.riverBlit
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.menu.RiverMainMenu
import dev.wyz.clientcore.ui.pause.PauseCamera
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
//?}
//? if >=26.3 {
/*import dev.wyz.clientcore.compat.GLFW
*///?} else {
import org.lwjgl.glfw.GLFW
//?}
import java.lang.ref.WeakReference

/**
 * River's pause menu: your own player in third person on the left (PauseCamera), and on the
 * right where you are, how it is going, and what you can do.
 *
 * It does not reimplement vanilla's menu. The vanilla PauseScreen is still made and set up
 * out of sight, and every action here presses that screen's own button: Disconnect, Options,
 * Open to LAN and the rest behave exactly as vanilla does in every Minecraft version River
 * supports, come with the game's own translated labels, and buttons other mods add to the pause
 * menu (Mod Menu, ReplayMod...) show up here too. Screens opened that way come back to that
 * vanilla screen when closed, which River swaps for this one again (see [takeOver]).
 * "More" shows the original menu, including mods that draw their own controls there.
 */
class RiverPauseScreen(private val vanilla: PauseScreen) : Screen(Component.translatable("menu.game")), RiverScreen {

    companion object {
        /** The vanilla menu shown on purpose ("More"), left alone instead of replaced. */
        private var shownAsIs: WeakReference<PauseScreen>? = null
        /** True while this screen sets the vanilla one up to read its buttons. */
        private var harvesting = false
        private var swapping: WeakReference<PauseScreen>? = null
        private val proxyLabel = Regex("<[a-z0-9]+(?:[_:.-][a-z0-9]+)+>")

        private val fancyMenu: Boolean by lazy { FabricLoader.getInstance().isModLoaded("fancymenu") }

        /** Set by the dev screenshots for tests that need vanilla's own pause menu. */
        @JvmStatic var vanillaForTest = false

        fun enabled(): Boolean = !fancyMenu && !vanillaForTest && RiverRuntime.configOrNull()?.riverPauseMenu != false

        /**
         * Called at the start of every PauseScreen.init. Returns true when River takes this
         * pause menu over: vanilla's own setup is skipped and River's screen replaces it as
         * soon as the game is done opening it.
         */
        @JvmStatic
        fun takeOver(screen: PauseScreen): Boolean {
            if (harvesting || !enabled() || !screen.showsPauseMenu()) return false
            if (shownAsIs?.get() === screen) return false
            val client = Minecraft.getInstance()
            if (client.level == null || client.player == null) return false
            swapping = WeakReference(screen)
            val swap = Runnable {
                if (client.screen === screen) client.setScreen(RiverPauseScreen(screen))
                if (swapping?.get() === screen) swapping = null
            }
            // Queued, not run now: this is inside the game's own setScreen.
//? if >=1.21.2 {
            client.schedule(swap)
//?} else {
/*            client.tell(swap)
*///?}
            return true
        }

        /** True for a vanilla pause menu about to be replaced: it draws nothing in the meantime. */
        @JvmStatic
        fun isSwapping(screen: Any): Boolean = swapping?.get() === screen

    }

    /** One of vanilla's pause menu buttons, as this menu shows it. */
    private class Action(val button: AbstractButton, val label: String)

    private var actions: List<Action> = emptyList()
    private var disconnect: AbstractButton? = null
    private val hits = ArrayList<Hit>()
    /** A click target; [sound] is false for vanilla's buttons, which play their own. */
    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val sound: Boolean = true, val onClick: () -> Unit)

    private var openedAt = 0L
    private var columnScale = 1f

    override fun init() {
        super.init()
        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        val client = minecraft ?: return
        PauseCamera.begin(client)
        harvest(client)
    }

    /** Sets the vanilla menu up at this size and reads its buttons. */
    private fun harvest(client: Minecraft) {
        harvesting = true
        try {
//? if >=1.21.11 {
            vanilla.init(width, height)
//?} else {
/*            vanilla.init(client, width, height)
*///?}
        } catch (_: Throwable) {
        } finally {
            harvesting = false
        }
        val list = ArrayList<Action>()
        var leave: AbstractButton? = null
        for (child in vanilla.children()) {
            val button = child as? AbstractButton ?: continue
            if (!button.visible) continue
            val key = (button.message.contents as? TranslatableContents)?.key ?: ""
            val label = button.message.string.removeSuffix("...").removeSuffix("…").trim()
            when {
                key == "menu.returnToGame" -> {}
                key == "menu.disconnect" || key == "menu.returnToMenu" -> leave = button
                // River's own button: this menu already has River's places along the top.
                label == "River Client" -> {}
                // Feedback and bug reports live under More, as they do on most menus.
                key == "menu.feedback" || key == "menu.sendFeedback" || key == "menu.reportBugs" -> {}
                // Proxy buttons only acquire their real presentation and action on the original screen.
                label.isBlank() || label == key || proxyLabel.matches(label) -> {}
                else -> list += Action(button, label)
            }
        }
        actions = list
        disconnect = leave
    }

    /** Clicks the original widget, including any mouse handler supplied by another mod. */
    private fun press(button: AbstractButton) {
        if (!button.active || !button.visible) return
        val x = button.x + button.width / 2.0
        val y = button.y + button.height / 2.0
//? if >=1.21.9 {
        button.mouseClicked(MouseButtonEvent(x, y, MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false)
//?} else {
/*        button.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT)
*///?}
    }

    private fun click() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
    }

    private fun backToGame() {
        val client = minecraft ?: return
        client.setScreen(null)
        PauseCamera.end(client)
    }

    private fun showVanillaMenu() {
        shownAsIs = WeakReference(vanilla)
        minecraft?.setScreen(vanilla)
    }

    // ------------------------------------------------------------------ drawing

    /*
      River's own look: the title screen's. The RIVER art with its splash, the same buttons
      (Back to Game in the accent, pairs side by side, the way out set apart in red) and the
      same row of square icon buttons, here down the left of the screen, with your player
      turning slowly on the right (PauseCamera).
    */

    override fun capsGuiScale(): Boolean = false

//? if >=26.1 {
/*    override fun extractBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        // No blur and no dimming: the world, and you in it, is the background.
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        ClientUi.beginFrame()
        ClientUi.menuFrame = false
        hits.clear()
        val client = minecraft ?: return
        val font = client.font
        val elapsed = (System.currentTimeMillis() - openedAt).coerceAtLeast(0L)
        // Lay the column out first so it can be fitted to the window, then draw it.
        val rows = buttonRows()
        val buttonsH = rows.size * RiverMainMenu.BTN_H + (rows.size - 1) * RiverMainMenu.BTN_GAP
        val tailH = (if (disconnect != null) RiverMainMenu.QUIT_EXTRA_GAP + RiverMainMenu.BTN_H else 0) +
            RiverMainMenu.ICON_ROW_GAP + RiverMainMenu.ICON_BTN
        val layout = PauseMenuSizing.layout(width, height, client.window.guiScale.toDouble(), buttonsH + tailH + 34)
        columnScale = layout.scale
        val columnWidth = layout.width
        val columnHeight = layout.height
        val columnMouseX = (mouseX / columnScale).toInt()
        val columnMouseY = (mouseY / columnScale).toInt()
        val x = maxOf(layout.padding, (columnWidth * 0.07f).toInt())
        val w = minOf(RiverMainMenu.BTN_W, columnWidth - x * 2)
        drawShade(g, ((x + w) * columnScale).toInt(), ease(elapsed / 260f))
//? if >=1.21.6 {
        g.pose().pushMatrix()
        g.pose().scale(columnScale, columnScale)
//?} else {
/*        g.pose().pushPose()
        g.pose().scale(columnScale, columnScale, 1f)
*///?}
        val art = RiverMainMenu.titleArt()
        val spare = columnHeight - layout.padding * 2 - 18 - (buttonsH + tailH + 34)
        val artW = if (art == null) 0 else minOf((w * 0.8f).toInt(), ((spare - 8) * art.second / art.third.coerceAtLeast(1)).coerceAtLeast(0))
        val artH = if (art == null || artW < 60) 0 else artW * art.third / art.second
        val total = (if (artH > 0) artH + 8 else 0) + 30 + buttonsH + tailH
        var y = maxOf(layout.padding, (columnHeight - total - 18) / 2)

        // The title art and its splash, as on the title screen.
        if (art != null && artH > 0) {
            val tIn = ease((elapsed - 40L) / 420f)
            val ty = y - ((1f - tIn) * 10f).toInt()
            val tint = ((tIn * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            g.riverBlit(art.first, x, ty, 0f, 0f, artW, artH, art.second, art.third, art.second, art.third, tint)
            // Higher up the art's side than on the title screen: here a line of text sits under the art.
            if (tIn > 0.95f) RiverMainMenu.drawSplash(g, font, x + artW + 4, ty + artH * 3 / 5, splash)
            y += artH + 8
        }

        // Where you are, in two quiet lines.
        val place = place(client)
        val infoIn = ease((elapsed - 90L) / 320f)
        if (infoIn > 0.02f) {
            val title = trim(font, place.title, w - font.width("  ·  ${place.kind}"))
            g.drawString(font, title, x, y, ClientUi.alpha(0xFFFFFFFF.toInt(), infoIn), true)
            g.drawString(font, "  ·  ${place.kind}", x + font.width(title), y, ClientUi.alpha(0xFFB9C0D2.toInt(), infoIn), true)
            val dot = if (place.paused) ClientUi.POSITIVE else ClientUi.WARNING
            g.fill(x + 1, y + 13, x + 5, y + 17, ClientUi.alpha(dot, infoIn))
            val status = listOf(if (place.paused) "Paused" else "Still running", place.detail).filter { it.isNotEmpty() }.joinToString("  ·  ")
            g.drawString(font, trim(font, status, w - 9), x + 9, y + 11, ClientUi.alpha(0xFFB9C0D2.toInt(), infoIn), true)
        }
        y += 30

        // The buttons: rows of one or two, in the title screen's style.
        var index = 0
        for (row in rows) {
            val cellW = (w - (row.size - 1) * RiverMainMenu.BTN_GAP) / row.size
            row.forEachIndexed { slot, entry ->
                val bIn = ease((elapsed - 120L - index * 35L) / 340f)
                val bx = x + slot * (cellW + RiverMainMenu.BTN_GAP)
                val by = y + ((1f - bIn) * 8f).toInt()
                val enabled = entry.button?.active != false
                val hovered = enabled && columnMouseX in bx..(bx + cellW) && columnMouseY in by..(by + RiverMainMenu.BTN_H)
                RiverMainMenu.drawButton(g, font, entry.label, bx, by, cellW, hovered, bIn * (if (enabled) 1f else 0.45f), isQuit = false, primary = entry.primary)
                if (enabled) hits += Hit(bx, by, bx + cellW, by + RiverMainMenu.BTN_H, entry.button == null, entry.onClick)
                index++
            }
            y += RiverMainMenu.BTN_H + RiverMainMenu.BTN_GAP
        }
        y -= RiverMainMenu.BTN_GAP

        // The way out, set apart and red on hover, as Quit is on the title screen.
        val leave = disconnect
        if (leave != null) {
            y += RiverMainMenu.QUIT_EXTRA_GAP
            val bIn = ease((elapsed - 120L - index * 35L) / 340f)
            val by = y + ((1f - bIn) * 8f).toInt()
            val hovered = leave.active && columnMouseX in x..(x + w) && columnMouseY in by..(by + RiverMainMenu.BTN_H)
            RiverMainMenu.drawButton(g, font, leave.message.string, x, by, w, hovered, bIn, isQuit = true, primary = false)
            if (leave.active) hits += Hit(x, by, x + w, by + RiverMainMenu.BTN_H, sound = false) { press(leave) }
            y += RiverMainMenu.BTN_H
            index++
        }

        // River's own places, as the title screen's icon row: the River menu, Wardrobe, Friends, and More.
        y += RiverMainMenu.ICON_ROW_GAP
        val icons = listOf<Triple<String, String?, () -> Unit>>(
            Triple("River Client", null) { client.setScreen(RiverMenuScreen(this)) },
            Triple("Wardrobe", "shirt") { client.setScreen(RiverCosmeticsScreen(this)) },
            Triple("Friends", "users") { client.setScreen(RiverFriendsScreen(this)) },
            Triple("Music Jam", "music") { client.setScreen(RiverMusicJamScreen(this)) },
            Triple("Servers", "server") { client.setScreen(RiverServerBrowserScreen(this)) },
            Triple("More options", "more") { showVanillaMenu() }
        )
        val iconIn = ease((elapsed - 120L - index * 35L) / 340f)
        icons.forEachIndexed { i, (tooltip, icon, open) ->
            val bx = x + i * (RiverMainMenu.ICON_BTN + RiverMainMenu.ICON_GAP)
            val by = y + ((1f - iconIn) * 8f).toInt()
            val hovered = columnMouseX in bx..(bx + RiverMainMenu.ICON_BTN) && columnMouseY in by..(by + RiverMainMenu.ICON_BTN)
            RiverMainMenu.drawIconButton(g, font, tooltip, icon, bx, by, hovered, iconIn)
            hits += Hit(bx, by, bx + RiverMainMenu.ICON_BTN, by + RiverMainMenu.ICON_BTN, onClick = open)
        }
//? if >=1.21.6 {
        g.pose().popMatrix()
//?} else {
/*        g.pose().popPose()
*///?}

        // Corner labels, as on the title screen: River on the left, the frame rate on the right.
        ClientUi.fillRounded(g, 6, height - 13, 2, 9, 1, ClientUi.ACCENT_B)
        g.drawString(font, "River Client", 12, height - 12, 0xE6C7CDDC.toInt(), true)
        val fps = "${client.fps} FPS"
        g.drawString(font, fps, width - 6 - font.width(fps), height - 12, 0xB3B9C0D2.toInt(), true)

        // Your music, where you put it on the HUD, with its controls (this screen draws instead
        // of Screen.render, so River's usual overlay hook does not reach it).
        dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.NowPlayingModule>("now_playing")?.renderMenu(client, g)
    }

    /** One button in the column: vanilla's (pressed as vanilla would) or River's own. */
    private class Entry(val label: String, val primary: Boolean, val button: AbstractButton?, val onClick: () -> Unit)

    /** Back to Game on its own, then vanilla's actions two to a row. */
    private fun buttonRows(): List<List<Entry>> {
        val rows = ArrayList<List<Entry>>()
        rows += listOf(Entry(Component.translatable("menu.returnToGame").string, true, null) { backToGame() })
        actions.map { action -> Entry(action.label, false, action.button) { press(action.button) } }
            .chunked(2).forEach { rows += it }
        return rows
    }

    private val splash: String = RiverMainMenu.randomSplash()

    private fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    /** Darkens the left side behind the column, fading out toward your player; the rest stays clear. */
    private fun drawShade(g: GuiGraphics, columnRight: Int, appear: Float) {
        if (appear < 0.02f) return
        val end = minOf(width, columnRight + 110)
        var x0 = 0
        while (x0 < end) {
            val p = 1f - x0.toFloat() / end
            val eased = p * p * (3f - 2f * p)
            g.fill(x0, 0, minOf(end, x0 + 2), height, shade(0.66f * eased * appear))
            x0 += 2
        }
        g.fillGradient(0, height - 48, width, height, shade(0f), shade(0.45f * appear))
    }

    private fun shade(alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0x07090D

    private fun trim(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text
        var out = text
        while (out.isNotEmpty() && font.width("$out...") > maxWidth) out = out.dropLast(1)
        return "$out..."
    }

    // ------------------------------------------------------------------ where you are

    private class Place(val kind: String, val title: String, val detail: String, val paused: Boolean)

    private fun place(client: Minecraft): Place {
        val local = client.singleplayerServer
        val mode = client.gameMode?.playerMode?.shortDisplayName?.string ?: ""
        if (local != null) {
            val title = runCatching { local.worldData.levelName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Singleplayer"
            return if (local.isPublished) {
                val players = client.connection?.onlinePlayers?.size ?: 1
                Place("LAN world", title, listOf(mode, "$players online").filter { it.isNotEmpty() }.joinToString("  ·  "), false)
            } else {
                Place("Singleplayer", title, mode, true)
            }
        }
        val server = client.currentServer
        val players = client.connection?.onlinePlayers?.size ?: 0
        val ping = client.player?.let { client.connection?.getPlayerInfo(it.uuid)?.latency } ?: -1
        val detail = listOfNotNull(
            if (players > 0) "$players online" else null,
            if (ping >= 0) "$ping ms" else null
        ).joinToString("  ·  ")
        val realm = server != null && runCatching { server.isRealm }.getOrDefault(false)
        val title = server?.name?.takeIf { it.isNotBlank() } ?: server?.ip ?: "Multiplayer"
        return Place(if (realm) "Realms" else "Multiplayer", title, detail, false)
    }


    // ------------------------------------------------------------------ input

//? if >=1.21.9 {
    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        val mx = event.x() / columnScale
        val my = event.y() / columnScale
//?} else {
/*    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        val mx = mouseX / columnScale
        val my = mouseY / columnScale
*///?}
        for (h in hits.asReversed()) {
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                if (h.sound) click()
                h.onClick()
                return true
            }
        }
        return true
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val keyCode = event.key()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
*///?}
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            backToGame()
            return true
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

    override fun onClose() = backToGame()
}
