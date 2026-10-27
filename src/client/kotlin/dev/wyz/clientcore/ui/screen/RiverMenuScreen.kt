package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.config.ConfigService
import dev.wyz.clientcore.config.ServerProfiles
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.net.RiverSocial
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.PlayerHeads
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverText
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.RiverTheme
import dev.wyz.clientcore.ui.Seasons
import dev.wyz.clientcore.ui.menu.SeasonalFx
import dev.wyz.clientcore.ui.widget.SettingsPanel
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?} else {
/**///?}
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The main River Client menu (Right Shift). Fullscreen click GUI with sidebar
 * categories, instant search, module cards and a popup for a module's settings.
 *
 * Built from the same pieces as the friends screen so the client reads as one thing: a
 * darker sidebar with the brand in a 34px header row and the pages grouped under small
 * caps labels, a header row over the content with the page's title, what is on, and the
 * search, and selection shown as an accent wash with a bar on its edge rather than a box
 * drawn round every button.
 */
/**
 * [focusModule] opens the menu with that module's settings panel already out, which is
 * how the HUD editor's cog gets here - the editor no longer carries its own copy of the
 * settings UI in the middle of the screen.
 */
class RiverMenuScreen(private val parent: Screen?, focusModule: Module? = null) : Screen(Component.literal("River Client")), RiverScreen {

    private enum class Page(val label: String, val icon: String, val category: ModuleCategory?) {
        FAVORITES("Favorites", "star", null),
        ALL("All", "grid", null),
        HUD("HUD", "monitor", ModuleCategory.HUD),
        VISUAL("Visual", "eye", ModuleCategory.VISUAL),
        GAMEPLAY("Gameplay", "compass", ModuleCategory.GAMEPLAY),
        UTILITY("Utility", "wrench", ModuleCategory.UTILITY),
        SETTINGS("Settings", "gear", null)
    }

    private enum class ProfileEdit { NONE, CREATE, RENAME }

    private var page = focusModule?.let { m -> Page.entries.firstOrNull { it.category == m.category } } ?: Page.HUD
    private var pageSwitchedAt = System.currentTimeMillis()
    private val scrollByPage = HashMap<Page, Int>()
    private var contentHeight = 0

    private var searchText = ""
    private var searchFocused = false

    private var selectedModule: Module? = focusModule

    /** Whether the screen this was opened from was drawn behind it this frame. */
    private var parentShown = false

    /** The frame's partial tick, so a preview animates at the same rate as the HUD. */
    private var previewPartialTick = 0f
    private var panelOpen = focusModule != null
    private val settingsPanel = SettingsPanel { selectedModule?.settings() ?: emptyList() }

    private var profileEdit = ProfileEdit.NONE
    private var profileInput = ""
    private var profileRenameTarget = ""
    private var deleteArmedFor = ""
    private var deleteArmedAt = 0L
    private var capturingCinematicKey = false
    private var capturingFriendsKey = false
    private var profileFlash = ""
    private var profileFlashAt = 0L
    private var themeHexFocused = false
    private var themeHexInput = ""
    /** Which custom gradient stop the hex box + typing edits: 0 = start, 1 = end. */
    private var themeHexTarget = 0

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)

    private val hits = ArrayList<Hit>()
    private var panelRect: IntArray? = null

    /*
      Index into [hits] where the popup's own regions begin. A click over the popup may
      only run those: without this, a click that lands on the popup's background falls
      through to whatever module card happens to sit behind it, which is exactly what it
      looked like - toggling modules by clicking on a settings panel.
    */
    private var panelHitFloor = 0
    private var panelBodyTop = 0
    private var panelBodyBottom = 0
    private var contentRect: IntArray = intArrayOf(0, 0, 0, 0)

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    /** Hit region clipped to the scrollable content area so scrolled-out cards can't shadow the top bar. */
    private fun contentHit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        val y1 = y.coerceAtLeast(contentRect[1])
        val y2 = (y + h).coerceAtMost(contentRect[3])
        if (y2 <= y1) return
        hits.add(Hit(x, y1, x + w, y2, onClick))
    }

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        ClientUi.beginFrame()
        hits.clear()
        panelRect = null

        val margin = 12
        val wx = margin
        val wy = margin
        val ww = width - margin * 2
        val wh = height - margin * 2
        ClientUi.drawPanel(g, wx, wy, ww, wh, seeThrough = parentShown)

        val sidebarW = if (ww >= 560) 128 else 104
        drawSidebar(g, wx, wy, sidebarW, wh, mouseX, mouseY)

        val mainX = wx + sidebarW + 1
        val mainW = ww - sidebarW - 1
        drawTopBar(g, mainX, wy, mainW, mouseX, mouseY)

        val cx = mainX + 14
        val cw = mainW - 28
        val contentY = wy + HEADER_H + 8
        val contentH = wh - HEADER_H - 14
        contentRect = intArrayOf(cx, contentY, cx + cw, contentY + contentH)

        ClientUi.withScissor(g, cx, contentY, cx + cw, contentY + contentH) {
            if (page == Page.SETTINGS && searchText.isBlank()) {
                drawSettingsPage(g, cx, contentY, cw, contentH, mouseX, mouseY)
            } else {
                drawModuleGrid(g, cx, contentY, cw, contentH, mouseX, mouseY)
            }
        }

        previewPartialTick = partialTick
        ClientUi.withOverlayLayer(g) { drawModulePopup(g, mouseX, mouseY) }
    }

    // ------------------------------------------------------------------ sidebar

    /*
      With the glass effect on, every surface is see-through so the blurred world shows
      through the whole menu, sidebar included: solid colours become washes and an overlay
      is laid on as it is instead of being composited to an opaque colour. With glass off -
      or on the title screen, where there is no world to blur - the surfaces stay solid.
    */
    /** [color] as a surface: itself, or at [glassAlpha] on glass. */
    private fun glass(color: Int, glassAlpha: Float): Int = if (ClientUi.glassFrame) ClientUi.alpha(color, glassAlpha) else color

    /** [overlay] on [base]: composited to an opaque colour on a solid surface, left translucent on glass. */
    private fun lay(overlay: Int, base: Int): Int = if (ClientUi.glassFrame) overlay else ClientUi.over(overlay, base)

    /** A text field's or keycap's well: darker than what it sits on. */
    private fun wellBg(): Int = if (ClientUi.glassFrame) ClientUi.alpha(0xFF000000.toInt(), 0.38f)
        else ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.32f), ClientUi.PANEL)

    /** The sidebar sits a step below the panel, the same surface as the friends screen's rail. */
    private fun sidebarBg(): Int = glass(ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.28f), ClientUi.PANEL), 0.55f)

    private fun drawSidebar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val bg = sidebarBg()
        g.fill(x + 1, y + 1, x + w, y + h - 1, bg)
        g.fill(x + w, y + 1, x + w + 1, y + h - 1, ClientUi.BORDER)

        // The brand, in the header row every River screen has.
        if (RiverTheme.current.key == "halloween") {
            SeasonalFx.drawPumpkinIcon(g, x + 10, y + 9, 14)
            ClientUi.drawGradientTitle(g, font, "River Client", x + 28, y + 13)
        } else {
            ClientUi.drawGradientTitle(g, font, "River Client", x + 12, y + 13)
        }
        g.fill(x + 1, y + HEADER_H - 1, x + w, y + HEADER_H, ClientUi.BORDER)

        selectedNavY = -1
        var by = y + HEADER_H + 8
        by = navLabel(g, "MODULES", x, by, w)
        Page.entries.forEach { entry ->
            if (entry == Page.SETTINGS) return@forEach
            by += drawSidebarButton(g, entry, x + 6, by, w - 12, mouseX, mouseY)
        }
        by += 6
        by = navLabel(g, "CLIENT", x, by, w)
        by += drawSidebarButton(g, Page.SETTINGS, x + 6, by, w - 12, mouseX, mouseY)
        by += navLink(g, "users", "Friends", RiverSocial.unreadTotal + RiverSocial.requests.size, x + 6, by, w - 12, mouseX, mouseY) {
            saveNow()
            minecraft?.setScreen(RiverFriendsScreen(this))
        }
        by += navLink(g, "shirt", "Cosmetics", 0, x + 6, by, w - 12, mouseX, mouseY) {
            saveNow()
            minecraft?.setScreen(RiverCosmeticsScreen(this))
        }
        navLink(g, "layout", "HUD Editor", 0, x + 6, by, w - 12, mouseX, mouseY) {
            saveNow()
            minecraft?.setScreen(RiverHudEditorScreen(this))
        }

        // The bar on the selected row's edge slides from page to page rather than jumping,
        // the same as the one on the friends screen's rail.
        if (selectedNavY >= 0) {
            val barY = ClientUi.animate("sb:bar", selectedNavY.toFloat(), 18f).roundToInt()
            g.fill(x + 6, barY, x + 8, barY + NAV_ROW, ClientUi.ACCENT_A)
        }

        // You, at the bottom: who is playing, and on which profile. Profiles live under
        // Settings, so that is where a click goes.
        val py = y + h - 36
        g.fill(x + 1, py, x + w, py + 1, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        val hovered = mouseX in (x + 1)..(x + w) && mouseY in (py + 1)..(y + h - 1)
        val anim = ClientUi.hover("sb:me", hovered)
        if (anim > 0.01f) g.fill(x + 1, py + 1, x + w, y + h - 1, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.05f * anim), bg))
        val me = RiverSocial.selfName.ifEmpty { minecraft?.user?.name ?: "" }
        PlayerHeads.draw(g, RiverSocial.selfUuid, me, x + 10, py + 9, 18)
        g.drawString(font, trim(me, w - 44), x + 34, py + 9, ClientUi.TEXT, true)
        RiverIcons.draw(g, "folder", x + 34, py + 20, 8, ClientUi.DIM)
        g.drawString(font, trim(ConfigService.activeProfile(), w - 56), x + 45, py + 20, ClientUi.DIM, false)
        hit(x + 1, py + 1, w - 1, 35) { switchPage(Page.SETTINGS) }
    }

    /** Where the selected sidebar row was drawn this frame, for the sliding bar. -1 = none. */
    private var selectedNavY = -1

    /** A small caps label over a group of sidebar entries. */
    private fun navLabel(g: GuiGraphics, label: String, x: Int, y: Int, w: Int): Int {
        val shown = RiverText.small(label)
        g.drawString(font, shown, x + 12, y + 2, ClientUi.DIM, false)
        val lineX = x + 12 + font.width(shown) + 6
        if (lineX < x + w - 10) g.fill(lineX, y + 6, x + w - 10, y + 7, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        return y + 14
    }

    /** How many modules a page lists, shown at the end of its sidebar row. */
    private fun pageCount(entry: Page): Int = when (entry) {
        Page.FAVORITES -> ModuleRegistry.all.count { it.favorite && it.showInMenu }
        Page.ALL -> ModuleRegistry.all.count { it.showInMenu }
        Page.SETTINGS -> 0
        else -> entry.category?.let { ModuleRegistry.byCategory(it).size } ?: 0
    }

    /** One sidebar row: icon, label, and either a count or a badge on the right. */
    private fun navRow(
        g: GuiGraphics, key: String, icon: String, label: String, x: Int, y: Int, w: Int,
        mouseX: Int, mouseY: Int, selected: Boolean, count: Int, badge: Int
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + NAV_ROW - 1)
        val anim = ClientUi.hover(key, hovered)
        val bg = sidebarBg()
        if (selected) {
            g.fill(x, y, x + w, y + NAV_ROW, lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.22f + 0.06f * anim), bg))
            selectedNavY = y
        } else if (anim > 0.01f) {
            g.fill(x, y, x + w, y + NAV_ROW, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f * anim), bg))
        }
        val fg = if (selected) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim)
        RiverIcons.draw(g, icon, x + 8, y + 5, 11, if (selected) ClientUi.ACCENT_B else fg)
        g.drawString(font, trim(label, w - 46), x + 24, y + 6, fg, selected)
        if (badge > 0) {
            val text = if (badge > 9) "9+" else badge.toString()
            val bw = max(10, font.width(text) + 5)
            val bx = x + w - 6 - bw
            g.fill(bx, y + 5, bx + bw, y + 15, ClientUi.ACCENT_A)
            g.drawString(font, text, bx + (bw - font.width(text) + 1) / 2, y + 6, ClientUi.TEXT, false)
        } else if (count > 0) {
            val text = count.toString()
            g.drawString(font, text, x + w - 7 - font.width(text), y + 6, if (selected) ClientUi.MUTED else ClientUi.DIM, false)
        }
    }

    private fun drawSidebarButton(g: GuiGraphics, entry: Page, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val selected = page == entry && searchText.isBlank()
        navRow(g, "sb:${entry.name}", entry.icon, entry.label, x, y, w, mouseX, mouseY, selected, pageCount(entry), 0)
        hit(x, y, w, NAV_ROW) { switchPage(entry) }
        return NAV_ROW + 2
    }

    /** A sidebar row that opens another screen rather than a page of this one. */
    private fun navLink(
        g: GuiGraphics, icon: String, label: String, badge: Int, x: Int, y: Int, w: Int,
        mouseX: Int, mouseY: Int, onClick: () -> Unit
    ): Int {
        navRow(g, "sb:link:$label", icon, label, x, y, w, mouseX, mouseY, false, 0, badge)
        hit(x, y, w, NAV_ROW) { onClick() }
        return NAV_ROW + 2
    }

    private fun switchPage(target: Page) {
        if (page != target) {
            page = target
            pageSwitchedAt = System.currentTimeMillis()
        }
        searchText = ""
        searchFocused = false
        profileEdit = ProfileEdit.NONE
    }

    // ------------------------------------------------------------------ top bar

    /**
     * The header over the content: which page this is and how much of it is on, then the
     * search and the close button on the right, the way the friends screen's header puts
     * who you are talking to on the left and the actions on the right.
     */
    private fun drawTopBar(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val searching = searchText.isNotBlank()
        val modules = if (page == Page.SETTINGS && !searching) emptyList() else visibleModules()
        val icon = if (searching) "search" else page.icon
        val title = if (searching) "Search" else page.label
        val summary = when {
            searching -> if (modules.size == 1) "1 module" else "${modules.size} modules"
            page == Page.SETTINGS -> "Theme, shortcuts and profiles"
            else -> "${modules.count { it.enabled }} of ${modules.size} on"
        }
        RiverIcons.draw(g, icon, x + 14, y + 11, 12, ClientUi.ACCENT_B)
        g.drawString(font, title, x + 32, y + 13, ClientUi.TEXT, true)

        // Close, then a divider, then the search to its left.
        val closeX = x + w - 28
        val closeHovered = mouseX in closeX..(closeX + 20) && mouseY in (y + 7)..(y + 27)
        val closeAnim = ClientUi.hover("menu:close", closeHovered)
        if (closeAnim > 0.01f) g.fill(closeX, y + 7, closeX + 20, y + 27, ClientUi.alpha(ClientUi.PANEL_ALT, closeAnim))
        RiverIcons.draw(g, "x", closeX + 5, y + 12, 10, if (closeHovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, y + 7, 20, 20) { onClose() }
        g.fill(closeX - 5, y + 11, closeX - 4, y + 23, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val searchW = min(220, max(120, w / 3))
        val sx = closeX - 10 - searchW
        val sy = y + 7
        val hovered = mouseX in sx..(sx + searchW) && mouseY in sy..(sy + 20)
        val anim = ClientUi.hover("search", hovered || searchFocused)
        g.fill(sx, sy, sx + searchW, sy + 20, wellBg())
        ClientUi.drawRoundedBorder(
            g, sx, sy, searchW, 20, 0,
            if (searchFocused) ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
            else ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.BORDER), anim)
        )
        RiverIcons.draw(g, "search", sx + 6, sy + 6, 9, if (searchFocused) ClientUi.ACCENT_B else ClientUi.DIM)
        if (searchText.isEmpty() && !searchFocused) {
            g.drawString(font, "Search modules", sx + 20, sy + 6, ClientUi.DIM, false)
            val hint = "Ctrl F"
            if (searchW > font.width("Search modules") + font.width(hint) + 40) {
                g.drawString(font, hint, sx + searchW - 7 - font.width(hint), sy + 6, ClientUi.alpha(ClientUi.DIM, 0.7f), false)
            }
        } else {
            val shown = tail(searchText, searchW - 30)
            g.drawString(font, shown, sx + 20, sy + 6, ClientUi.TEXT, false)
            if (searchFocused && System.currentTimeMillis() / 530 % 2 == 0L) {
                val caretX = sx + 20 + font.width(shown) + 1
                g.fill(caretX, sy + 5, caretX + 1, sy + 15, ClientUi.TEXT)
            }
        }
        hit(sx, sy, searchW, 20) { searchFocused = true }

        // What is on, after the title, as long as it does not run into the search.
        val summaryX = x + 32 + font.width(title) + 8
        g.drawString(font, trim(summary, sx - 12 - summaryX), summaryX, y + 13, ClientUi.DIM, false)

        g.fill(x, y + HEADER_H - 1, x + w - 1, y + HEADER_H, ClientUi.BORDER)
    }

    // ------------------------------------------------------------------ module grid

    private fun visibleModules(): List<Module> {
        if (searchText.isNotBlank()) {
            return ModuleRegistry.search(searchText).sortedByDescending { it.favorite }
        }
        return when (page) {
            Page.FAVORITES -> ModuleRegistry.all.filter { it.favorite && it.showInMenu }
            Page.ALL -> ModuleRegistry.all.filter { it.showInMenu }
            Page.SETTINGS -> emptyList()
            else -> page.category?.let { ModuleRegistry.byCategory(it) } ?: emptyList()
        }
    }

    private fun drawModuleGrid(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val modules = visibleModules()
        if (modules.isEmpty()) {
            val (icon, title, message) = when {
                searchText.isNotBlank() -> Triple("search", "No matches", "No modules match \"${trim(searchText, 120)}\"")
                page == Page.FAVORITES -> Triple("star", "No favorites yet", "Star a module to add it here.")
                else -> Triple(page.icon, "Nothing here yet", "Modules in this category will show up here")
            }
            // The same empty state as the friends screen: a tile, a title, one line.
            val cx = x + w / 2
            val ty = y + h / 2 - 34
            g.fill(cx - 14, ty, cx + 14, ty + 28, glass(ClientUi.PANEL, 0.5f))
            ClientUi.drawRoundedBorder(g, cx - 14, ty, 28, 28, 0, ClientUi.BORDER)
            RiverIcons.draw(g, icon, cx - 7, ty + 7, 14, ClientUi.MUTED)
            g.drawString(font, title, cx - font.width(title) / 2, ty + 38, ClientUi.TEXT, true)
            g.drawString(font, message, cx - font.width(message) / 2, ty + 51, ClientUi.DIM, false)
            contentHeight = 0
            return
        }

        val cols = if (w >= 540) 3 else 2
        val gap = 8
        val cardW = (w - (cols - 1) * gap - 10) / cols
        val cardH = CARD_H
        val scroll = scrollByPage.getOrDefault(page, 0)
        val now = System.currentTimeMillis()

        modules.forEachIndexed { index, module ->
            val col = index % cols
            val row = index / cols
            // Entrance: cards slide up + settle, staggered ~18ms per card, 200ms ease-out.
            val t = (((now - pageSwitchedAt) - index * 18L) / 200f).coerceIn(0f, 1f)
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            val slide = ((1f - ease) * 10f).roundToInt()
            val cardX = x + col * (cardW + gap)
            val cardY = y + 4 + row * (cardH + gap) - scroll + slide
            if (t <= 0f || cardY > y + h || cardY + cardH < y) return@forEachIndexed
            drawModuleCard(g, module, cardX, cardY, cardW, cardH, mouseX, mouseY)
        }

        val rows = (modules.size + cols - 1) / cols
        contentHeight = rows * (cardH + gap) + 8
    }

    private fun drawModuleCard(g: GuiGraphics, module: Module, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val inContent = mouseY >= contentRect[1] && mouseY <= contentRect[3] && !panelOpen && !isOverPanel(mouseX, mouseY)
        val hovered = inContent && mouseX in x..(x + w) && mouseY in y..(y + h)
        val anim = ClientUi.hover("card:${module.id}", hovered)
        val lift = (anim * 2f).roundToInt()
        val cy = y - lift
        val selected = panelOpen && selectedModule === module

        if (module.comingSoon) {
            ClientUi.drawModuleCard(g, x, y, w, h, 0f, false, false)
            RiverIcons.draw(g, module.icon, x + 15, y + (h - 16) / 2, 16, ClientUi.alpha(ClientUi.DIM, 0.7f))
            g.drawString(font, trim(module.displayName, w - 54), x + 44, y + 11, ClientUi.DIM, true)
            // Big, centred "Soon..." — light grey, shadowed, no tag chrome.
            val label = "Soon..."
            val scale = 2f
            val lw = font.width(label) * scale
            val lh = font.lineHeight * scale
            val pose = g.pose()
//? if >=1.21.6 {
            pose.pushMatrix()
            pose.translate(x + (w - lw) / 2f, y + h - lh - 6f)
            pose.scale(scale, scale)
//?} else {
/*            pose.pushPose()
            pose.translate(x + (w - lw) / 2f, y + h - lh - 6f, 0f)
            pose.scale(scale, scale, 1f)
*///?}
            g.drawString(font, label, 0, 0, 0xFFC2C6D0.toInt(), true)
//? if >=1.21.6 {
            pose.popMatrix()
//?} else {
/*            pose.popPose()
*///?}
            return
        }

        ClientUi.drawModuleCard(g, x, cy, w, h, anim, selected, module.enabled)
        // Body click opens the settings panel; the control hits below are registered later, so they win.
        contentHit(x, y, w, h) { openPanelFor(module) }
        if (module.enabled) {
            val allowed = ServerSafety.allows(module)
            val barColor = if (allowed) ClientUi.mix(ClientUi.ACCENT_A, ClientUi.ACCENT_B, 0.6f) else ClientUi.WARNING
            ClientUi.fillRounded(g, x + 1, cy + 8, 2, h - 16, 1, barColor)
        }

        // The icon sits on its own tile, which takes the accent while the module is on, so
        // a row of cards can be read for what is on without reading the toggles.
        val tileX = x + 10
        val tileY = cy + (h - 26) / 2
        val tileFill = if (module.enabled) ClientUi.over(ClientUi.alpha(ClientUi.ACCENT_A, 0.24f), ClientUi.PANEL)
            else ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.03f), ClientUi.PANEL)
        g.fill(tileX, tileY, tileX + 26, tileY + 26, glass(tileFill, 0.6f))
        ClientUi.drawRoundedBorder(
            g, tileX, tileY, 26, 26, 0,
            if (module.enabled) ClientUi.alpha(ClientUi.ACCENT_B, 0.45f) else ClientUi.alpha(ClientUi.BORDER, 0.9f)
        )
        RiverIcons.draw(g, module.icon, tileX + 5, tileY + 5, 16, if (module.enabled) ClientUi.TEXT else ClientUi.MUTED)

        // The cog and star only show while the card is hovered - or the star while it is a
        // favourite - so a page of cards is not a page of icons. They sit on the name's
        // line, left of the toggle, which leaves the description the card's full width.
        val controls = maxOf(anim, if (selected) 1f else 0f)
        val textX = x + 44
        val rightZone = if (controls > 0.01f) 92 else if (module.favorite) 72 else 56
        val name = trim(module.displayName, w - (textX - x) - rightZone)
        RiverText.drawBold(g, font, name, textX, cy + 11, if (module.enabled) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim))
        if (module.keybind >= 0) {
            // The bound key as a small keycap after the name.
            val key = dev.wyz.clientcore.ui.widget.InputNames.keyName(module.keybind)
            val kx = textX + RiverText.boldWidth(font, name) + 6
            val kw = font.width(key) + 8
            if (kx + kw < x + w - rightZone) {
                g.fill(kx, cy + 9, kx + kw, cy + 21, lay(ClientUi.alpha(ClientUi.ACCENT_B, 0.16f), ClientUi.PANEL_ALT))
                ClientUi.drawRoundedBorder(g, kx, cy + 9, kw, 12, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.45f))
                g.drawString(font, key, kx + 4, cy + 11, ClientUi.ACCENT_B, false)
            }
        }

        val descColor: Int
        val desc: String
        when {
            module.enabled && ServerSafety.cinematicMode -> {
                desc = "Paused by cinematic mode"
                descColor = ClientUi.WARNING
            }
            else -> {
                desc = module.description
                descColor = ClientUi.DIM
            }
        }
        g.drawString(font, trim(desc, w - (textX - x) - 12), textX, cy + 25, descColor, false)

        ClientUi.drawMinimalToggle(g, x + w - 44, cy + 9, module.enabled, hovered, id = "card:${module.id}")
        contentHit(x + w - 46, y + 6, 38, 18) { module.enabled = !module.enabled }

        val starX = x + w - 62
        val starHovered = inContent && mouseX in (starX - 3)..(starX + 13) && mouseY in (y + 6)..(y + 22)
        val starColor = when {
            module.favorite -> 0xFFFFD46B.toInt()
            starHovered -> ClientUi.TEXT
            else -> ClientUi.alpha(ClientUi.DIM, controls)
        }
        if (module.favorite || controls > 0.01f) RiverIcons.draw(g, "star", starX, cy + 10, 11, starColor)
        contentHit(starX - 3, y + 6, 16, 16) { module.favorite = !module.favorite }

        val gearX = starX - 17
        val gearHovered = inContent && mouseX in (gearX - 3)..(gearX + 13) && mouseY in (y + 6)..(y + 22)
        if (controls > 0.01f) {
            val gearColor = if (gearHovered || selected) ClientUi.ACCENT_B else ClientUi.DIM
            RiverIcons.draw(g, "gear", gearX, cy + 10, 11, ClientUi.alpha(gearColor, controls))
        }
        contentHit(gearX - 3, y + 6, 16, 16) { openPanelFor(module) }
    }

    private fun openPanelFor(module: Module) {
        if (panelOpen && selectedModule === module) {
            closePanel()
            return
        }
        selectedModule = module
        panelOpen = true
        settingsPanel.resetTransientState()
    }

    private fun closePanel() {
        panelOpen = false
        settingsPanel.resetTransientState()
        saveNow()
    }

    // ------------------------------------------------------------------ slide-out settings panel

    private fun isOverPanel(mouseX: Int, mouseY: Int): Boolean {
        val r = panelRect ?: return false
        return mouseX >= r[0] && mouseX <= r[2] && mouseY >= r[1] && mouseY <= r[3]
    }

    /**
     * The module's settings, as a popup over the middle of the screen.
     *
     * It used to slide in from the right over the grid, which read as part of the grid
     * rather than in front of it - and clicking its background toggled whatever card was
     * underneath. A dimmed backdrop and a centred card say "this owns the screen now",
     * and [panelHitFloor] makes that true for clicks as well as for the eye.
     */
    private fun drawModulePopup(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val anim = ClientUi.animate("menu:panel", if (panelOpen) 1f else 0f, 13f)
        val module = selectedModule
        if (anim < 0.02f || module == null) {
            if (!panelOpen) selectedModule = null
            return
        }

        val eased = 1f - (1f - anim) * (1f - anim) * (1f - anim)
        // Dim everything behind it. Fades with the popup so opening and closing are one move.
        g.fill(0, 0, width, height, ClientUi.alpha(0xFF05070C.toInt(), 0.62f * eased))

        // Wide enough for two columns: settings on the left, the preview on the right.
        val panelW = min(520, width - 40)
        val ph = min(380, height - 40)
        val px = (width - panelW) / 2
        // Rises the last few pixels into place rather than appearing flat.
        val py = (height - ph) / 2 + ((1f - eased) * 12f).roundToInt()

        panelHitFloor = hits.size
        // See-through, so the menu shows behind the popup the way it shows behind the window.
        ClientUi.drawPanel(g, px, py, panelW, ph, seeThrough = true)
        // Only a hint of the cards behind, so the settings in front stay easy to read.
        if (ClientUi.glassFrame) g.fill(px + 1, py + 1, px + panelW - 1, py + ph - 1, ClientUi.alpha(ClientUi.PANEL_SOFT, 0.6f))
        panelRect = intArrayOf(px, py, px + panelW, py + ph)

        // Header: the module on its tile, what it does, and its on/off switch, so a module
        // can be turned on from the place its settings are changed.
        val tileFill = if (module.enabled) lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.24f), ClientUi.PANEL_SOFT) else glass(ClientUi.PANEL, 0.5f)
        g.fill(px + 10, py + 7, px + 36, py + 33, tileFill)
        ClientUi.drawRoundedBorder(g, px + 10, py + 7, 26, 26, 0, if (module.enabled) ClientUi.alpha(ClientUi.ACCENT_B, 0.45f) else ClientUi.BORDER)
        RiverIcons.draw(g, module.icon, px + 15, py + 12, 16, if (module.enabled) ClientUi.TEXT else ClientUi.MUTED)
        RiverText.drawBold(g, font, trim(module.displayName, panelW - 140), px + 44, py + 10, ClientUi.TEXT)
        g.drawString(font, trim(module.description, panelW - 140), px + 44, py + 22, ClientUi.DIM, false)

        val closeX = px + panelW - 28
        val closeHovered = mouseX in closeX..(closeX + 20) && mouseY in (py + 10)..(py + 30)
        if (closeHovered) g.fill(closeX, py + 10, closeX + 20, py + 30, ClientUi.PANEL_ALT)
        RiverIcons.draw(g, "x", closeX + 5, py + 15, 10, if (closeHovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, py + 10, 20, 20) { closePanel() }
        g.fill(closeX - 5, py + 14, closeX - 4, py + 26, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        val toggleX = closeX - 46
        val toggleHovered = mouseX in toggleX..(toggleX + 36) && mouseY in (py + 12)..(py + 28)
        ClientUi.drawMinimalToggle(g, toggleX, py + 13, module.enabled, toggleHovered, id = "popup:${module.id}")
        hit(toggleX - 2, py + 10, 40, 20) { module.enabled = !module.enabled }
        g.fill(px + 1, py + 40, px + panelW - 1, py + 41, ClientUi.BORDER)

        /*
          Two columns. The settings are what you came to change, so they get the left side
          and the reading order; the preview sits beside them rather than above, where it
          used to push the first few settings off the bottom of a short popup.
        */
        val bodyY = py + 44
        val footerH = 32
        val bodyH = ph - 44 - footerH
        val client = minecraft
        val bounds = client?.let { module.previewBounds(it) }

        val previewW = if (bounds == null) 0 else ((panelW - 20) * 0.38f).roundToInt().coerceIn(110, 196)
        val settingsW = panelW - 4 - (if (previewW > 0) previewW + 14 else 0)

        panelBodyTop = bodyY
        panelBodyBottom = bodyY + bodyH
        settingsPanel.render(g, font, px + 2, bodyY, settingsW, bodyH, mouseX, mouseY)

        if (bounds != null && client != null) {
            val boxH = min(bodyH - 4, 168)
            drawModulePreview(g, client, module, bounds, px + panelW - 10 - previewW, bodyY + 2, previewW, boxH)
        }

        // Footer: reset in red, because it throws away every setting above it and there is
        // no undo. Kept to the corner, away from the settings, so it is not hit by accident.
        val resetH = 20
        val resetY = py + ph - resetH - 6
        g.fill(px + 1, py + ph - footerH, px + panelW - 1, py + ph - footerH + 1, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        g.drawString(font, trim("Changes apply as you make them", panelW - 140), px + 12, resetY + 6, ClientUi.DIM, false)
        val resetW = font.width("Reset module") + 24
        val resetX = px + panelW - 10 - resetW
        val resetHovered = mouseX in resetX..(resetX + resetW) && mouseY in resetY..(resetY + resetH)
        ClientUi.drawFlatButton(g, font, resetX, resetY, resetW, resetH, "Reset module", resetHovered, false, danger = true)
        hit(resetX, resetY, resetW, resetH) {
            module.resetModuleSettings()
            settingsPanel.resetTransientState()
        }
    }

    /**
     * Draws [module] inside a framed box, scaled to fit and centred on whatever it
     * actually draws. The module renders exactly as it does in game - same code, same
     * settings - so the box updates live while a slider moves.
     */
    private fun drawModulePreview(
        g: GuiGraphics,
        client: Minecraft,
        module: Module,
        bounds: IntArray,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        // A darker well, square like everything else, so the HUD element stands out from the popup.
        g.fill(x, y, x + w, y + h, wellBg())
        ClientUi.drawRoundedBorder(g, x, y, w, h, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val bw = bounds[2].coerceAtLeast(1)
        val bh = bounds[3].coerceAtLeast(1)
        // Fits what is there, then stays inside a range: a one-pixel crosshair blown up
        // to fill the box would be a lie about its size, and so would a shrunken HUD.
        val fit = min((w - 14).toFloat() / bw, (h - 14).toFloat() / bh)
        // Whole-number scale and a whole-pixel offset when enlarging: at 1.7x a one-pixel
        // line lands on one screen pixel here and two there, which is what made the
        // crosshair preview look mangled.
        val scale = if (fit >= 1f) floor(fit.coerceAtMost(2.5f)) else fit.coerceAtLeast(0.4f)
        val dx = ((x + w / 2f) - (bounds[0] + bw / 2f) * scale).roundToInt().toFloat()
        val dy = ((y + h / 2f) - (bounds[1] + bh / 2f) * scale).roundToInt().toFloat()

        ClientUi.withScissor(g, x + 1, y + 1, x + w - 1, y + h - 1) {
            ClientUi.withTransform(g, dx, dy, scale) {
                module.renderScaled(g) { module.renderEditorPreview(client, g, previewPartialTick) }
            }
        }
    }

    // ------------------------------------------------------------------ settings page

    /*
      Every card on this page used to invent its own geometry: toggles on 20px rows in one
      card and 22px in the next, labels sitting at +4 or +5 depending on who wrote them,
      hit regions stopping at w - 60 in some rows and running the full width in others.
      Nothing lined up vertically, which is what made the page look untidy even though each
      card on its own was fine.

      These are the numbers SettingsPanel already uses for module settings, so the two
      surfaces now share one vocabulary rather than drifting apart.
    */
    private object Metrics {
        /** Card inner padding. Content spans x + PAD to x + w - PAD. */
        const val PAD = 12
        /** A card's header: icon tile, title and the hairline under them. */
        const val HEADER_ROW = 36
        /** A label-plus-toggle row, matching SettingsPanel's BoolSetting. */
        const val TOGGLE_ROW = 20
        /** A row holding a boxed control (keybind, hex field, dropdown). */
        const val CONTROL_ROW = 22
        /** Height of a boxed control inside a CONTROL_ROW. */
        const val BOX_H = 18
        /** Distance from the content's right edge to a toggle's left edge. */
        const val TOGGLE_INSET = 34
        /** A line of explanatory text under a section header. */
        const val NOTE_ROW = 18
        /** Gap between cards. */
        const val CARD_GAP = 8
        /** Below this width the page is one column of cards rather than two. */
        const val TWO_COLUMNS_FROM = 600
    }

    private fun drawSettingsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val scroll = scrollByPage.getOrDefault(page, 0)
        val top = y + 4 - scroll

        /*
          Ordered by how often it is reached rather than by when it was written, so profiles
          no longer sit between the user and the settings they actually came here to change.
          On a wide window the cards sit in two columns - the look of the client on the
          left, how it behaves on the right - instead of one column of cards stretched
          to twice the width their rows need.
        */
        if (w >= Metrics.TWO_COLUMNS_FROM) {
            val colW = (w - 10 - Metrics.CARD_GAP) / 2
            var left = drawThemeCard(g, x, top, colW, mouseX, mouseY)
            left = drawCinematicCard(g, x, left + Metrics.CARD_GAP, colW, mouseX, mouseY)
            val rightX = x + colW + Metrics.CARD_GAP
            var right = drawFriendsCard(g, rightX, top, colW, mouseX, mouseY)
            right = drawProfilesCard(g, rightX, right + Metrics.CARD_GAP, colW, mouseX, mouseY)
            contentHeight = max(left, right) - top + 12
        } else {
            val cardW = w - 10
            var cy = drawThemeCard(g, x, top, cardW, mouseX, mouseY)
            cy = drawCinematicCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)
            cy = drawFriendsCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)
            cy = drawProfilesCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)
            contentHeight = cy - top + 12
        }
    }

    /** A card's header: the icon on an accent tile, the title, and a hairline across the card. */
    private fun sectionHeader(g: GuiGraphics, icon: String, title: String, x: Int, y: Int, w: Int): Int {
        val tileX = x + Metrics.PAD
        val tileY = y + 8
        g.fill(tileX, tileY, tileX + 18, tileY + 18, lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.22f), ClientUi.PANEL_ALT))
        ClientUi.drawRoundedBorder(g, tileX, tileY, 18, 18, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.45f))
        RiverIcons.draw(g, icon, tileX + 4, tileY + 4, 10, ClientUi.ACCENT_B)
        RiverText.drawBold(g, font, title, tileX + 26, y + 13, ClientUi.TEXT)
        g.fill(x + 1, y + 31, x + w - 1, y + 32, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        return y + Metrics.HEADER_ROW
    }

    /** Explanatory line under a header. Trimmed to the card so long copy cannot overhang it. */
    private fun noteLine(g: GuiGraphics, x: Int, cy: Int, w: Int, text: String): Int {
        g.drawString(font, trim(text, w - Metrics.PAD * 2), x + Metrics.PAD, cy + 2, ClientUi.DIM, false)
        return cy + Metrics.NOTE_ROW
    }

    /** A button on a card: outlined, since a filled one in the card's own colour disappears. */
    private fun outlineButton(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, label: String, hovered: Boolean, primary: Boolean = false) {
        val anim = ClientUi.hover("obtn:$label", hovered)
        if (primary) {
            g.fill(x, y, x + w, y + h, ClientUi.mix(ClientUi.ACCENT_A, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.ACCENT_A), anim))
            g.fill(x, y, x + w, y + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
        } else {
            g.fill(x, y, x + w, y + h, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.03f + 0.05f * anim))
            ClientUi.drawRoundedBorder(g, x, y, w, h, 0, ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.18f), ClientUi.BORDER), anim))
        }
        val shown = trim(label, w - 6)
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + (h - 8) / 2, if (primary || hovered) ClientUi.TEXT else ClientUi.MUTED, false)
    }

    /** The lift a settings row takes under the cursor, the same one the friends list uses. */
    private fun rowHover(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, anim: Float) {
        if (anim > 0.01f) g.fill(x, y, x + w, y + h, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.04f * anim))
    }

    /**
     * Label on the left, toggle on the right, the whole row clickable.
     *
     * The row being clickable end to end is the reason this is shared: the cards used to
     * cut their hit regions at various widths, so on some rows the gap between the label
     * and the toggle did nothing when clicked and on others it worked.
     */
    private fun toggleRow(
        g: GuiGraphics,
        x: Int,
        cy: Int,
        w: Int,
        mouseX: Int,
        mouseY: Int,
        label: String,
        value: Boolean,
        id: String,
        onToggle: () -> Unit
    ): Int {
        val rowX = x + Metrics.PAD
        val rowW = w - Metrics.PAD * 2
        val hovered = mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + Metrics.TOGGLE_ROW - 2)
        rowHover(g, rowX - 4, cy - 1, rowW + 8, Metrics.TOGGLE_ROW, ClientUi.hover("row:$id", hovered))
        g.drawString(
            font,
            trim(label, rowW - 44),
            rowX,
            cy + 5,
            if (value || hovered) ClientUi.TEXT else ClientUi.MUTED,
            false
        )
        ClientUi.drawMinimalToggle(g, rowX + rowW - Metrics.TOGGLE_INSET, cy + 2, value, hovered, id = id)
        hit(rowX, cy, rowW, Metrics.TOGGLE_ROW - 2) { onToggle() }
        return cy + Metrics.TOGGLE_ROW
    }

    /** Label on the left, a keycap on the right that listens for the next key when clicked. */
    private fun keybindRow(
        g: GuiGraphics,
        x: Int,
        cy: Int,
        w: Int,
        mouseX: Int,
        mouseY: Int,
        label: String,
        keyLabel: String,
        capturing: Boolean,
        id: String,
        onClick: () -> Unit
    ): Int {
        val rowX = x + Metrics.PAD
        val rowW = w - Metrics.PAD * 2
        val boxW = 78
        val boxX = rowX + rowW - boxW
        g.drawString(font, trim(label, rowW - boxW - 8), rowX, cy + 5, ClientUi.MUTED, false)
        val hovered = mouseX in boxX..(boxX + boxW) && mouseY in cy..(cy + Metrics.BOX_H)
        val anim = ClientUi.hover(id, hovered)
        g.fill(boxX, cy, boxX + boxW, cy + Metrics.BOX_H, wellBg())
        ClientUi.drawRoundedBorder(
            g, boxX, cy, boxW, Metrics.BOX_H, 0,
            if (capturing) ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
            else ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.16f), ClientUi.BORDER), anim)
        )
        val shown = trim(keyLabel, boxW - 12)
        g.drawString(
            font,
            shown,
            boxX + (boxW - font.width(shown)) / 2,
            cy + 5,
            if (capturing) ClientUi.ACCENT_B else ClientUi.TEXT,
            false
        )
        hit(boxX, cy, boxW, Metrics.BOX_H) { onClick() }
        return cy + Metrics.CONTROL_ROW
    }

    private fun drawProfilesCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val profiles = ConfigService.listProfiles()
        val editing = profileEdit != ProfileEdit.NONE
        val rows = profiles.size + 1 + (if (editing) 1 else 0)
        val cardH = Metrics.HEADER_ROW + rows * 24 + 10
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "folder", "Profiles", x, y, w)

        val active = ConfigService.activeProfile()
        profiles.forEach { name ->
            val rowX = x + 10
            val rowW = w - 20
            val isActive = name == active
            val hovered = mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + 20)
            // The active profile reads like the selected friend: an accent wash with a bar on
            // its edge. Washes rather than solid rows, so they sit on the card's glass.
            val rowAnim = ClientUi.hover("prow:$name", hovered)
            if (isActive) {
                g.fill(rowX, cy, rowX + rowW, cy + 20, ClientUi.alpha(ClientUi.ACCENT_A, 0.18f + 0.06f * rowAnim))
                g.fill(rowX, cy, rowX + 2, cy + 20, ClientUi.ACCENT_A)
                g.fill(rowX + 7, cy + 8, rowX + 11, cy + 12, ClientUi.POSITIVE)
            } else {
                rowHover(g, rowX, cy, rowW, 20, rowAnim)
            }
            g.drawString(font, trim(name, rowW - 150), rowX + 16, cy + 6, if (isActive) ClientUi.TEXT else ClientUi.MUTED, isActive)
            // Servers this profile switches in on, after the name.
            val hosts = ServerProfiles.hostsFor(name)
            if (hosts.isNotEmpty()) {
                val hx = rowX + 16 + font.width(trim(name, rowW - 150)) + 8
                g.drawString(font, trim(hosts.joinToString(", "), rowX + rowW - 124 - hx), hx, cy + 6, ClientUi.DIM, false)
            }

            var bx = rowX + rowW - 18
            // delete (not for active / last)
            if (!isActive && profiles.size > 1) {
                val armed = deleteArmedFor == name && System.currentTimeMillis() - deleteArmedAt < 3000
                RiverIcons.draw(g, "trash", bx, cy + 4, 11, if (armed) 0xFFFF6B6B.toInt() else ClientUi.DIM)
                if (armed) g.drawString(font, "sure?", bx - 30, cy + 6, 0xFFFF6B6B.toInt(), true)
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) {
                    if (deleteArmedFor == name && System.currentTimeMillis() - deleteArmedAt < 3000) {
                        ConfigService.deleteProfile(name)
                        deleteArmedFor = ""
                    } else {
                        deleteArmedFor = name
                        deleteArmedAt = System.currentTimeMillis()
                    }
                }
                bx -= 20
            }
            // duplicate
            RiverIcons.draw(g, "copy", bx, cy + 4, 11, ClientUi.DIM)
            run {
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) {
                    var i = 2
                    var candidate = ConfigService.sanitizeName("$name $i")
                    while (ConfigService.listProfiles().contains(candidate)) {
                        i += 1
                        candidate = ConfigService.sanitizeName("$name $i")
                    }
                    ConfigService.duplicateProfile(name, candidate)
                }
            }
            bx -= 20
            // rename
            RiverIcons.draw(g, "edit", bx, cy + 4, 11, ClientUi.DIM)
            run {
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) {
                    profileEdit = ProfileEdit.RENAME
                    profileRenameTarget = name
                    profileInput = name
                    searchFocused = false
                }
            }
            bx -= 20
            // link the server you are on to this profile
            val here = ServerProfiles.currentHost()
            val linkedHere = here != null && ServerProfiles.hostsFor(name).contains(here)
            val linkHovered = mouseX in (bx - 2)..(bx + 14) && mouseY in (cy + 2)..(cy + 18)
            RiverIcons.draw(g, "wifi", bx, cy + 4, 11, when {
                linkedHere -> ClientUi.ACCENT_B
                linkHovered -> ClientUi.TEXT
                else -> ClientUi.DIM
            })
            run {
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) {
                    profileFlash = ServerProfiles.toggleCurrent(name)
                    profileFlashAt = System.currentTimeMillis()
                }
            }
            bx -= 20
            // use
            if (!isActive) {
                RiverIcons.draw(g, "check", bx, cy + 4, 11, ClientUi.POSITIVE)
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) { RiverRuntime.switchProfile(name) }
            }

            hit(rowX, cy, rowW - 110, 20) { if (!isActive) RiverRuntime.switchProfile(name) }
            cy += 24
        }

        if (editing) {
            val rowX = x + 10
            val rowW = w - 20
            ClientUi.drawListRow(g, rowX, cy, rowW - 54, 20, 0f, true)
            val caret = if (System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
            g.drawString(font, trim(profileInput, rowW - 90) + caret, rowX + 8, cy + 6, ClientUi.TEXT, true)
            val okX = rowX + rowW - 48
            val okHovered = mouseX in okX..(okX + 22) && mouseY in cy..(cy + 20)
            outlineButton(g, okX, cy, 22, 20, "OK", okHovered, primary = true)
            hit(okX, cy, 22, 20) { commitProfileEdit() }
            val cancelX = rowX + rowW - 22
            RiverIcons.draw(g, "x", cancelX + 4, cy + 5, 11, ClientUi.DIM)
            hit(cancelX, cy, 20, 20) { profileEdit = ProfileEdit.NONE }
            cy += 24
        }

        // New profile / export / import row
        val newHovered = mouseX in (x + 10)..(x + 110) && mouseY in cy..(cy + 20)
        outlineButton(g, x + 10, cy, 100, 20, "+ New profile", newHovered)
        hit(x + 10, cy, 100, 20) {
            profileEdit = ProfileEdit.CREATE
            profileInput = ""
            searchFocused = false
        }
        val exportX = x + 116
        val exportHovered = mouseX in exportX..(exportX + 64) && mouseY in cy..(cy + 20)
        outlineButton(g, exportX, cy, 64, 20, "Export", exportHovered)
        hit(exportX, cy, 64, 20) {
            saveNow()
            minecraft?.keyboardHandler?.clipboard = ConfigService.exportActive(RiverRuntime.config)
            profileFlash = "Copied ${ConfigService.activeProfile()} to clipboard"
            profileFlashAt = System.currentTimeMillis()
        }
        val importX = exportX + 70
        val importHovered = mouseX in importX..(importX + 64) && mouseY in cy..(cy + 20)
        outlineButton(g, importX, cy, 64, 20, "Import", importHovered)
        hit(importX, cy, 64, 20) {
            val clip = runCatching { minecraft?.keyboardHandler?.clipboard }.getOrNull()
            val name = clip?.let { ConfigService.importProfile(it) }
            profileFlash = if (name != null) "Imported $name" else "No River profile on the clipboard"
            profileFlashAt = System.currentTimeMillis()
        }
        if (System.currentTimeMillis() - profileFlashAt < 2500) {
            g.drawString(font, trim(profileFlash, x + w - Metrics.PAD - (importX + 72)), importX + 72, cy + 6, ClientUi.POSITIVE, false)
        } else {
            val host = ServerProfiles.currentHost()
            val hint = if (host != null) "Signal icon: load a profile on $host" else "Share a profile via clipboard."
            g.drawString(font, trim(hint, x + w - Metrics.PAD - (importX + 72)), importX + 72, cy + 6, ClientUi.DIM, false)
        }
        cy += 24

        return y + cardH
    }

    private fun commitProfileEdit() {
        val name = ConfigService.sanitizeName(profileInput)
        if (name.isBlank()) {
            profileEdit = ProfileEdit.NONE
            return
        }
        when (profileEdit) {
            ProfileEdit.CREATE -> {
                if (ConfigService.createProfile(name)) {
                    RiverRuntime.switchProfile(name)
                }
            }
            ProfileEdit.RENAME -> ConfigService.renameProfile(profileRenameTarget, name)
            ProfileEdit.NONE -> Unit
        }
        profileEdit = ProfileEdit.NONE
    }

    private fun drawCinematicCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val cardH = Metrics.HEADER_ROW + Metrics.NOTE_ROW + Metrics.TOGGLE_ROW + Metrics.CONTROL_ROW + 8
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "camera", "Cinematic mode", x, y, w)

        /*
          This explanation used to sit to the right of the keybind box on the same line, at
          a hard-coded offset that ran past the card edge on a narrow window or a large GUI
          scale. It reads as a note under the header like every other card's does.
        */
        cy = noteLine(g, x, cy, w, "Hides all modules and the watermark, so the game looks vanilla.")

        val on = ServerSafety.cinematicMode
        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            label = "Cinematic mode",
            value = on,
            id = "cinematic"
        ) {
            ServerSafety.cinematicMode = !ServerSafety.cinematicMode
            saveNow()
        }

        cy = keybindRow(
            g, x, cy, w, mouseX, mouseY,
            label = "Toggle key",
            keyLabel = if (capturingCinematicKey) "press a key"
                else dev.wyz.clientcore.ui.widget.InputNames.keyName(RiverRuntime.config.cinematicKey),
            capturing = capturingCinematicKey,
            id = "cinkey"
        ) { capturingCinematicKey = !capturingCinematicKey }

        return y + cardH
    }

    /**
     * Left Shift + Tab, switchable per context. That chord is also sneak + player list, so
     * wanting the shortcut out of the way on a server while keeping it in singleplayer is
     * legitimate - hence two toggles rather than one.
     */
    private fun drawFriendsCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val cardH = Metrics.HEADER_ROW + Metrics.CONTROL_ROW + Metrics.TOGGLE_ROW * 4 + 8
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "users", "Friends", x, y, w)

        // Rebindable second key; Left Shift is always the modifier.
        cy = keybindRow(
            g, x, cy, w, mouseX, mouseY,
            label = "Open with Left Shift +",
            keyLabel = if (capturingFriendsKey) "press a key"
                else dev.wyz.clientcore.ui.widget.InputNames.keyName(
                    RiverRuntime.config.friendsKey.let { if (it >= 0) it else GLFW.GLFW_KEY_TAB }
                ),
            capturing = capturingFriendsKey,
            id = "friendskey"
        ) { capturingFriendsKey = !capturingFriendsKey }

        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            "Shortcut in singleplayer", RiverRuntime.config.friendsKeyInSingleplayer, "friendskey-sp"
        ) {
            RiverRuntime.config.friendsKeyInSingleplayer = !RiverRuntime.config.friendsKeyInSingleplayer
            saveNow()
        }

        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            "Shortcut on servers", RiverRuntime.config.friendsKeyOnServers, "friendskey-mp"
        ) {
            RiverRuntime.config.friendsKeyOnServers = !RiverRuntime.config.friendsKeyOnServers
            saveNow()
        }

        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            "Message alerts", RiverRuntime.config.friendsMessageToasts, "friends-toasts"
        ) {
            RiverRuntime.config.friendsMessageToasts = !RiverRuntime.config.friendsMessageToasts
            saveNow()
        }

        // Off by default: presence hashes server addresses, so sharing yours is a choice.
        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            "Show friends my server", RiverRuntime.config.friendsShareServer, "friends-share"
        ) {
            RiverRuntime.config.friendsShareServer = !RiverRuntime.config.friendsShareServer
            saveNow()
        }

        return y + cardH
    }

    private fun drawThemeCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val activeKey = RiverTheme.current.key
        val customActive = activeKey == RiverTheme.CUSTOM_KEY
        val custom = RiverTheme.customPalette(
            RiverRuntime.config.themeCustom and 0xFFFFFF,
            RiverRuntime.config.themeCustomB and 0xFFFFFF
        )

        /*
          Chips were laid out on one unbroken row, so the seven of them ran off the right
          edge of the card on a narrow window or at a large GUI scale - the last themes were
          simply unreachable. The rows are measured first so the card can be sized to fit
          however many they wrap onto, rather than the height being a guess that only held
          while every label stayed short.
        */
        val chipRowH = 26
        val contentW = w - Metrics.PAD * 2
        val presets = RiverTheme.pickable
        val labels = presets.map { it.label } + "Custom"
        val chipWidths = labels.map { font.width(it) + 28 }
        val rowsOfChips = ArrayList<MutableList<Int>>()
        run {
            var row = ArrayList<Int>()
            var used = 0
            chipWidths.forEachIndexed { index, cw ->
                if (row.isNotEmpty() && used + cw > contentW) {
                    rowsOfChips.add(row)
                    row = ArrayList()
                    used = 0
                }
                row.add(index)
                used += cw + 6
            }
            if (row.isNotEmpty()) rowsOfChips.add(row)
        }

        val cardH = Metrics.HEADER_ROW + Metrics.NOTE_ROW + rowsOfChips.size * chipRowH +
            (if (customActive) 70 else 0) + Metrics.TOGGLE_ROW + 4
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "palette", "Theme", x, y, w)
        cy = noteLine(
            g, x, cy, w,
            dev.wyz.clientcore.ui.SeasonEvent.menuLine()
                ?: if (Seasons.active() != null) "Accent color for menus and the HUD. Halloween is October only."
            else "Accent color for menus and the HUD."
        )

        rowsOfChips.forEach { row ->
            var chipX = x + Metrics.PAD
            row.forEach { index ->
                val chipW = chipWidths[index]
                val isCustom = index == presets.size
                val key = if (isCustom) RiverTheme.CUSTOM_KEY else presets[index].key
                val selected = key == activeKey
                val hovered = mouseX in chipX..(chipX + chipW) && mouseY in cy..(cy + 20)
                ClientUi.drawListRow(g, chipX, cy, chipW, 20, ClientUi.hover("theme:$key", hovered), selected)
                if (isCustom) {
                    // Custom chip shows the picked two-stop gradient as its swatch.
                    ClientUi.fillRoundedGradient(g, chipX + 7, cy + 6, 8, 8, 4, custom.accentA, custom.accentB)
                } else {
                    ClientUi.fillRounded(g, chipX + 7, cy + 6, 8, 8, 4, presets[index].hudAccent)
                }
                g.drawString(
                    font, labels[index], chipX + 20, cy + 6,
                    if (selected) ClientUi.TEXT else ClientUi.MUTED, true
                )
                val cx = chipX
                hit(cx, cy, chipW, 20) {
                    RiverRuntime.config.theme = key
                    if (isCustom && !themeHexFocused) {
                        themeHexInput = "%06X".format(RiverRuntime.config.themeCustom and 0xFFFFFF)
                    }
                    saveNow()
                }
                chipX += chipW + 6
            }
            cy += chipRowH
        }

        // Two rows (only when custom is active): the gradient start and end stops.
        if (customActive) {
            cy = drawThemeStopRow(g, x, w, cy, mouseX, mouseY, 0, "Start", RiverRuntime.config.themeCustom)
            cy = drawThemeStopRow(g, x, w, cy, mouseX, mouseY, 1, "End", RiverRuntime.config.themeCustomB)
            // Live gradient preview bar across the full width.
            val barX = x + 12
            val barW = w - 24
            if (barW > 20) {
                ClientUi.fillRoundedGradient(g, barX, cy + 2, barW, 14, ClientUi.RADIUS_CARD, custom.accentA, custom.accentB)
                ClientUi.drawRoundedBorder(g, barX, cy + 2, barW, 14, ClientUi.RADIUS_CARD, ClientUi.alpha(ClientUi.BORDER, 0.6f))
            }
            cy += 22
        }

        // Frosted glass behind panels, the HUD and popups. Off for anyone who wants the
        // flat look back, or the last bit of GPU time on a weak machine.
        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            "Glass effect", RiverRuntime.config.glassEffect, "glass"
        ) {
            RiverRuntime.config.glassEffect = !RiverRuntime.config.glassEffect
            saveNow()
        }

        return y + cardH
    }

    /**
     * One custom-gradient stop: label, hex box, Pick button and a solid swatch.
     * [target] is 0 for the start color, 1 for the end color.
     */
    private fun drawThemeStopRow(g: GuiGraphics, x: Int, w: Int, cy: Int, mouseX: Int, mouseY: Int, target: Int, label: String, colorValue: Int): Int {
        val focused = themeHexFocused && themeHexTarget == target
        g.drawString(font, label, x + 12, cy + 5, ClientUi.MUTED, false)
        val boxX = x + 12 + 34
        val boxW = 68
        val boxHovered = mouseX in boxX..(boxX + boxW) && mouseY in cy..(cy + 18)
        ClientUi.drawListRow(g, boxX, cy, boxW, 18, ClientUi.hover("themehex$target", boxHovered), focused)
        g.drawString(font, "#", boxX + 5, cy + 5, ClientUi.MUTED, true)
        val shown = if (focused) themeHexInput else "%06X".format(colorValue and 0xFFFFFF)
        val caret = if (focused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        g.drawString(font, shown + caret, boxX + 12, cy + 5, ClientUi.TEXT, true)
        hit(boxX, cy, boxW, 18) {
            themeHexFocused = true
            themeHexTarget = target
            themeHexInput = "%06X".format(colorValue and 0xFFFFFF)
            searchFocused = false
            RiverRuntime.config.theme = RiverTheme.CUSTOM_KEY
        }
        // Pick button opens the visual colour wheel for this stop.
        val pickX = boxX + boxW + 8
        val pickW = 42
        val pickHovered = mouseX in pickX..(pickX + pickW) && mouseY in cy..(cy + 18)
        outlineButton(g, pickX, cy, pickW, 18, "Pick", pickHovered)
        hit(pickX, cy, pickW, 18) {
            val title = if (target == 0) "Gradient start" else "Gradient end"
            minecraft?.setScreen(RiverColorPickerScreen(this, title, colorValue and 0xFFFFFF,
                onPick = { rgb ->
                    if (target == 0) RiverRuntime.config.themeCustom = rgb and 0xFFFFFF
                    else RiverRuntime.config.themeCustomB = rgb and 0xFFFFFF
                    RiverRuntime.config.theme = RiverTheme.CUSTOM_KEY
                },
                onDone = { saveNow() }
            ))
        }
        // Solid swatch of this stop's current color.
        val swX = pickX + pickW + 8
        val swW = (x + w - 12) - swX
        if (swW > 12) {
            ClientUi.fillRounded(g, swX, cy + 2, swW, 14, ClientUi.RADIUS_CARD, (0xFF shl 24) or (colorValue and 0xFFFFFF))
            ClientUi.drawRoundedBorder(g, swX, cy + 2, swW, 14, ClientUi.RADIUS_CARD, ClientUi.alpha(ClientUi.BORDER, 0.6f))
        }
        return cy + 24
    }

    /** Writes a color to whichever gradient stop the hex box currently edits. */
    private fun setThemeStop(value: Int) {
        if (themeHexTarget == 0) RiverRuntime.config.themeCustom = value and 0xFFFFFF
        else RiverRuntime.config.themeCustomB = value and 0xFFFFFF
        RiverRuntime.config.theme = RiverTheme.CUSTOM_KEY
    }

    private fun applyThemeHex() {
        val cleaned = themeHexInput.trim().removePrefix("#").take(6)
        val value = cleaned.toIntOrNull(16)
        if (value != null && cleaned.length == 6) {
            setThemeStop(value)
            saveNow()
        }
        themeHexFocused = false
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

        searchFocused = false
        // Any click commits the hex field; the box's own hit re-focuses it right after.
        if (themeHexFocused) applyThemeHex()

        // While the popup is up it owns every click: on it, or on the backdrop.
        if (panelOpen && panelRect != null) {
            if (!isOverPanel(mx.toInt(), my.toInt())) {
                closePanel()
                return true
            }
            if (my >= panelBodyTop && my <= panelBodyBottom && settingsPanel.mouseClicked(mx, my)) {
                return true
            }
            for (i in hits.indices.reversed()) {
                if (i < panelHitFloor) break
                val h = hits[i]
                if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                    h.onClick()
                    return true
                }
            }
            // Nothing in the popup wanted it, and nothing behind it may have it.
            return true
        }

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

//? if >=1.21.9 {
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (settingsPanel.mouseDragged(event.x())) return true
        return super.mouseDragged(event, dragX, dragY)
//?} else {
/*    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (settingsPanel.mouseDragged(mouseX)) return true
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
*///?}
    }

//? if >=1.21.9 {
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
//?} else {
/*    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
*///?}
        if (settingsPanel.mouseReleased()) {
            saveNow()
            return true
        }
//? if >=1.21.9 {
        return super.mouseReleased(event)
//?} else {
/*        return super.mouseReleased(mouseX, mouseY, button)
*///?}
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        if (panelOpen && isOverPanel(mouseX.toInt(), mouseY.toInt())) {
            return settingsPanel.mouseScrolled(deltaY)
        }
        if (mouseX >= contentRect[0] && mouseX <= contentRect[2] && mouseY >= contentRect[1] && mouseY <= contentRect[3]) {
            val viewH = contentRect[3] - contentRect[1]
            val maxScroll = (contentHeight - viewH).coerceAtLeast(0)
            val current = scrollByPage.getOrDefault(page, 0)
            scrollByPage[page] = (current - (deltaY * 20).roundToInt()).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val modifiers = event.modifiers()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode
*///?}

        if (settingsPanel.isCapturingKey) {
            if (settingsPanel.keyPressed(key)) return true
        }

        if (capturingFriendsKey) {
            RiverRuntime.config.friendsKey = if (key == GLFW.GLFW_KEY_ESCAPE) -1 else key
            capturingFriendsKey = false
            saveNow()
            return true
        }

        if (capturingCinematicKey) {
            RiverRuntime.config.cinematicKey = if (key == GLFW.GLFW_KEY_ESCAPE) -1 else key
            capturingCinematicKey = false
            saveNow()
            return true
        }

        if (themeHexFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { themeHexFocused = false; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { applyThemeHex(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> {
                    themeHexInput = themeHexInput.dropLast(1)
                    liveApplyThemeHex()
                    return true
                }
            }
            return true
        }

        if (profileEdit != ProfileEdit.NONE) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { profileEdit = ProfileEdit.NONE; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { commitProfileEdit(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { profileInput = profileInput.dropLast(1); return true }
            }
            return true
        }

        if (key == GLFW.GLFW_KEY_F && (modifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            if (panelOpen) closePanel()
            searchFocused = true
            return true
        }

        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { searchFocused = false; searchText = ""; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { searchFocused = false; return true }
                GLFW.GLFW_KEY_BACKSPACE -> { searchText = searchText.dropLast(1); return true }
            }
            return true
        }

        when (key) {
            GLFW.GLFW_KEY_ESCAPE -> {
                if (panelOpen) { closePanel(); return true }
                onClose()
                return true
            }
            GLFW.GLFW_KEY_RIGHT_SHIFT -> { onClose(); return true }
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
        val text = event.codepointAsString()
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val text = codePoint.toString()
*///?}
        if (themeHexFocused) {
            // Only hex digits, max 6.
            val filtered = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (filtered.isNotEmpty() && themeHexInput.length < 6) {
                themeHexInput += filtered.uppercase()
                liveApplyThemeHex()
            }
            return true
        }
        if (profileEdit != ProfileEdit.NONE) {
            if (profileInput.length < 24) profileInput += text
            return true
        }
        if (searchFocused) {
            if (searchText.length < 40) searchText += text
            return true
        }
//? if >=1.21.9 {
        return super.charTyped(event)
//?} else {
/*        return super.charTyped(codePoint, modifiers)
*///?}
    }

    /** Apply the hex as you type once it's a complete 6-digit color, for a live preview. */
    private fun liveApplyThemeHex() {
        val value = themeHexInput.toIntOrNull(16)
        if (value != null && themeHexInput.length == 6) {
            setThemeStop(value)
        }
    }

    /**
     * In a world: vanilla's blurred world (it already moves). Outside a world:
     * the rotating Minecraft panorama with a light veil, so the menu never sits
     * on a flat black screen. Called by renderWithTooltipAndSubtitles before
     * render(); never call it again inside render (one blur per frame).
     */
//? if >=26.1 {
/*    override fun extractBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        // Over the menu this was opened from - the title screen, the pause menu - with a
        // veil, so the menu behind shows through the see-through panel without competing.
        parentShown = ClientUi.drawParentScreen(g, parent, width, height, partialTick)
        if (parentShown) {
            g.fill(0, 0, width, height, 0x8C05060C.toInt())
            return
        }
        if (minecraft?.level == null) {
//? if >=26.1 {
/*            extractPanorama(g, partialTick)
*///?} else {
            renderPanorama(g, partialTick)
//?}
            g.fillGradient(0, 0, width, height, 0x59060810, 0x8C05060C.toInt())
        } else {
//? if >=26.1 {
/*            super.extractBackground(g, mouseX, mouseY, partialTick)
*///?} else {
            super.renderBackground(g, mouseX, mouseY, partialTick)
//?}
        }
    }

    override fun onClose() {
        saveNow()
        minecraft?.setScreen(parent)
    }

    override fun removed() {
        saveNow()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    private fun saveNow() = RiverRuntime.saveConfig()

    private fun trim(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    /** The end of [text] that fits, so a long search shows what is being typed. */
    private fun tail(text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width(t) > maxWidth) t = t.drop(1)
        return t
    }

    private companion object {
        /** The header row on both sides of the window - brand on the left, page title on the right. */
        const val HEADER_H = 34
        const val NAV_ROW = 20
        const val CARD_H = 48
    }
}
