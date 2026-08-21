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
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
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
    private var resetLayoutArmedAt = 0L
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

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
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
            pose.pushPose()
            pose.translate(x + (w - lw) / 2f, y + h - lh - 6f, 0f)
            pose.scale(scale, scale, 1f)
            g.drawString(font, label, 0, 0, 0xFFC2C6D0.toInt(), true)
            pose.popPose()
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
            module.enabled && ServerSafety.isBlockedHere(module) -> {
                desc = "Disabled on this server"
                descColor = ClientUi.WARNING
            }
            module.serverSensitive -> {
                desc = module.description + " (check server rules)"
                descColor = ClientUi.DIM
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

        ClientUi.drawShadow(g, px, py, panelW, ph, ClientUi.RADIUS_PANEL, ClientUi.alpha(0xFF000000.toInt(), 0.5f), 3)
        ClientUi.fillRounded(g, px, py, panelW, ph, ClientUi.RADIUS_PANEL, ClientUi.PANEL)
        ClientUi.drawRoundedBorder(g, px, py, panelW, ph, ClientUi.RADIUS_PANEL, ClientUi.BORDER)
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

    private fun drawSettingsPage(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val scroll = scrollByPage.getOrDefault(page, 0)
        var cy = y + 4 - scroll
        val cardW = w - 10

        cy = drawProfilesCard(g, x, cy, cardW, mouseX, mouseY)
        cy = drawThemeCard(g, x, cy + 8, cardW, mouseX, mouseY)
        cy = drawCinematicCard(g, x, cy + 8, cardW, mouseX, mouseY)
        cy = drawFriendsCard(g, x, cy + 8, cardW, mouseX, mouseY)
        cy = drawHostCard(g, x, cy + 8, cardW, mouseX, mouseY)
        cy = drawSafetyCard(g, x, cy + 8, cardW, mouseX, mouseY)
        cy = drawLayoutCard(g, x, cy + 8, cardW, mouseX, mouseY)

        contentHeight = cy - (y + 4 - scroll) + 12
    }

    private fun sectionHeader(g: GuiGraphics, icon: String, title: String, x: Int, y: Int): Int {
        RiverIcons.draw(g, icon, x + 10, y + 8, 12, ClientUi.ACCENT_B)
        g.drawString(font, title, x + 27, y + 10, ClientUi.TEXT, true)
        return y + 26
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
        val cardH = 30 + 20 + 22 + 16
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "camera", "Cinematic mode", x, y)

        val on = ServerSafety.cinematicMode
        val hovered = mouseX in (x + 10)..(x + w - 60) && mouseY in cy..(cy + 18)
        g.drawString(font, if (on) "On. Your screen is clean until you switch it off." else "Off", x + 12, cy + 4, if (on) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 46, cy + 1, on, hovered, id = "cinematic")
        hit(x + 10, cy, w - 60, 18) {
            ServerSafety.cinematicMode = !ServerSafety.cinematicMode
            saveNow()
        }
        cy += 22

        // Keybind row
        g.drawString(font, "Toggle key", x + 12, cy + 5, ClientUi.MUTED, true)
        val capturing = capturingCinematicKey
        val label = if (capturing) "press a key" else dev.wyz.clientcore.ui.widget.InputNames.keyName(RiverRuntime.config.cinematicKey)
        val boxW = 80
        val boxX = x + 90
        val keyHovered = mouseX in boxX..(boxX + boxW) && mouseY in cy..(cy + 18)
        ClientUi.drawListRow(g, boxX, cy, boxW, 18, ClientUi.hover("cinkey", keyHovered), capturing)
        g.drawString(font, trim(label, boxW - 12), boxX + 6, cy + 5, if (capturing) ClientUi.ACCENT_B else ClientUi.TEXT, true)
        hit(boxX, cy, boxW, 18) { capturingCinematicKey = !capturingCinematicKey }
        g.drawString(font, "Pauses every module and hides the watermark for recording.", x + boxX - x + boxW + 8, cy + 5, ClientUi.DIM, true)

        return y + cardH
    }

    /**
     * Left Shift + Tab, switchable per context. That chord is also sneak + player list, so
     * wanting the shortcut out of the way on a server while keeping it in singleplayer is
     * legitimate - hence two toggles rather than one.
     */
    private fun drawFriendsCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val cardH = 30 + 22 + 20 + 20 + 20 + 20 + 12
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "users", "Friends", x, y)

        // Rebindable second key; Left Shift is always the modifier.
        g.drawString(font, "Open with Left Shift +", x + 12, cy + 5, ClientUi.MUTED, true)
        val keyLabel = if (capturingFriendsKey) "press a key"
            else dev.wyz.clientcore.ui.widget.InputNames.keyName(
                RiverRuntime.config.friendsKey.let { if (it >= 0) it else GLFW.GLFW_KEY_TAB }
            )
        val boxW = 80
        val boxX = x + 130
        val keyHovered = mouseX in boxX..(boxX + boxW) && mouseY in cy..(cy + 18)
        ClientUi.drawListRow(g, boxX, cy, boxW, 18, ClientUi.hover("friendskey", keyHovered), capturingFriendsKey)
        g.drawString(font, trim(keyLabel, boxW - 12), boxX + 6, cy + 5, if (capturingFriendsKey) ClientUi.ACCENT_B else ClientUi.TEXT, true)
        hit(boxX, cy, boxW, 18) { capturingFriendsKey = !capturingFriendsKey }
        cy += 22

        val spOn = RiverRuntime.config.friendsKeyInSingleplayer
        val spHovered = mouseX in (x + 10)..(x + w - 60) && mouseY in cy..(cy + 18)
        g.drawString(font, "Shortcut in singleplayer", x + 12, cy + 5, if (spOn) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 46, cy + 1, spOn, spHovered, id = "friendskey-sp")
        hit(x + 10, cy, w - 60, 18) {
            RiverRuntime.config.friendsKeyInSingleplayer = !spOn
            saveNow()
        }
        cy += 20

        val mpOn = RiverRuntime.config.friendsKeyOnServers
        val mpHovered = mouseX in (x + 10)..(x + w - 60) && mouseY in cy..(cy + 18)
        g.drawString(font, "Shortcut on servers", x + 12, cy + 5, if (mpOn) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 46, cy + 1, mpOn, mpHovered, id = "friendskey-mp")
        hit(x + 10, cy, w - 60, 18) {
            RiverRuntime.config.friendsKeyOnServers = !mpOn
            saveNow()
        }
        cy += 20

        val toastsOn = RiverRuntime.config.friendsMessageToasts
        val toastHovered = mouseX in (x + 10)..(x + w - 60) && mouseY in cy..(cy + 18)
        g.drawString(font, "Message alerts", x + 12, cy + 5, if (toastsOn) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 46, cy + 1, toastsOn, toastHovered, id = "friends-toasts")
        hit(x + 10, cy, w - 60, 18) {
            RiverRuntime.config.friendsMessageToasts = !toastsOn
            saveNow()
        }
        cy += 20

        // Off by default: presence hashes server addresses, so sharing yours is a choice.
        val shareOn = RiverRuntime.config.friendsShareServer
        val shareHovered = mouseX in (x + 10)..(x + w - 60) && mouseY in cy..(cy + 18)
        g.drawString(font, "Show friends my server", x + 12, cy + 5, if (shareOn) ClientUi.TEXT else ClientUi.MUTED, true)
        ClientUi.drawMinimalToggle(g, x + w - 46, cy + 1, shareOn, shareHovered, id = "friends-share")
        hit(x + 10, cy, w - 60, 18) {
            RiverRuntime.config.friendsShareServer = !shareOn
            saveNow()
        }

        return y + cardH
    }

    /**
     * Share the world you are in. Only shown in singleplayer, because there is nothing to
     * host otherwise - a dead button would just raise questions.
     */
    private fun drawHostCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val host = dev.wyz.clientcore.world.WorldHost
        val client = minecraft ?: return y
        if (!host.canHost(client)) return y

        val cardH = 30 + 20 + 22 + 14
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "wifi", "Host this world", x, y)

        val online = host.state == dev.wyz.clientcore.world.WorldHost.State.ONLINE
        val starting = host.state == dev.wyz.clientcore.world.WorldHost.State.STARTING

        val status = when {
            starting -> "Opening your world..."
            online -> "Friends can join at:"
            host.error.isNotEmpty() -> host.error
            else -> "Opens your world so friends can join, no port forwarding."
        }
        val statusColor = when {
            online -> ClientUi.TEXT
            host.error.isNotEmpty() -> ClientUi.WARNING
            else -> ClientUi.MUTED
        }
        g.drawString(font, trim(status, w - 24), x + 12, cy + 4, statusColor, true)
        cy += 14

        // The address is the whole point once live, so it gets the emphasis - and clicking
        // copies it, since retyping an IP by hand is exactly where people slip up.
        if (online && host.address.isNotEmpty()) {
            val addressHovered = mouseX in (x + 10)..(x + w - 90) && mouseY in cy..(cy + 16)
            g.drawString(font, host.address, x + 12, cy + 4, if (addressHovered) ClientUi.ACCENT_B else ClientUi.TEXT, true)
            g.drawString(font, "click to copy", x + 16 + font.width(host.address), cy + 4, ClientUi.DIM, true)
            hit(x + 10, cy, w - 90, 16) {
                minecraft?.keyboardHandler?.clipboard = host.address
                profileFlash = "Copied ${host.address}"
                profileFlashAt = System.currentTimeMillis()
            }
        } else if (host.advice.isNotEmpty()) {
            g.drawString(font, trim(host.advice, w - 24), x + 12, cy + 4, ClientUi.DIM, true)
        }
        cy += 18

        val label = when {
            starting -> "Working..."
            online -> "Stop hosting"
            else -> "Start hosting"
        }
        val bw = 96
        val bx = x + 12
        val bHovered = mouseX in bx..(bx + bw) && mouseY in cy..(cy + 18)
        ClientUi.drawFlatButton(g, font, bx, cy, bw, 18, label, bHovered, !online)
        if (!starting) {
            hit(bx, cy, bw, 18) {
                if (online) host.stopUpnp() else host.startUpnp(client)
            }
        }

        return y + cardH
    }

    private fun drawSafetyCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val rules = ServerSafety.allRules().filterValues { it.isNotEmpty() }
        val serverLabel = ServerSafety.currentServerLabel()
        var rowCount = 1 + rules.size
        if (serverLabel != null) rowCount += 1
        val cardH = 30 + rowCount * 20 + 6
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "shield", "Server rules", x, y)

        g.drawString(font, "Need a module off on one server only? Flip it in that module's settings.", x + 12, cy + 2, ClientUi.DIM, true)
        cy += 20

        if (serverLabel != null) {
            val key = ServerSafety.currentServerKey()
            val disabledHere = ServerSafety.allRules()[key]?.size ?: 0
            g.drawString(font, "You're on ${trim(serverLabel, w - 220)} right now, $disabledHere modules are off here.", x + 12, cy + 2, ClientUi.MUTED, true)
            cy += 20
        }

        rules.forEach { (server, moduleIds) ->
            val label = "${trim(server, w - 220)}: ${moduleIds.size} off"
            g.drawString(font, label, x + 12, cy + 3, ClientUi.MUTED, true)
            val clearX = x + w - 50
            val clearHovered = mouseX in clearX..(clearX + 40) && mouseY in cy..(cy + 16)
            ClientUi.drawFlatButton(g, font, clearX, cy, 40, 16, "Clear", clearHovered, false)
            hit(clearX, cy, 40, 16) {
                moduleIds.toList().forEach { ServerSafety.setBlocked(server, it, false) }
            }
            cy += 20
        }
        return y + cardH
    }

    private fun drawLayoutCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val cardH = 30 + 26 + 6
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "layout", "HUD layout", x, y)

        val openHovered = mouseX in (x + 10)..(x + 120) && mouseY in cy..(cy + 20)
        ClientUi.drawFlatButton(g, font, x + 10, cy, 110, 20, "Open HUD editor", openHovered, true)
        hit(x + 10, cy, 110, 20) {
            saveNow()
            minecraft?.setScreen(RiverHudEditorScreen(this))
        }

        val resetArmed = System.currentTimeMillis() - resetLayoutArmedAt < 3000
        val resetLabel = if (resetArmed) "Confirm reset" else "Reset layout"
        val resetW = 96
        val resetX = x + 130
        val resetHovered = mouseX in resetX..(resetX + resetW) && mouseY in cy..(cy + 20)
        ClientUi.drawFlatButton(g, font, resetX, cy, resetW, 20, resetLabel, resetHovered, false)
        hit(resetX, cy, resetW, 20) {
            if (resetArmed) {
                ModuleRegistry.all.forEach { it.resetPosition() }
                resetLayoutArmedAt = 0
            } else {
                resetLayoutArmedAt = System.currentTimeMillis()
            }
        }

        return y + cardH
    }

    private fun drawThemeCard(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        val activeKey = RiverTheme.current.key
        val customActive = activeKey == RiverTheme.CUSTOM_KEY
        val cardH = 30 + 20 + 26 + (if (customActive) 70 else 0)
        ClientUi.drawSectionCard(g, x, y, w, cardH)
        var cy = sectionHeader(g, "palette", "Theme", x, y)
        g.drawString(font, "Pick an accent color. The whole UI and your HUD follow it.", x + 12, cy + 2, ClientUi.DIM, true)
        cy += 18

        var chipX = x + 12
        RiverTheme.presets.forEach { palette ->
            val chipW = font.width(palette.label) + 28
            val selected = palette.key == activeKey
            val hovered = mouseX in chipX..(chipX + chipW) && mouseY in cy..(cy + 20)
            val anim = ClientUi.hover("theme:${palette.key}", hovered)
            ClientUi.drawListRow(g, chipX, cy, chipW, 20, anim, selected)
            ClientUi.fillRounded(g, chipX + 7, cy + 6, 8, 8, 4, palette.hudAccent)
            g.drawString(font, palette.label, chipX + 20, cy + 6, if (selected) ClientUi.TEXT else ClientUi.MUTED, true)
            val cx = chipX
            hit(cx, cy, chipW, 20) {
                RiverRuntime.config.theme = palette.key
                saveNow()
            }
            chipX += chipW + 6
        }

        // Custom chip: shows the picked two-stop gradient as its swatch.
        val custom = RiverTheme.customPalette(RiverRuntime.config.themeCustom and 0xFFFFFF, RiverRuntime.config.themeCustomB and 0xFFFFFF)
        val chipW = font.width("Custom") + 28
        val selected = customActive
        val hovered = mouseX in chipX..(chipX + chipW) && mouseY in cy..(cy + 20)
        ClientUi.drawListRow(g, chipX, cy, chipW, 20, ClientUi.hover("theme:custom", hovered), selected)
        ClientUi.fillRoundedGradient(g, chipX + 7, cy + 6, 8, 8, 4, custom.accentA, custom.accentB)
        g.drawString(font, "Custom", chipX + 20, cy + 6, if (selected) ClientUi.TEXT else ClientUi.MUTED, true)
        run {
            val cx = chipX
            hit(cx, cy, chipW, 20) {
                RiverRuntime.config.theme = RiverTheme.CUSTOM_KEY
                if (!themeHexFocused) themeHexInput = "%06X".format(RiverRuntime.config.themeCustom and 0xFFFFFF)
                saveNow()
            }
        }
        cy += 26

        // Two rows (only when custom is active): the gradient start and end stops.
        if (customActive) {
            cy = drawThemeStopRow(g, x, w, cy, mouseX, mouseY, 0, "Start", RiverRuntime.config.themeCustom)
            cy = drawThemeStopRow(g, x, w, cy, mouseX, mouseY, 1, "End", RiverRuntime.config.themeCustomB)
            // Live gradient preview bar across the full width.
            val barX = x + 12
            val barW = w - 24
            if (barW > 20) {
                ClientUi.fillRoundedGradient(g, barX, cy + 2, barW, 14, 6, custom.accentA, custom.accentB)
                ClientUi.drawRoundedBorder(g, barX, cy + 2, barW, 14, 6, ClientUi.alpha(ClientUi.BORDER, 0.6f))
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
            ClientUi.fillRounded(g, swX, cy + 2, swW, 14, 6, (0xFF shl 24) or (colorValue and 0xFFFFFF))
            ClientUi.drawRoundedBorder(g, swX, cy + 2, swW, 14, 6, ClientUi.alpha(ClientUi.BORDER, 0.6f))
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

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mx = mouseX
        val my = mouseY
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)

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
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (settingsPanel.mouseDragged(mouseX)) return true
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (settingsPanel.mouseReleased()) {
            saveNow()
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
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

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode

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
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val text = codePoint.toString()
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
        return super.charTyped(codePoint, modifiers)
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
    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (minecraft?.level == null) {
            renderPanorama(g, partialTick)
            g.fillGradient(0, 0, width, height, 0x59060810, 0x8C05060C.toInt())
        } else {
            super.renderBackground(g, mouseX, mouseY, partialTick)
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
