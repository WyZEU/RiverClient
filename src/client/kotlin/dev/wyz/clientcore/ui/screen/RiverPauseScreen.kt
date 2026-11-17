package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.compat.riverBlit
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.NowPlayingModule
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.Glass
import dev.wyz.clientcore.ui.PlayerHeads
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.RiverShapes
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
 * "More" shows the vanilla menu itself, untouched.
 */
class RiverPauseScreen(private val vanilla: PauseScreen) : Screen(Component.translatable("menu.game")), RiverScreen {

    companion object {
        /** The vanilla menu shown on purpose ("More"), left alone instead of replaced. */
        private var shownAsIs: WeakReference<PauseScreen>? = null
        /** True while this screen sets the vanilla one up to read its buttons. */
        private var harvesting = false
        private var swapping: WeakReference<PauseScreen>? = null

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

        private var logo: Triple<McId, Int, Int>? = null
        private var logoTried = false
        private fun logo(): Triple<McId, Int, Int>? {
            if (!logoTried) {
                logoTried = true
                logo = RiverMainMenu.loadTexture("/assets/clientcore/textures/watermark_logo.png", "river_pause_logo")
            }
            return logo
        }

        private const val COLUMN = 236
        private const val ROW = 22
    }

    /** One of vanilla's pause menu buttons, as this menu shows it. */
    private class Action(val button: AbstractButton, val key: String, val label: String, val icon: String)

    private var actions: List<Action> = emptyList()
    private var disconnect: AbstractButton? = null
    private var hasMore = false
    private val hits = ArrayList<Hit>()
    /** A click target; [sound] is false for vanilla's buttons, which play their own. */
    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val sound: Boolean = true, val onClick: () -> Unit)

    private var openedAt = 0L

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
                key == "menu.options" -> list += Action(button, key, label, "gear")
                key == "gui.advancements" -> list += Action(button, key, label, "star")
                key == "gui.stats" -> list += Action(button, key, label, "list")
                key == "menu.shareToLan" -> list += Action(button, key, label, "wifi")
                key == "menu.server_links" -> list += Action(button, key, label, "flag")
                key == "options.worldOptions.button" -> list += Action(button, key, label, "wrench")
                key.startsWith("menu.custom_options") -> list += Action(button, key, label, "tool")
                key == "menu.playerReporting" -> list += Action(button, key, label, "shield")
                // Feedback and bug reports live under More, as they do on most menus.
                key == "menu.feedback" || key == "menu.sendFeedback" || key == "menu.reportBugs" -> {}
                // Anything else is another mod's button: shown here, a few at most (the rest under More).
                else -> if (list.count { it.key == "" } < 3) list += Action(button, "", label, "box")
            }
        }
        actions = list
        disconnect = leave
        hasMore = true
    }

    /** Presses one of vanilla's buttons the way Enter would: its own click sound, its own action. */
    private fun press(button: AbstractButton) {
        if (!button.active) return
//? if >=1.21.9 {
        button.keyPressed(KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0))
//?} else {
/*        button.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0)
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
        hits.clear()
        val client = minecraft ?: return
        val t = ((System.currentTimeMillis() - openedAt) / 220f).coerceIn(0f, 1f)
        val appear = 1f - (1f - t) * (1f - t) * (1f - t)

        drawShade(g, appear)
        if (appear < 0.04f) return
        drawTopBar(g, client, mouseX, mouseY, appear)
        drawColumn(g, client, mouseX, mouseY, appear)
        drawChips(g, client, mouseX, mouseY, appear)
    }

    private fun a(color: Int, appear: Float) = ClientUi.alpha(color, appear)

    /** Darkens the right side for the menu and the top and bottom edges for the bars; the rest stays clear. */
    private fun drawShade(g: GuiGraphics, appear: Float) {
        // Fully dark from just left of the menu to the edge, fading out toward the middle in
        // two-pixel steps (fine enough that no band shows, at about a hundred fills).
        val full = width - 44 - COLUMN - 28
        val start = minOf((width * 0.36f).toInt(), full - 60)
        var x0 = start
        while (x0 < full) {
            val p = (x0 - start + 1f) / (full - start)
            val eased = p * p * (3f - 2f * p)
            g.fill(x0, 0, minOf(full, x0 + 2), height, shade(0.88f * eased * appear))
            x0 += 2
        }
        g.fill(full, 0, width, height, shade(0.88f * appear))
        g.fillGradient(0, 0, width, 64, shade(0.72f * appear), shade(0f))
        g.fillGradient(0, height - 72, width, height, shade(0f), shade(0.6f * appear))
    }

    private fun shade(alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0x0B0C10

    private fun drawTopBar(g: GuiGraphics, client: Minecraft, mouseX: Int, mouseY: Int, appear: Float) {
        // River, top left.
        val logo = logo()
        if (logo != null) g.riverBlit(logo.first, 22, 13, 0f, 0f, 16, 16, logo.second, logo.third, logo.second, logo.third)
        g.drawString(font, "River Client", 44, 17, a(ClientUi.TEXT, appear), false)

        // Where to go, across the middle.
        val tabs = listOf<Pair<String, (() -> Unit)?>>(
            "Game" to null,
            "Modules" to { client.setScreen(RiverMenuScreen(this)) },
            "Wardrobe" to { client.setScreen(RiverCosmeticsScreen(this)) },
            "Friends" to { client.setScreen(RiverFriendsScreen(this)) }
        )
        val gap = 24
        val total = tabs.sumOf { font.width(it.first) } + gap * (tabs.size - 1)
        var tx = (width - total) / 2
        val ty = 17
        for ((label, open) in tabs) {
            val w = font.width(label)
            val hovered = open != null && mouseX in (tx - 6)..(tx + w + 6) && mouseY in (ty - 6)..(ty + 14)
            val glow = ClientUi.hover("pause-tab:$label", hovered)
            val color = if (open == null) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, glow)
            g.drawString(font, label, tx, ty, a(color, appear), false)
            if (open == null) {
                ClientUi.fillRounded(g, tx + w / 2 - 7, ty + 13, 14, 2, 1, a(ClientUi.ACCENT_B, appear))
            } else {
                hits += Hit(tx - 6, ty - 6, tx + w + 6, ty + 14, onClick = open)
            }
            tx += w + gap
        }

        // You, top right.
        val name = client.user.name
        val nameW = font.width(name)
        val headX = width - 22 - 16
        PlayerHeads.draw(g, client.player?.uuid?.toString() ?: "", name, headX, 13, 16, alpha = appear)
        g.drawString(font, name, headX - 8 - nameW, 17, a(ClientUi.TEXT, appear), false)
    }

    private fun drawColumn(g: GuiGraphics, client: Minecraft, mouseX: Int, mouseY: Int, appear: Float) {
        val x = width - 44 - COLUMN
        val listH = actions.size * ROW + (if (hasMore) ROW else 0)
        val total = 12 + 22 + 12 + 12 + 14 + 30 + 10 + listH + 13 + 26
        var y = maxOf(56, (height - total) / 2) + ((1f - appear) * 6f).toInt()

        // Where you are.
        val place = place(client)
        g.drawString(font, place.kind, x, y, a(ClientUi.MUTED, appear), false)
        y += 12
        ClientUi.withTransform(g, x.toFloat(), y.toFloat(), 1.6f) {
            g.drawString(font, trim(place.title, (COLUMN / 1.6f).toInt()), 0, 0, a(ClientUi.TEXT, appear), false)
        }
        y += 22
        if (place.detail.isNotEmpty()) g.drawString(font, trim(place.detail, COLUMN), x, y, a(ClientUi.MUTED, appear), false)
        y += 12
        val dot = if (place.paused) ClientUi.POSITIVE else ClientUi.WARNING
        if (RiverShapes.available) RiverShapes.circle(g, x.toFloat(), y + 2.5f, 5f, a(dot, appear))
        else g.fill(x, y + 2, x + 5, y + 7, a(dot, appear))
        g.drawString(font, if (place.paused) "Paused" else "Still running", x + 10, y, a(ClientUi.MUTED, appear), false)
        y += 14 + 12

        // Back to the game: the one thing most people came here for.
        run {
            val h = 30
            val hovered = mouseX in x..(x + COLUMN) && mouseY in y..(y + h)
            val glow = ClientUi.hover("pause-back", hovered)
            val fill = ClientUi.mix(ClientUi.ACCENT_A, ClientUi.over(ClientUi.alpha(ClientUi.LIFT, 0.14f), ClientUi.ACCENT_A), glow)
            ClientUi.fillRounded(g, x, y, COLUMN, h, ClientUi.RADIUS_BUTTON, a(fill, appear))
            val label = Component.translatable("menu.returnToGame").string
            g.drawString(font, label, x + (COLUMN - font.width(label)) / 2, y + (h - 8) / 2, a(ClientUi.TEXT, appear), false)
            hits += Hit(x, y, x + COLUMN, y + h) { backToGame() }
            y += h + 10
        }

        // Vanilla's actions, then More.
        for (action in actions) {
            row(g, x, y, mouseX, mouseY, appear, "pause-row:${action.key}:${action.label}", action.icon, action.label, action.button.active, sound = false) { press(action.button) }
            y += ROW
        }
        if (hasMore) {
            row(g, x, y, mouseX, mouseY, appear, "pause-row:more", "layout", "More", true) { showVanillaMenu() }
            y += ROW
        }

        // Leaving, set apart and red.
        y += 6
        g.fill(x, y, x + COLUMN, y + 1, a(ClientUi.alpha(ClientUi.BORDER, 0.8f), appear))
        y += 7
        val leave = disconnect
        if (leave != null) {
            val h = 26
            val hovered = mouseX in x..(x + COLUMN) && mouseY in y..(y + h) && leave.active
            val glow = ClientUi.hover("pause-leave", hovered)
            ClientUi.fillRounded(g, x, y, COLUMN, h, ClientUi.RADIUS_BUTTON, a(ClientUi.alpha(ClientUi.DANGER, 0.10f + 0.10f * glow), appear))
            val label = leave.message.string
            RiverIcons.draw(g, "x", x + 10, y + (h - 9) / 2, 9, a(ClientUi.DANGER, appear))
            g.drawString(font, trim(label, COLUMN - 34), x + 26, y + (h - 8) / 2, a(ClientUi.DANGER, appear), false)
            hits += Hit(x, y, x + COLUMN, y + h, sound = false) { press(leave) }
        }
    }

    private fun row(g: GuiGraphics, x: Int, y: Int, mouseX: Int, mouseY: Int, appear: Float, id: String, icon: String, label: String, active: Boolean, sound: Boolean = true, onClick: () -> Unit) {
        val hovered = active && mouseX in x..(x + COLUMN) && mouseY in y..(y + ROW - 2)
        val glow = ClientUi.hover(id, hovered)
        if (glow > 0.01f) ClientUi.fillRounded(g, x, y, COLUMN, ROW - 2, ClientUi.RADIUS_BUTTON, a(ClientUi.alpha(ClientUi.LIFT, 0.07f * glow), appear))
        val color = when {
            !active -> ClientUi.DIM
            else -> ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, 0.55f + 0.45f * glow)
        }
        RiverIcons.draw(g, icon, x + 8, y + (ROW - 2 - 11) / 2, 11, a(color, appear))
        g.drawString(font, trim(label, COLUMN - 36), x + 26, y + (ROW - 2 - 8) / 2, a(color, appear), false)
        if (active) hits += Hit(x, y, x + COLUMN, y + ROW - 2, sound, onClick)
    }

    /** The bottom left: you, the frame rate, and what is playing. */
    private fun drawChips(g: GuiGraphics, client: Minecraft, mouseX: Int, mouseY: Int, appear: Float) {
        val h = 30
        val y = height - 22 - h
        var x = 22

        // You.
        run {
            val name = client.user.name
            val w = 10 + 18 + 7 + maxOf(font.width(name), font.width("You")) + 12
            chip(g, x, y, w, h, appear)
            PlayerHeads.draw(g, client.player?.uuid?.toString() ?: "", name, x + 10, y + 6, 18, alpha = appear)
            g.drawString(font, name, x + 35, y + 6, a(ClientUi.TEXT, appear), false)
            g.drawString(font, "You", x + 35, y + 17, a(ClientUi.DIM, appear), false)
            x += w + 8
        }

        // The frame rate.
        run {
            val fps = client.fps.coerceAtLeast(0)
            val top = "$fps FPS"
            val bottom = if (fps > 0) "%.1f ms".format(1000f / fps) else "-"
            val w = 12 + maxOf(font.width(top), font.width(bottom)) + 12
            chip(g, x, y, w, h, appear)
            g.drawString(font, top, x + 12, y + 6, a(ClientUi.TEXT, appear), false)
            g.drawString(font, bottom, x + 12, y + 17, a(ClientUi.DIM, appear), false)
            x += w + 8
        }

        // What is playing, with its keys, when the Spotify module is on.
        val music = ModuleRegistry.get<NowPlayingModule>("now_playing")?.takeIf { it.active } ?: return
        val playback = music.playback() ?: return
        run {
            val textW = maxOf(font.width(playback.title), font.width(playback.artist)).coerceIn(60, 130)
            val w = 8 + 22 + 8 + textW + 10 + 3 * 15 + 6
            chip(g, x, y, w, h, appear)
            val cover = music.coverForMenu()
            if (cover != null) {
                g.riverBlit(cover.first, x + 8, y + 4, 0f, 0f, 22, 22, cover.second, cover.third, cover.second, cover.third)
            } else {
                ClientUi.fillRounded(g, x + 8, y + 4, 22, 22, 3, a(ClientUi.PANEL_ALT, appear))
                RiverIcons.draw(g, "spotify", x + 12, y + 8, 14, a(0xFF1DB954.toInt(), appear))
            }
            val tx = x + 38
            g.drawString(font, trim(playback.title, textW), tx, y + 6, a(ClientUi.TEXT, appear), false)
            g.drawString(font, trim(playback.artist, textW), tx, y + 17, a(ClientUi.DIM, appear), false)
            var cx = tx + textW + 10
            for (action in listOf("prev", "playpause", "next")) {
                val hovered = mouseX in (cx - 2)..(cx + 13) && mouseY in (y + 7)..(y + 24)
                val glow = ClientUi.hover("pause-media:$action", hovered)
                if (glow > 0.01f) ClientUi.fillRounded(g, cx - 2, y + 7, 15, 16, 3, a(ClientUi.alpha(ClientUi.LIFT, 0.10f * glow), appear))
                music.drawControl(g, action, cx, y + 10, playback.playing)
                hits += Hit(cx - 2, y + 7, cx + 13, y + 24) { music.control(action) }
                cx += 15
            }
        }
    }

    private fun chip(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, appear: Float) {
        if (!Glass.surface(g, x, y, w, h, 8, ClientUi.alpha(ClientUi.PANEL, 0.82f * appear), ClientUi.PANEL, 0.55f * appear, false)) {
            ClientUi.fillRounded(g, x, y, w, h, 8, a(ClientUi.alpha(ClientUi.PANEL, 0.82f), appear))
        }
        ClientUi.drawRoundedBorder(g, x, y, w, h, 8, a(ClientUi.alpha(ClientUi.LIFT, 0.08f), appear))
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

    private fun trim(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var out = text
        while (out.isNotEmpty() && font.width("$out...") > maxWidth) out = out.dropLast(1)
        return "$out..."
    }

    // ------------------------------------------------------------------ input

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
