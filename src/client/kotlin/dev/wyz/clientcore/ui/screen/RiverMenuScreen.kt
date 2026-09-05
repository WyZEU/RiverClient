package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.config.ConfigService
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.RiverTheme
import dev.wyz.clientcore.ui.widget.SettingsPanel
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?} else {
/**///?}
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The main River Client menu (Right Shift). Fullscreen click GUI with sidebar
 * categories, instant search, module cards and a slide-out settings panel.
 */
class RiverMenuScreen(private val parent: Screen?) : Screen(Component.literal("River Client")), RiverScreen {

    private enum class Page(val label: String, val icon: String, val category: ModuleCategory?) {
        FAVORITES("Favorites", "star", null),
        HUD("HUD", "monitor", ModuleCategory.HUD),
        VISUAL("Visual", "eye", ModuleCategory.VISUAL),
        GAMEPLAY("Gameplay", "compass", ModuleCategory.GAMEPLAY),
        UTILITY("Utility", "wrench", ModuleCategory.UTILITY),
        SETTINGS("Settings", "gear", null)
    }

    private enum class ProfileEdit { NONE, CREATE, RENAME }

    private var page = Page.HUD
    private var pageSwitchedAt = System.currentTimeMillis()
    private val scrollByPage = HashMap<Page, Int>()
    private var contentHeight = 0

    private var searchText = ""
    private var searchFocused = false

    private var selectedModule: Module? = null
    private var panelOpen = false
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
        ClientUi.drawPanel(g, wx, wy, ww, wh)

        val sidebarW = if (ww >= 560) 112 else 92
        drawSidebar(g, wx, wy, sidebarW, wh, mouseX, mouseY)
        g.fill(wx + sidebarW, wy + 8, wx + sidebarW + 1, wy + wh - 8, ClientUi.alpha(ClientUi.BORDER, 0.5f))

        val cx = wx + sidebarW + 12
        val cw = ww - sidebarW - 24
        drawTopBar(g, cx, wy + 10, cw, mouseX, mouseY)

        val contentY = wy + 40
        val contentH = wh - 50
        contentRect = intArrayOf(cx, contentY, cx + cw, contentY + contentH)

        ClientUi.withScissor(g, cx, contentY, cx + cw, contentY + contentH) {
            if (page == Page.SETTINGS && searchText.isBlank()) {
                drawSettingsPage(g, cx, contentY, cw, contentH, mouseX, mouseY)
            } else {
                drawModuleGrid(g, cx, contentY, cw, contentH, mouseX, mouseY)
            }
        }

        drawSlideOutPanel(g, cx, contentY, cw, contentH, mouseX, mouseY)
    }

    // ------------------------------------------------------------------ sidebar

    private fun drawSidebar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        ClientUi.drawGradientTitle(g, font, "River Client", x + 12, y + 14)

        var by = y + 38
        Page.entries.forEach { entry ->
            if (entry == Page.SETTINGS) return@forEach
            by += drawSidebarButton(g, entry, x + 8, by, w - 16, mouseX, mouseY)
        }

        g.fill(x + 12, by + 3, x + w - 12, by + 4, ClientUi.alpha(ClientUi.BORDER, 0.6f))
        by += 10
        drawSidebarButton(g, Page.SETTINGS, x + 8, by, w - 16, mouseX, mouseY)

        // Bottom: Cosmetics popup + HUD editor shortcuts.
        val wardrobeY = y + h - 58
        val wardrobeHovered = mouseX in (x + 8)..(x + w - 8) && mouseY in wardrobeY..(wardrobeY + 22)
        val wardrobeAnim = ClientUi.hover("sb:wardrobe", wardrobeHovered)
        ClientUi.drawSectionCard(g, x + 8, wardrobeY - (wardrobeAnim * 1f).roundToInt(), w - 16, 22, wardrobeAnim, false)
        RiverIcons.draw(g, "shirt", x + 15, wardrobeY + 5 - (wardrobeAnim * 1f).roundToInt(), 11, ClientUi.TEXT)
        g.drawString(font, "Cosmetics", x + 30, wardrobeY + 7 - (wardrobeAnim * 1f).roundToInt(), ClientUi.TEXT, true)
        hit(x + 8, wardrobeY, w - 16, 22) {
            saveNow()
            minecraft?.setScreen(RiverCosmeticsScreen(this))
        }

        val editorY = y + h - 32
        val editorHovered = mouseX in (x + 8)..(x + w - 8) && mouseY in editorY..(editorY + 22)
        val editorAnim = ClientUi.hover("sb:editor", editorHovered)
        ClientUi.drawSectionCard(g, x + 8, editorY - (editorAnim * 1f).roundToInt(), w - 16, 22, editorAnim, false)
        RiverIcons.draw(g, "layout", x + 15, editorY + 5 - (editorAnim * 1f).roundToInt(), 11, ClientUi.TEXT)
        g.drawString(font, "HUD Editor", x + 30, editorY + 7 - (editorAnim * 1f).roundToInt(), ClientUi.TEXT, true)
        hit(x + 8, editorY, w - 16, 22) {
            saveNow()
            minecraft?.setScreen(RiverHudEditorScreen(this))
        }
    }

    private fun drawSidebarButton(g: GuiGraphics, entry: Page, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val selected = page == entry && searchText.isBlank()
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 22)
        val anim = ClientUi.hover("sb:${entry.name}", hovered)
        ClientUi.drawListRow(g, x, y, w, 22, anim, selected)
        val fg = if (selected) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim)
        RiverIcons.draw(g, entry.icon, x + 7, y + 5, 11, if (selected) ClientUi.ACCENT_B else fg)
        g.drawString(font, entry.label, x + 22, y + 7, fg, true)
        hit(x, y, w, 22) { switchPage(entry) }
        return 26
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

    private fun drawTopBar(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val searchW = min(230, w - 130)
        val hovered = mouseX in x..(x + searchW) && mouseY in y..(y + 22)
        ClientUi.drawListRow(g, x, y, searchW, 22, ClientUi.hover("search", hovered), searchFocused)
        RiverIcons.draw(g, "search", x + 7, y + 6, 11, if (searchFocused) ClientUi.ACCENT_B else ClientUi.DIM)
        val caret = if (searchFocused && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        if (searchText.isEmpty() && !searchFocused) {
            g.drawString(font, "Search modules", x + 23, y + 7, ClientUi.DIM, true)
        } else {
            g.drawString(font, trim(searchText, searchW - 34) + caret, x + 23, y + 7, ClientUi.TEXT, true)
        }
        hit(x, y, searchW, 22) { searchFocused = true }

        // Profile chip
        val profileLabel = ConfigService.activeProfile()
        val chipW = font.width(profileLabel) + 34
        val chipX = x + w - chipW - 26
        val chipHovered = mouseX in chipX..(chipX + chipW) && mouseY in y..(y + 22)
        ClientUi.drawListRow(g, chipX, y, chipW, 22, ClientUi.hover("profilechip", chipHovered), page == Page.SETTINGS)
        RiverIcons.draw(g, "folder", chipX + 7, y + 6, 11, ClientUi.ACCENT_B)
        g.drawString(font, profileLabel, chipX + 22, y + 7, ClientUi.MUTED, true)
        hit(chipX, y, chipW, 22) { switchPage(Page.SETTINGS) }

        // Close
        val closeX = x + w - 20
        val closeHovered = mouseX in closeX..(closeX + 20) && mouseY in y..(y + 22)
        RiverIcons.draw(g, "x", closeX + 4, y + 6, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(closeX, y, 20, 22) { onClose() }
    }

    // ------------------------------------------------------------------ module grid

    private fun visibleModules(): List<Module> {
        if (searchText.isNotBlank()) {
            return ModuleRegistry.search(searchText).sortedByDescending { it.favorite }
        }
        return when (page) {
            Page.FAVORITES -> ModuleRegistry.all.filter { it.favorite && it.showInMenu }
            Page.SETTINGS -> emptyList()
            else -> page.category?.let { ModuleRegistry.byCategory(it) } ?: emptyList()
        }
    }

    private fun drawModuleGrid(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val modules = visibleModules()
        if (modules.isEmpty()) {
            val message = when {
                searchText.isNotBlank() -> "No modules match \"${trim(searchText, 120)}\""
                page == Page.FAVORITES -> "Star your favorite modules and they'll show up here"
                else -> "Nothing here yet"
            }
            g.drawString(font, message, x + (w - font.width(message)) / 2, y + h / 2 - 4, ClientUi.DIM, true)
            contentHeight = 0
            return
        }

        val cols = if (w >= 540) 3 else 2
        val gap = 8
        val cardW = (w - (cols - 1) * gap - 10) / cols
        val cardH = 52
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
        val inContent = mouseY >= contentRect[1] && mouseY <= contentRect[3] && !isOverPanel(mouseX, mouseY)
        val hovered = inContent && mouseX in x..(x + w) && mouseY in y..(y + h)
        val anim = ClientUi.hover("card:${module.id}", hovered)
        val lift = (anim * 2f).roundToInt()
        val cy = y - lift
        val selected = panelOpen && selectedModule === module

        if (module.comingSoon) {
            ClientUi.drawModuleCard(g, x, y, w, h, 0f, false, false)
            RiverIcons.draw(g, module.icon, x + 10, y + 9, 16, ClientUi.alpha(ClientUi.DIM, 0.7f))
            g.drawString(font, trim(module.displayName, w - 20), x + 34, y + 9, ClientUi.DIM, true)
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

        RiverIcons.draw(g, module.icon, x + 10, cy + 9, 16, if (module.enabled) ClientUi.TEXT else ClientUi.MUTED)

        val textX = x + 34
        val rightZone = 56
        g.drawString(font, trim(module.displayName, w - textX + x - rightZone), textX, cy + 9, if (module.enabled) ClientUi.TEXT else ClientUi.MUTED, true)
        if (module.keybind >= 0) {
            val nameW = font.width(trim(module.displayName, w - textX + x - rightZone))
            ClientUi.drawTag(g, font, textX + nameW + 5, cy + 6, dev.wyz.clientcore.ui.widget.InputNames.keyName(module.keybind), ClientUi.ACCENT_B)
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
        g.drawString(font, trim(desc, w - (textX - x) - 26), textX, cy + 23, descColor, true)

        // Bottom row: settings gear + favorite star, right-aligned; toggle top right.
        ClientUi.drawMinimalToggle(g, x + w - 44, cy + 8, module.enabled, hovered, id = "card:${module.id}")
        contentHit(x + w - 46, y + 6, 38, 18) { module.enabled = !module.enabled }

        val gearHovered = inContent && mouseX in (x + w - 22)..(x + w - 6) && mouseY in (y + h - 22)..(y + h - 6)
        RiverIcons.draw(g, "gear", x + w - 20, cy + h - 20, 12, if (gearHovered || selected) ClientUi.ACCENT_B else ClientUi.DIM)
        contentHit(x + w - 24, y + h - 24, 20, 20) { openPanelFor(module) }

        val starHovered = inContent && mouseX in (x + w - 42)..(x + w - 26) && mouseY in (y + h - 22)..(y + h - 6)
        val starColor = when {
            module.favorite -> 0xFFFFD46B.toInt()
            starHovered -> ClientUi.MUTED
            else -> ClientUi.alpha(ClientUi.DIM, 0.55f)
        }
        RiverIcons.draw(g, "star", x + w - 40, cy + h - 20, 12, starColor)
        contentHit(x + w - 44, y + h - 24, 20, 20) { module.favorite = !module.favorite }

        // Card body toggles the module (excluding control zones handled above, since those hits are added later... they are added after, so they win on reverse walk being later? We add card hit FIRST so controls added after take precedence in reversed lookup.)
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

    private fun drawSlideOutPanel(g: GuiGraphics, cx: Int, cy: Int, cw: Int, ch: Int, mouseX: Int, mouseY: Int) {
        val anim = ClientUi.animate("menu:panel", if (panelOpen) 1f else 0f, 13f)
        val module = selectedModule
        if (anim < 0.02f || module == null) {
            if (!panelOpen) selectedModule = null
            return
        }

        val panelW = min(252, cw - 30)
        val eased = 1f - (1f - anim) * (1f - anim) * (1f - anim)
        val px = cx + cw - (panelW * eased).roundToInt()
        val py = cy
        val ph = ch

        /*
          This panel slides over the module grid, so it has to read as being in front of it.
          That was a drop shadow; it is now the surface step plus a brighter rule down the
          leading edge. The panel body is PANEL against the grid's PANEL_ALT, so it is
          already a shade darker than what it covers, and the edge marks where it starts.
        */
        ClientUi.fillRounded(g, px, py, panelW, ph, ClientUi.RADIUS_PANEL, ClientUi.PANEL)
        ClientUi.drawRoundedBorder(g, px, py, panelW, ph, ClientUi.RADIUS_PANEL, ClientUi.BORDER)
        g.fill(px, py, px + 1, py + ph, ClientUi.alpha(ClientUi.BORDER_STRONG, 0.55f))
        panelRect = intArrayOf(px, py, px + panelW, py + ph)

        // Header
        RiverIcons.draw(g, module.icon, px + 12, py + 11, 14, ClientUi.TEXT)
        g.drawString(font, trim(module.displayName, panelW - 60), px + 32, py + 10, ClientUi.TEXT, true)
        g.drawString(font, trim(module.description, panelW - 40), px + 12, py + 27, ClientUi.DIM, true)
        val closeHovered = mouseX in (px + panelW - 24)..(px + panelW - 6) && mouseY in (py + 8)..(py + 26)
        RiverIcons.draw(g, "x", px + panelW - 22, py + 10, 11, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(px + panelW - 26, py + 6, 22, 22) { closePanel() }
        g.fill(px + 10, py + 40, px + panelW - 10, py + 41, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        // Body
        val bodyY = py + 44
        val bodyH = ph - 44 - 30
        settingsPanel.render(g, font, px + 2, bodyY, panelW - 4, bodyH, mouseX, mouseY)

        // Footer: reset
        val resetY = py + ph - 25
        val resetHovered = mouseX in (px + 10)..(px + panelW - 10) && mouseY in resetY..(resetY + 18)
        ClientUi.drawFlatButton(g, font, px + 10, resetY, panelW - 20, 18, "Reset module", resetHovered, false)
        hit(px + 10, resetY, panelW - 20, 18) {
            module.resetModuleSettings()
            settingsPanel.resetTransientState()
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
    }

    private fun drawSettingsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val scroll = scrollByPage.getOrDefault(page, 0)
        var cy = y + 4 - scroll
        val cardW = w - 10

        /*
          Ordered by how often it is reached rather than by when it was written, so profiles
          no longer sit between the user and the settings they actually came here to change.
        */
        cy = drawThemeCard(g, x, cy, cardW, mouseX, mouseY)
        cy = drawCinematicCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)
        cy = drawFriendsCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)
        cy = drawProfilesCard(g, x, cy + Metrics.CARD_GAP, cardW, mouseX, mouseY)

        contentHeight = cy - (y + 4 - scroll) + 12
    }

    private fun sectionHeader(g: GuiGraphics, icon: String, title: String, x: Int, y: Int): Int {
        RiverIcons.draw(g, icon, x + 10, y + 8, 12, ClientUi.ACCENT_B)
        g.drawString(font, title, x + 27, y + 10, ClientUi.TEXT, true)
        return y + 26
    }

    /** Explanatory line under a header. Trimmed to the card so long copy cannot overhang it. */
    private fun noteLine(g: GuiGraphics, x: Int, cy: Int, w: Int, text: String): Int {
        g.drawString(font, trim(text, w - Metrics.PAD * 2), x + Metrics.PAD, cy + 2, ClientUi.DIM, true)
        return cy + Metrics.NOTE_ROW
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
        g.drawString(
            font,
            trim(label, rowW - 44),
            rowX,
            cy + 4,
            if (value || hovered) ClientUi.TEXT else ClientUi.MUTED,
            true
        )
        ClientUi.drawMinimalToggle(g, rowX + rowW - Metrics.TOGGLE_INSET, cy + 1, value, hovered, id = id)
        hit(rowX, cy, rowW, Metrics.TOGGLE_ROW - 2) { onToggle() }
        return cy + Metrics.TOGGLE_ROW
    }

    /** Label on the left, a boxed key capture on the right, aligned with every other control. */
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
        g.drawString(font, trim(label, rowW - boxW - 8), rowX, cy + 5, ClientUi.MUTED, true)
        val hovered = mouseX in boxX..(boxX + boxW) && mouseY in cy..(cy + Metrics.BOX_H)
        ClientUi.drawListRow(g, boxX, cy, boxW, Metrics.BOX_H, ClientUi.hover(id, hovered), capturing)
        g.drawString(
            font,
            trim(keyLabel, boxW - 12),
            boxX + 6,
            cy + 5,
            if (capturing) ClientUi.ACCENT_B else ClientUi.TEXT,
            true
        )
        hit(boxX, cy, boxW, Metrics.BOX_H) { onClick() }
        return cy + Metrics.CONTROL_ROW
    }

    private fun drawProfilesCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val profiles = ConfigService.listProfiles()
        val editing = profileEdit != ProfileEdit.NONE
        val rows = profiles.size + 1 + (if (editing) 1 else 0)
        val cardH = 30 + rows * 24 + 6
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "folder", "Profiles", x, y)

        val active = ConfigService.activeProfile()
        profiles.forEach { name ->
            val rowX = x + 10
            val rowW = w - 20
            val isActive = name == active
            val hovered = mouseX in rowX..(rowX + rowW) && mouseY in cy..(cy + 20)
            ClientUi.drawListRow(g, rowX, cy, rowW, 20, ClientUi.hover("prow:$name", hovered), isActive)
            if (isActive) {
                ClientUi.fillRounded(g, rowX + 6, cy + 7, 5, 5, 2, ClientUi.POSITIVE)
            }
            g.drawString(font, trim(name, rowW - 120), rowX + 16, cy + 6, if (isActive) ClientUi.TEXT else ClientUi.MUTED, true)

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
            // use
            if (!isActive) {
                RiverIcons.draw(g, "check", bx, cy + 4, 11, ClientUi.POSITIVE)
                val bxx = bx
                hit(bxx - 2, cy + 2, 16, 16) { RiverRuntime.switchProfile(name) }
            }

            hit(rowX, cy, rowW - 90, 20) { if (!isActive) RiverRuntime.switchProfile(name) }
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
            ClientUi.drawFlatButton(g, font, okX, cy, 22, 20, "OK", okHovered, true)
            hit(okX, cy, 22, 20) { commitProfileEdit() }
            val cancelX = rowX + rowW - 22
            RiverIcons.draw(g, "x", cancelX + 4, cy + 5, 11, ClientUi.DIM)
            hit(cancelX, cy, 20, 20) { profileEdit = ProfileEdit.NONE }
            cy += 24
        }

        // New profile / export / import row
        val newHovered = mouseX in (x + 10)..(x + 110) && mouseY in cy..(cy + 20)
        ClientUi.drawFlatButton(g, font, x + 10, cy, 100, 20, "+ New profile", newHovered, false)
        hit(x + 10, cy, 100, 20) {
            profileEdit = ProfileEdit.CREATE
            profileInput = ""
            searchFocused = false
        }
        val exportX = x + 116
        val exportHovered = mouseX in exportX..(exportX + 64) && mouseY in cy..(cy + 20)
        ClientUi.drawFlatButton(g, font, exportX, cy, 64, 20, "Export", exportHovered, false)
        hit(exportX, cy, 64, 20) {
            saveNow()
            minecraft?.keyboardHandler?.clipboard = ConfigService.exportActive(RiverRuntime.config)
            profileFlash = "Copied ${ConfigService.activeProfile()} to clipboard"
            profileFlashAt = System.currentTimeMillis()
        }
        val importX = exportX + 70
        val importHovered = mouseX in importX..(importX + 64) && mouseY in cy..(cy + 20)
        ClientUi.drawFlatButton(g, font, importX, cy, 64, 20, "Import", importHovered, false)
        hit(importX, cy, 64, 20) {
            val clip = runCatching { minecraft?.keyboardHandler?.clipboard }.getOrNull()
            val name = clip?.let { ConfigService.importProfile(it) }
            profileFlash = if (name != null) "Imported $name" else "No River profile on the clipboard"
            profileFlashAt = System.currentTimeMillis()
        }
        if (System.currentTimeMillis() - profileFlashAt < 2500) {
            g.drawString(font, trim(profileFlash, w - 210), importX + 70, cy + 6, ClientUi.POSITIVE, true)
        } else {
            g.drawString(font, "Share a profile via clipboard.", importX + 70, cy + 6, ClientUi.DIM, true)
        }
        cy += 24

        return y + 30 + (profiles.size + 1 + (if (editing) 1 else 0)) * 24 + 6
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
        val cardH = 26 + Metrics.NOTE_ROW + Metrics.TOGGLE_ROW + Metrics.CONTROL_ROW + 8
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "camera", "Cinematic mode", x, y)

        /*
          This explanation used to sit to the right of the keybind box on the same line, at
          a hard-coded offset that ran past the card edge on a narrow window or a large GUI
          scale. It reads as a note under the header like every other card's does.
        */
        cy = noteLine(g, x, cy, w, "Pauses every module and hides the watermark for recording.")

        val on = ServerSafety.cinematicMode
        cy = toggleRow(
            g, x, cy, w, mouseX, mouseY,
            label = if (on) "On, your screen stays clean until you switch it off" else "Off",
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
        val cardH = 26 + Metrics.CONTROL_ROW + Metrics.TOGGLE_ROW * 4 + 8
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "users", "Friends", x, y)

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
        val labels = RiverTheme.presets.map { it.label } + "Custom"
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

        val cardH = 26 + Metrics.NOTE_ROW + rowsOfChips.size * chipRowH +
            (if (customActive) 70 else 0) + 4
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "palette", "Theme", x, y)
        cy = noteLine(g, x, cy, w, "Pick an accent color. The whole UI and your HUD follow it.")

        rowsOfChips.forEach { row ->
            var chipX = x + Metrics.PAD
            row.forEach { index ->
                val chipW = chipWidths[index]
                val isCustom = index == RiverTheme.presets.size
                val key = if (isCustom) RiverTheme.CUSTOM_KEY else RiverTheme.presets[index].key
                val selected = key == activeKey
                val hovered = mouseX in chipX..(chipX + chipW) && mouseY in cy..(cy + 20)
                ClientUi.drawListRow(g, chipX, cy, chipW, 20, ClientUi.hover("theme:$key", hovered), selected)
                if (isCustom) {
                    // Custom chip shows the picked two-stop gradient as its swatch.
                    ClientUi.fillRoundedGradient(g, chipX + 7, cy + 6, 8, 8, 4, custom.accentA, custom.accentB)
                } else {
                    ClientUi.fillRounded(g, chipX + 7, cy + 6, 8, 8, 4, RiverTheme.presets[index].hudAccent)
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

        return y + cardH
    }

    /**
     * One custom-gradient stop: label, hex box, Pick button and a solid swatch.
     * [target] is 0 for the start color, 1 for the end color.
     */
    private fun drawThemeStopRow(g: GuiGraphics, x: Int, w: Int, cy: Int, mouseX: Int, mouseY: Int, target: Int, label: String, colorValue: Int): Int {
        val focused = themeHexFocused && themeHexTarget == target
        g.drawString(font, label, x + 12, cy + 5, ClientUi.MUTED, true)
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
        ClientUi.drawFlatButton(g, font, pickX, cy, pickW, 18, "Pick", pickHovered, false)
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

//? if >=1.21.11 {
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

        // Slide-out panel body gets first pick when open.
        if (panelOpen && isOverPanel(mx.toInt(), my.toInt())) {
            // Header/footer hits are in the global list; body handled by the panel.
            val bodyTop = (panelRect?.get(1) ?: 0) + 44
            val bodyBottom = (panelRect?.get(3) ?: 0) - 30
            if (my >= bodyTop && my <= bodyBottom && settingsPanel.mouseClicked(mx, my)) {
                return true
            }
        }

        hits.asReversed().forEach { h ->
            if (mx >= h.x1 && mx <= h.x2 && my >= h.y1 && my <= h.y2) {
                h.onClick()
                return true
            }
        }

        // Click on empty space closes the slide-out.
        if (panelOpen && !isOverPanel(mx.toInt(), my.toInt())) {
            closePanel()
            return true
        }
//? if >=1.21.11 {
        return super.mouseClicked(event, doubled)
//?} else {
/*        return super.mouseClicked(mouseX, mouseY, button)
*///?}
    }

//? if >=1.21.11 {
    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (settingsPanel.mouseDragged(event.x())) return true
        return super.mouseDragged(event, dragX, dragY)
//?} else {
/*    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (settingsPanel.mouseDragged(mouseX)) return true
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
*///?}
    }

//? if >=1.21.11 {
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
//?} else {
/*    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
*///?}
        if (settingsPanel.mouseReleased()) {
            saveNow()
            return true
        }
//? if >=1.21.11 {
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

//? if >=1.21.11 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
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
//? if >=1.21.11 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

//? if >=1.21.11 {
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
//? if >=1.21.11 {
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
}
