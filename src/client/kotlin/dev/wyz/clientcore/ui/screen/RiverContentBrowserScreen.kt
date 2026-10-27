package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.net.ModrinthContent
import dev.wyz.clientcore.net.RemoteIconCache
import dev.wyz.clientcore.resources.RiverGlobalDataPacks
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverText
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.menu.SeasonalFx
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
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import dev.wyz.clientcore.compat.riverBlit

/**
 * In-game browser for downloading shaders, resource packs and data packs from Modrinth.
 * Shaders land in shaderpacks/, resource packs in resourcepacks/, and data packs in
 * River's global data-pack folder (auto-applied to every singleplayer world).
 *
 * Laid out like the River menu so the two read as one client: a sidebar with what to
 * browse and how to sort it, a header row with the page's title, how many results, the
 * search and the close button, filter chips under it and a grid of cards. Clicking a card
 * opens the project in a popup over the grid - its gallery, its description and Get -
 * the way a module's settings open over the module grid.
 */
class RiverContentBrowserScreen(private val parent: Screen?) : Screen(Component.literal("Content")), RiverScreen {

    private val mcClient = Minecraft.getInstance()

    private var tab = ModrinthContent.Type.SHADER
    private var sort = ModrinthContent.Sort.POPULAR
    private val categories = HashSet<String>()

    private var query = ""
    private var searchFocused = false
    private var queryDirtyAt = 0L
    private var focusChangedAt = 0L

    private var results: List<ModrinthContent.Hit> = emptyList()
    private var loading = false
    private var error: String? = null
    private var scroll = 0
    private var searchToken = 0

    private val installing = HashSet<String>()
    private val installed = HashSet<String>()

    // Detail view state.
    private var selected: ModrinthContent.Hit? = null
    private var detail: ModrinthContent.Detail? = null
    private var detailLoading = false
    private var detailToken = 0
    private var galleryIndex = 0
    private var detailScroll = 0
    private var descLines: List<FormattedCharSequence> = emptyList()
    private var descWidth = -1
    private var descBody = ""

    /** Whether the screen this was opened from was drawn behind it this frame. */
    private var parentShown = false

    private class Region(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Region>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Region(x, y, x + w, y + h, onClick))
    }

    /** A hit clipped to a scrolling area, so a card scrolled out of view cannot be clicked. */
    private fun clippedHit(x: Int, y: Int, w: Int, h: Int, top: Int, bottom: Int, onClick: () -> Unit) {
        val y1 = max(y, top)
        val y2 = min(y + h, bottom)
        if (y2 > y1) hits.add(Region(x, y1, x + w, y2, onClick))
    }

    private fun inside(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    override fun init() {
        if (results.isEmpty() && !loading) runSearch()
    }

    private fun targetDir(type: ModrinthContent.Type): Path = when (type) {
        ModrinthContent.Type.SHADER -> mcClient.gameDirectory.toPath().resolve("shaderpacks")
        ModrinthContent.Type.RESOURCE_PACK -> mcClient.gameDirectory.toPath().resolve("resourcepacks")
        ModrinthContent.Type.DATA_PACK -> RiverGlobalDataPacks.folder()
    }

    /** Where a type installs to, in words, for the sidebar. */
    private fun targetLabel(type: ModrinthContent.Type): String = when (type) {
        ModrinthContent.Type.SHADER -> "shaderpacks folder"
        ModrinthContent.Type.RESOURCE_PACK -> "resourcepacks folder"
        ModrinthContent.Type.DATA_PACK -> "every singleplayer world"
    }

    private fun folderNames(type: ModrinthContent.Type): Set<String> = runCatching {
        targetDir(type).listDirectoryEntries().map { it.name.lowercase() }.toSet()
    }.getOrDefault(emptySet())

    private fun runSearch() {
        loading = true
        error = null
        val token = ++searchToken
        val requestedTab = tab
        ModrinthContent.searchAsync(tab, query, sort, HashSet(categories)) { result ->
            if (mcClient.screen !== this || token != searchToken) return@searchAsync
            loading = false
            result
                .onSuccess { results = it; scroll = 0; if (it.isEmpty()) error = "No ${requestedTab.label.lowercase()} match those filters." }
                .onFailure { results = emptyList(); error = it.message ?: "Search failed." }
        }
    }

    private fun switchTab(next: ModrinthContent.Type) {
        if (tab == next) return
        tab = next
        categories.clear()
        results = emptyList()
        error = null
        scroll = 0
        runSearch()
    }

    private fun toggleCategory(category: String) {
        if (!categories.add(category)) categories.remove(category)
        runSearch()
    }

    private fun setSort(next: ModrinthContent.Sort) {
        if (sort == next) return
        sort = next
        runSearch()
    }

    private fun openDetail(hit: ModrinthContent.Hit) {
        selected = hit
        detail = null
        detailLoading = true
        galleryIndex = 0
        detailScroll = 0
        descLines = emptyList()
        descWidth = -1
        descBody = ""
        val token = ++detailToken
        ModrinthContent.detailAsync(hit.projectId) { result ->
            if (mcClient.screen !== this || token != detailToken) return@detailAsync
            detailLoading = false
            result.onSuccess { detail = it; descBody = stripMarkdown(it.body) }.onFailure { detail = ModrinthContent.Detail("", emptyList(), emptyList()) }
        }
    }

    private fun closeDetail() {
        selected = null
        detail = null
    }

    private fun download(hit: ModrinthContent.Hit) {
        if (installing.contains(hit.projectId)) return
        installing.add(hit.projectId)
        error = null
        val forTab = tab
        ModrinthContent.downloadAsync(forTab, hit, targetDir(forTab)) { result ->
            installing.remove(hit.projectId)
            if (mcClient.screen !== this) return@downloadAsync
            result.onSuccess { installed.add(hit.projectId) }.onFailure { error = "Could not install ${hit.title}: ${it.message}" }
        }
    }

    private fun isInstalled(hit: ModrinthContent.Hit, folder: Set<String>): Boolean =
        installed.contains(hit.projectId) || folder.any { it.contains(hit.slug.lowercase()) }

    private fun setSearchFocus(focused: Boolean) {
        if (searchFocused != focused) focusChangedAt = System.currentTimeMillis()
        searchFocused = focused
    }

    // ------------------------------------------------------------------ surfaces

    /*
      The same surface rules as the River menu: with the glass effect on, everything is
      see-through so what is behind shows; with it off, the surfaces are solid.
    */
    private fun glass(color: Int, glassAlpha: Float): Int = if (ClientUi.glassFrame) ClientUi.alpha(color, glassAlpha) else color

    private fun lay(overlay: Int, base: Int): Int = if (ClientUi.glassFrame) overlay else ClientUi.over(overlay, base)

    private fun sidebarBg(): Int = glass(ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.28f), ClientUi.PANEL), 0.55f)

    private fun wellBg(): Int = if (ClientUi.glassFrame) ClientUi.alpha(0xFF000000.toInt(), 0.38f)
        else ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.32f), ClientUi.PANEL)

    // ------------------------------------------------------------------ render

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        // Over another menu the backdrop is a lighter veil, so that menu still shows.
        if (parentShown) g.fill(0, 0, width, height, 0x8C05060C.toInt()) else ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        hits.clear()

        if (queryDirtyAt != 0L && System.currentTimeMillis() - queryDirtyAt > 300L) {
            queryDirtyAt = 0L
            runSearch()
        }

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
        val chipsY = wy + HEADER_H + 10
        val chipsBottom = drawChips(g, cx, chipsY, cw, mouseX, mouseY)

        val gridTop = chipsBottom + 10
        val gridBottom = wy + wh - 10
        drawGrid(g, cx, gridTop, cw, gridBottom, mouseX, mouseY)

        if (selected != null) {
            // The detail view is modal: drop every click target from the list underneath so
            // the cards peeking out behind the overlay can't be clicked through.
            hits.clear()
            ClientUi.withOverlayLayer(g) { drawDetail(g, mouseX, mouseY) }
        }
    }

    // ------------------------------------------------------------------ sidebar

    private fun drawSidebar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int) {
        val bg = sidebarBg()
        g.fill(x + 1, y + 1, x + w, y + h - 1, bg)
        g.fill(x + w, y + 1, x + w + 1, y + h - 1, ClientUi.BORDER)

        if (SeasonalFx.halloween()) {
            SeasonalFx.drawPumpkinIcon(g, x + 10, y + 10, 13)
            ClientUi.drawGradientTitle(g, font, "Content", x + 28, y + 13)
        } else {
            RiverIcons.draw(g, "box", x + 12, y + 11, 12, ClientUi.ACCENT_B)
            ClientUi.drawGradientTitle(g, font, "Content", x + 30, y + 13)
        }
        g.fill(x + 1, y + HEADER_H - 1, x + w, y + HEADER_H, ClientUi.BORDER)

        var by = y + HEADER_H + 8
        by = navLabel(g, "BROWSE", x, by, w)
        ModrinthContent.Type.entries.forEach { type ->
            val selected = tab == type
            navRow(g, "content:type:${type.name}", type.icon, type.label, x + 6, by, w - 12, mouseX, mouseY, selected, bg)
            if (selected) selectedTypeY = by
            hit(x + 6, by, w - 12, NAV_ROW) { switchTab(type) }
            by += NAV_ROW + 2
        }
        by += 6
        by = navLabel(g, "SORT BY", x, by, w)
        ModrinthContent.Sort.entries.forEach { option ->
            val selected = sort == option
            navRow(g, "content:sort:${option.name}", sortIcon(option), option.label, x + 6, by, w - 12, mouseX, mouseY, selected, bg)
            if (selected) selectedSortY = by
            hit(x + 6, by, w - 12, NAV_ROW) { setSort(option) }
            by += NAV_ROW + 2
        }

        // The bars on the selected rows slide when the selection changes, as in the menu.
        val typeBar = ClientUi.animate("content:typebar", selectedTypeY.toFloat(), 18f).roundToInt()
        g.fill(x + 6, typeBar, x + 8, typeBar + NAV_ROW, ClientUi.ACCENT_A)
        val sortBar = ClientUi.animate("content:sortbar", selectedSortY.toFloat(), 18f).roundToInt()
        g.fill(x + 6, sortBar, x + 8, sortBar + NAV_ROW, ClientUi.ACCENT_A)

        // Where a download goes, at the bottom, so nobody has to go looking for it.
        val iy = y + h - 36
        g.fill(x + 1, iy, x + w, iy + 1, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        RiverIcons.draw(g, "folder", x + 12, iy + 10, 9, ClientUi.DIM)
        g.drawString(font, "Installs to", x + 26, iy + 9, ClientUi.DIM, false)
        g.drawString(font, trim(targetLabel(tab), w - 24), x + 12, iy + 21, ClientUi.MUTED, false)
    }

    private var selectedTypeY = 0
    private var selectedSortY = 0

    private fun sortIcon(option: ModrinthContent.Sort): String = when (option) {
        ModrinthContent.Sort.RELEVANT -> "target"
        ModrinthContent.Sort.POPULAR -> "star"
        ModrinthContent.Sort.NEWEST -> "sparkle"
        ModrinthContent.Sort.UPDATED -> "reset"
    }

    private fun navLabel(g: GuiGraphics, label: String, x: Int, y: Int, w: Int): Int {
        val shown = RiverText.small(label)
        g.drawString(font, shown, x + 12, y + 2, ClientUi.DIM, false)
        val lineX = x + 12 + font.width(shown) + 6
        if (lineX < x + w - 10) g.fill(lineX, y + 6, x + w - 10, y + 7, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        return y + 14
    }

    private fun navRow(
        g: GuiGraphics, key: String, icon: String, label: String, x: Int, y: Int, w: Int,
        mouseX: Int, mouseY: Int, selected: Boolean, bg: Int
    ) {
        val hovered = inside(mouseX, mouseY, x, y, w, NAV_ROW)
        val anim = ClientUi.hover(key, hovered)
        if (selected) {
            g.fill(x, y, x + w, y + NAV_ROW, lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.22f + 0.06f * anim), bg))
        } else if (anim > 0.01f) {
            g.fill(x, y, x + w, y + NAV_ROW, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f * anim), bg))
        }
        val fg = if (selected) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim)
        RiverIcons.draw(g, icon, x + 8, y + 5, 11, if (selected) ClientUi.ACCENT_B else fg)
        g.drawString(font, trim(label, w - 30), x + 24, y + 6, fg, selected)
    }

    // ------------------------------------------------------------------ top bar

    private fun drawTopBar(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        RiverIcons.draw(g, tab.icon, x + 14, y + 11, 12, ClientUi.ACCENT_B)
        RiverText.drawBold(g, font, tab.label, x + 32, y + 13, ClientUi.TEXT)
        val summary = when {
            loading && results.isEmpty() -> "Loading"
            results.isEmpty() -> "Nothing found"
            results.size == 1 -> "1 result"
            else -> "${results.size} results"
        }

        val closeX = x + w - 28
        val closeHovered = inside(mouseX, mouseY, closeX, y + 7, 20, 20)
        val closeAnim = ClientUi.hover("content:close", closeHovered)
        if (closeAnim > 0.01f) g.fill(closeX, y + 7, closeX + 20, y + 27, ClientUi.alpha(ClientUi.PANEL_ALT, closeAnim))
        RiverIcons.draw(g, "x", closeX + 5, y + 12, 10, if (closeHovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, y + 7, 20, 20) { onClose() }
        g.fill(closeX - 5, y + 11, closeX - 4, y + 23, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val searchW = min(260, max(130, w * 2 / 5))
        val sx = closeX - 10 - searchW
        drawSearch(g, sx, y + 7, searchW, mouseX, mouseY)

        val summaryX = x + 32 + RiverText.boldWidth(font, tab.label) + 8
        g.drawString(font, trim(summary, sx - 12 - summaryX), summaryX, y + 13, ClientUi.DIM, false)
        g.fill(x, y + HEADER_H - 1, x + w - 1, y + HEADER_H, ClientUi.BORDER)
    }

    private fun drawSearch(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val hovered = inside(mouseX, mouseY, x, y, w, 20)
        val anim = ClientUi.hover("content:search", hovered || searchFocused)
        g.fill(x, y, x + w, y + 20, wellBg())
        ClientUi.drawRoundedBorder(
            g, x, y, w, 20, 0,
            if (searchFocused) ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
            else ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.BORDER), anim)
        )
        RiverIcons.draw(g, "search", x + 6, y + 6, 9, if (searchFocused) ClientUi.ACCENT_B else ClientUi.DIM)
        if (query.isEmpty()) {
            val placeholder = "Search Modrinth"
            g.drawString(font, trim(placeholder, w - 28), x + 20, y + 6, if (searchFocused) ClientUi.alpha(ClientUi.DIM, 0.6f) else ClientUi.DIM, false)
        }
        val shown = tail(query, w - 28)
        if (shown.isNotEmpty()) g.drawString(font, shown, x + 20, y + 6, ClientUi.TEXT, false)
        if (searchFocused && ((System.currentTimeMillis() - focusChangedAt) / 530) % 2 == 0L) {
            val caretX = x + 20 + font.width(shown) + 1
            g.fill(caretX, y + 5, caretX + 1, y + 15, ClientUi.TEXT)
        }
        hit(x, y, w, 20) { setSearchFocus(true) }
    }

    // ------------------------------------------------------------------ chips

    /** The category filters as a row of chips. Returns the y below them. */
    private fun drawChips(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int): Int {
        var chipX = x
        var chipY = y
        tab.categories.forEach { category ->
            val label = prettify(category)
            val active = categories.contains(category)
            val cw = font.width(label) + (if (active) 26 else 16)
            if (chipX + cw > x + w && chipX > x) {
                chipX = x
                chipY += CHIP_H + 6
            }
            val hovered = inside(mouseX, mouseY, chipX, chipY, cw, CHIP_H)
            val anim = ClientUi.hover("content:chip:$category", hovered)
            if (active) {
                g.fill(chipX, chipY, chipX + cw, chipY + CHIP_H, ClientUi.alpha(ClientUi.ACCENT_A, 0.24f + 0.08f * anim))
                ClientUi.drawRoundedBorder(g, chipX, chipY, cw, CHIP_H, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.7f))
                RiverIcons.draw(g, "check", chipX + 6, chipY + 5, 8, ClientUi.ACCENT_B)
                g.drawString(font, label, chipX + 18, chipY + 5, ClientUi.TEXT, false)
            } else {
                g.fill(chipX, chipY, chipX + cw, chipY + CHIP_H, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.03f + 0.05f * anim))
                ClientUi.drawRoundedBorder(g, chipX, chipY, cw, CHIP_H, 0, ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.18f), ClientUi.BORDER), anim))
                g.drawString(font, label, chipX + 8, chipY + 5, if (hovered) ClientUi.TEXT else ClientUi.MUTED, false)
            }
            hit(chipX, chipY, cw, CHIP_H) { toggleCategory(category) }
            chipX += cw + 6
        }
        if (categories.isNotEmpty()) {
            val label = "Clear"
            val cw = font.width(label) + 12
            if (chipX + cw > x + w) {
                chipX = x
                chipY += CHIP_H + 6
            }
            val hovered = inside(mouseX, mouseY, chipX, chipY, cw, CHIP_H)
            g.drawString(font, label, chipX + 6, chipY + 5, if (hovered) ClientUi.TEXT else ClientUi.DIM, false)
            hit(chipX, chipY, cw, CHIP_H) { categories.clear(); runSearch() }
        }
        return chipY + CHIP_H
    }

    // ------------------------------------------------------------------ grid

    private fun columnsFor(w: Int): Int = when {
        w >= 1040 -> 3
        w >= 560 -> 2
        else -> 1
    }

    private fun drawGrid(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        if (loading && results.isEmpty()) {
            drawSpinner(g, x + w / 2, top + (bottom - top) / 2 - 8)
            centered(g, "Loading ${tab.label.lowercase()}", x + w / 2, top + (bottom - top) / 2 + 8, ClientUi.DIM)
            return
        }
        if (error != null && results.isEmpty()) {
            drawEmpty(g, x, top, w, bottom, tab.icon, "Nothing here", error!!)
            return
        }

        val cols = columnsFor(w)
        val gap = 8
        val scrollbarSpace = 8
        val cardW = (w - scrollbarSpace - (cols - 1) * gap) / cols
        val cardH = CARD_H
        val rows = (results.size + cols - 1) / cols
        val viewH = bottom - top
        val contentH = rows * (cardH + gap)
        scroll = scroll.coerceIn(0, max(0, contentH - viewH))

        val folder = folderNames(tab)
        g.enableScissor(x, top, x + w, bottom)
        results.forEachIndexed { i, entry ->
            val col = i % cols
            val row = i / cols
            val cx = x + col * (cardW + gap)
            val cy = top - scroll + row * (cardH + gap)
            if (cy + cardH >= top && cy <= bottom) {
                drawCard(g, entry, cx, cy, cardW, cardH, top, bottom, mouseX, mouseY, folder)
            }
        }
        g.disableScissor()

        // A message that arrives while results are already showing - a failed install.
        error?.let { message ->
            if (results.isNotEmpty()) {
                val text = trim(message, w - 40)
                val tw = font.width(text) + 24
                val tx = x + (w - tw) / 2
                val ty = bottom - 26
                g.fill(tx, ty, tx + tw, ty + 20, ClientUi.PANEL_ALT)
                ClientUi.drawRoundedBorder(g, tx, ty, tw, 20, 0, ClientUi.alpha(ClientUi.DANGER, 0.55f))
                g.fill(tx, ty, tx + 2, ty + 20, ClientUi.DANGER)
                g.drawString(font, text, tx + 12, ty + 6, ClientUi.TEXT, false)
            }
        }

        // A slim thumb, as in the other River lists.
        if (contentH > viewH) {
            val thumbH = max(16, viewH * viewH / contentH)
            val thumbY = top + ((viewH - thumbH) * scroll / max(1, contentH - viewH))
            g.fill(x + w - 3, thumbY, x + w - 1, thumbY + thumbH, ClientUi.alpha(ClientUi.MUTED, 0.5f))
        }
    }

    private fun drawCard(
        g: GuiGraphics, entry: ModrinthContent.Hit, x: Int, y: Int, w: Int, h: Int, top: Int, bottom: Int,
        mouseX: Int, mouseY: Int, folder: Set<String>
    ) {
        val hovered = inside(mouseX, mouseY, x, y, w, h) && mouseY in top until bottom
        val anim = ClientUi.hover("content:card:${entry.projectId}", hovered)
        val lift = (anim * 2f).roundToInt()
        val cy = y - lift
        ClientUi.drawSectionCard(g, x, cy, w, h, anim)
        clippedHit(x, y, w, h, top, bottom) { openDetail(entry) }

        // The project's icon on a well, so a transparent icon still has an edge.
        val iconSize = 48
        val ix = x + 10
        val iy = cy + 10
        g.fill(ix, iy, ix + iconSize, iy + iconSize, wellBg())
        drawIcon(g, entry.iconUrl, ix, iy, iconSize)
        ClientUi.drawRoundedBorder(g, ix, iy, iconSize, iconSize, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val textX = ix + iconSize + 10
        val textW = (x + w) - textX - 10
        RiverText.drawBold(g, font, trim(entry.title, textW), textX, cy + 11, ClientUi.TEXT)
        g.drawString(font, trim(metaLine(entry), textW), textX, cy + 23, ClientUi.DIM, false)
        wrap(entry.description, textW, 2).forEachIndexed { i, line ->
            g.drawString(font, line, textX, cy + 37 + i * 10, ClientUi.MUTED, false)
        }

        // Install state, bottom right. Registered after the card click so it wins the overlap.
        val bw = 62
        val bx = x + w - bw - 10
        val by = cy + h - 26
        when {
            installing.contains(entry.projectId) -> {
                drawSpinner(g, bx + bw / 2, by + 8)
            }
            isInstalled(entry, folder) -> {
                val label = "Installed"
                val lw = font.width(label)
                RiverIcons.draw(g, "check", bx + bw - lw - 13, by + 4, 9, ClientUi.POSITIVE)
                g.drawString(font, label, bx + bw - lw, by + 5, ClientUi.POSITIVE, false)
            }
            else -> {
                val bHovered = inside(mouseX, mouseY, bx, by, bw, 18) && mouseY in top until bottom
                getButton(g, bx, by, bw, 18, bHovered, "content:get:${entry.projectId}")
                clippedHit(bx, by, bw, 18, top, bottom) { download(entry) }
            }
        }
    }

    /** The accent Get button: the one filled control on a card, because it is the action. */
    private fun getButton(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, key: String) {
        val anim = ClientUi.hover(key, hovered)
        g.fill(x, y, x + w, y + h, ClientUi.mix(ClientUi.ACCENT_A, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.ACCENT_A), anim))
        g.fill(x, y, x + w, y + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
        val label = "Get"
        val contentW = 12 + font.width(label)
        val cx = x + (w - contentW) / 2
        RiverIcons.draw(g, "chevron_down", cx, y + (h - 9) / 2, 9, ClientUi.TEXT)
        g.drawString(font, label, cx + 12, y + (h - 8) / 2, ClientUi.TEXT, false)
    }

    private fun drawIcon(g: GuiGraphics, url: String, x: Int, y: Int, size: Int) {
        val icon = RemoteIconCache.get(url)
        if (icon != null) {
            drawImageFit(g, icon.id, icon.width, icon.height, x, y, size, size)
        } else {
            val s = min(16, size / 2)
            RiverIcons.draw(g, tab.icon, x + (size - s) / 2, y + (size - s) / 2, s, ClientUi.DIM)
        }
    }

    private fun drawEmpty(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, icon: String, title: String, body: String) {
        val cx = x + w / 2
        var cy = top + max(8, (bottom - top) / 2 - 40)
        g.fill(cx - 14, cy, cx + 14, cy + 28, glass(ClientUi.PANEL, 0.5f))
        ClientUi.drawRoundedBorder(g, cx - 14, cy, 28, 28, 0, ClientUi.BORDER)
        RiverIcons.draw(g, icon, cx - 7, cy + 7, 14, ClientUi.MUTED)
        cy += 38
        g.drawString(font, title, cx - font.width(title) / 2, cy, ClientUi.TEXT, true)
        cy += 13
        for (line in font.split(Component.literal(body), min(320, w - 40))) {
            g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.DIM, false)
            cy += 10
        }
    }

    /** Eight squares round a circle, the brightest one walking clockwise. */
    private fun drawSpinner(g: GuiGraphics, cx: Int, cy: Int) {
        val step = ((System.currentTimeMillis() / 90) % 8).toInt()
        for (i in 0 until 8) {
            val angle = Math.PI * 2 * i / 8 - Math.PI / 2
            val px = cx + (cos(angle) * 6).roundToInt()
            val py = cy + (sin(angle) * 6).roundToInt()
            val age = (step - i + 8) % 8
            g.fill(px - 1, py - 1, px + 1, py + 1, ClientUi.alpha(ClientUi.ACCENT_B, (1f - age / 8f).coerceAtLeast(0.15f)))
        }
    }

    // ------------------------------------------------------------------ detail

    /**
     * The project, in a popup over the grid like a module's settings: header with its icon,
     * who made it and Get; the gallery on the left and the description beside it.
     */
    private fun drawDetail(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val entry = selected ?: return
        g.fill(0, 0, width, height, ClientUi.alpha(0xFF05070C.toInt(), 0.62f))
        // Swallow any click that misses the panel's own controls (registered after this),
        // so nothing behind the overlay reacts.
        hit(0, 0, width, height) { }

        val pw = min(680, width - 40)
        val ph = min(420, height - 40)
        val px = (width - pw) / 2
        val py = (height - ph) / 2
        ClientUi.drawPanel(g, px, py, pw, ph, seeThrough = true)
        // Only a hint of the grid behind: this is a page of text, and it has to read.
        if (ClientUi.glassFrame) g.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, ClientUi.alpha(ClientUi.PANEL_SOFT, 0.6f))

        // Header: icon, title, author and downloads; Get, a divider and close on the right.
        g.fill(px + 10, py + 7, px + 36, py + 33, wellBg())
        drawIcon(g, entry.iconUrl, px + 10, py + 7, 26)
        ClientUi.drawRoundedBorder(g, px + 10, py + 7, 26, 26, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val closeX = px + pw - 28
        val closeHovered = inside(mouseX, mouseY, closeX, py + 10, 20, 20)
        if (closeHovered) g.fill(closeX, py + 10, closeX + 20, py + 30, ClientUi.PANEL_ALT)
        RiverIcons.draw(g, "x", closeX + 5, py + 15, 10, if (closeHovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, py + 10, 20, 20) { closeDetail() }
        g.fill(closeX - 5, py + 14, closeX - 4, py + 26, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        val bw = 78
        val bx = closeX - 12 - bw
        val by = py + 10
        val folder = folderNames(tab)
        when {
            installing.contains(entry.projectId) -> {
                drawSpinner(g, bx + bw / 2, by + 10)
            }
            isInstalled(entry, folder) -> {
                val label = "Installed"
                val lw = font.width(label)
                RiverIcons.draw(g, "check", bx + bw - lw - 13, by + 5, 9, ClientUi.POSITIVE)
                g.drawString(font, label, bx + bw - lw, by + 6, ClientUi.POSITIVE, false)
            }
            else -> {
                getButton(g, bx, by, bw, 20, inside(mouseX, mouseY, bx, by, bw, 20), "content:detail:get")
                hit(bx, by, bw, 20) { download(entry) }
            }
        }

        val titleW = bx - 12 - (px + 44)
        RiverText.drawBold(g, font, trim(entry.title, titleW), px + 44, py + 10, ClientUi.TEXT)
        g.drawString(font, trim(metaLine(entry), titleW), px + 44, py + 22, ClientUi.DIM, false)
        g.fill(px + 1, py + 40, px + pw - 1, py + 41, ClientUi.BORDER)

        val bodyTop = py + 50
        val bodyBottom = py + ph - 12
        val twoColumns = pw >= 520
        val galleryX = px + 12
        val galleryW = if (twoColumns) ((pw - 24) * 0.55f).toInt() else pw - 24
        val galleryImages = detail?.gallery ?: emptyList()
        val galleryH = if (twoColumns) min(220, (bodyBottom - bodyTop) - 58) else 150

        // Featured gallery image (or the icon if there is no gallery), on a dark well.
        g.fill(galleryX, bodyTop, galleryX + galleryW, bodyTop + galleryH, wellBg())
        ClientUi.drawRoundedBorder(g, galleryX, bodyTop, galleryW, galleryH, 0, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        var galleryBottom = bodyTop + galleryH
        if (galleryImages.isNotEmpty()) {
            val idx = galleryIndex.coerceIn(0, galleryImages.size - 1)
            val image = RemoteIconCache.get(galleryImages[idx])
            if (image != null) drawImageFit(g, image.id, image.width, image.height, galleryX + 2, bodyTop + 2, galleryW - 4, galleryH - 4)
            else drawSpinner(g, galleryX + galleryW / 2, bodyTop + galleryH / 2)

            // Thumbnails under it; the shown one takes the accent edge.
            var thumbX = galleryX
            val thumbY = bodyTop + galleryH + 8
            val thumbSize = 40
            galleryImages.take(12).forEachIndexed { i, imageUrl ->
                if (thumbX + thumbSize <= galleryX + galleryW) {
                    val isShown = i == idx
                    val thumbHovered = inside(mouseX, mouseY, thumbX, thumbY, thumbSize, thumbSize)
                    g.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, wellBg())
                    val thumb = RemoteIconCache.get(imageUrl)
                    if (thumb != null) drawImageFit(g, thumb.id, thumb.width, thumb.height, thumbX + 2, thumbY + 2, thumbSize - 4, thumbSize - 4)
                    ClientUi.drawRoundedBorder(
                        g, thumbX, thumbY, thumbSize, thumbSize, 0,
                        when {
                            isShown -> ClientUi.alpha(ClientUi.ACCENT_B, 0.9f)
                            thumbHovered -> ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.18f), ClientUi.BORDER)
                            else -> ClientUi.BORDER
                        }
                    )
                    hit(thumbX, thumbY, thumbSize, thumbSize) { galleryIndex = i }
                    thumbX += thumbSize + 6
                }
            }
            galleryBottom = thumbY + thumbSize
        } else {
            drawIcon(g, entry.iconUrl, galleryX + (galleryW - 64) / 2, bodyTop + (galleryH - 64) / 2, 64)
            if (detailLoading) drawSpinner(g, galleryX + 14, bodyTop + 14)
        }

        // Description: beside the gallery when there is room, under it when there is not.
        val descX = if (twoColumns) galleryX + galleryW + 14 else px + 12
        val descTop = if (twoColumns) bodyTop else galleryBottom + 10
        val descW = if (twoColumns) px + pw - 12 - descX else pw - 24
        val descBottom = bodyBottom
        if (twoColumns || descTop < descBottom - 12) {
            var textTop = descTop
            if (tab == ModrinthContent.Type.DATA_PACK) {
                // Where a data pack ends up is not obvious, so it is said before anything else.
                g.fill(descX, textTop, descX + descW, textTop + 18, ClientUi.alpha(ClientUi.ACCENT_A, 0.16f))
                g.fill(descX, textTop, descX + 2, textTop + 18, ClientUi.ACCENT_A)
                g.drawString(font, trim("Applies to all singleplayer worlds", descW - 12), descX + 8, textTop + 5, ClientUi.TEXT, false)
                textTop += 26
            }
            if (descWidth != descW || descLines.isEmpty()) {
                descWidth = descW
                descLines = if (descBody.isBlank()) emptyList()
                    else font.split(Component.literal(descBody), descW - 8)
            }
            if (descLines.isEmpty()) {
                if (detailLoading) drawSpinner(g, descX + 8, textTop + 8)
                else g.drawString(font, "No description.", descX, textTop + 2, ClientUi.DIM, false)
            } else {
                val lineH = font.lineHeight + 1
                val viewH = descBottom - textTop
                val contentH = descLines.size * lineH
                detailScroll = detailScroll.coerceIn(0, max(0, contentH - viewH))
                g.enableScissor(descX, textTop, descX + descW, descBottom)
                descLines.forEachIndexed { i, line ->
                    val ly = textTop - detailScroll + i * lineH
                    if (ly + lineH >= textTop && ly <= descBottom) g.drawString(font, line, descX, ly, ClientUi.MUTED, false)
                }
                g.disableScissor()
                if (contentH > viewH) {
                    val thumbH = max(16, viewH * viewH / contentH)
                    val thumbY = textTop + ((viewH - thumbH) * detailScroll / max(1, contentH - viewH))
                    g.fill(descX + descW - 2, thumbY, descX + descW, thumbY + thumbH, ClientUi.alpha(ClientUi.MUTED, 0.5f))
                }
            }
        }
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
        setSearchFocus(false)
        hits.asReversed().forEach { region ->
            if (mx >= region.x1 && mx <= region.x2 && my >= region.y1 && my <= region.y2) {
                region.onClick()
                return true
            }
        }
//? if >=1.21.9 {
        return super.mouseClicked(event, doubled)
//?} else {
/*        return super.mouseClicked(mouseX, mouseY, button)
*///?}
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val delta = (deltaY * 28).toInt()
        if (selected != null) detailScroll = (detailScroll - delta).coerceAtLeast(0)
        else scroll = (scroll - delta).coerceAtLeast(0)
        return true
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        val modifiers = event.modifiers()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode
*///?}
        if (key == GLFW.GLFW_KEY_F && (modifiers and GLFW.GLFW_MOD_CONTROL) != 0 && selected == null) {
            setSearchFocus(true)
            return true
        }
        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { setSearchFocus(false); return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { setSearchFocus(false); queryDirtyAt = 0L; runSearch(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { query = query.dropLast(1); queryDirtyAt = System.currentTimeMillis(); return true }
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selected != null) { closeDetail(); return true }
            onClose(); return true
        }
//? if >=1.21.9 {
        return super.keyPressed(event)
//?} else {
/*        return super.keyPressed(keyCode, scanCode, modifiers)
*///?}
    }

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
*///?}
        if (searchFocused) {
//? if >=1.21.9 {
            if (query.length < 60) { query += event.codepointAsString(); queryDirtyAt = System.currentTimeMillis() }
//?} else {
/*            if (query.length < 60) { query += codePoint.toString(); queryDirtyAt = System.currentTimeMillis() }
*///?}
            return true
        }
//? if >=1.21.9 {
        return super.charTyped(event)
//?} else {
/*        return super.charTyped(codePoint, modifiers)
*///?}
    }

    /** The screen this was opened from - the title screen - behind it. */
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
        mcClient.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    // ------------------------------------------------------------------ helpers

//? if >=1.21.11 {
    private fun drawImageFit(g: GuiGraphics, id: net.minecraft.resources.Identifier, iw: Int, ih: Int, boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
//?} else {
/*    private fun drawImageFit(g: GuiGraphics, id: net.minecraft.resources.ResourceLocation, iw: Int, ih: Int, boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
*///?}
        if (iw <= 0 || ih <= 0) return
        val scale = min(boxW.toFloat() / iw, boxH.toFloat() / ih)
        val dw = max(1, (iw * scale).toInt())
        val dh = max(1, (ih * scale).toInt())
        val dx = boxX + (boxW - dw) / 2
        val dy = boxY + (boxH - dh) / 2
//? if >=1.21.6 {
        g.riverBlit(id, dx, dy, 0f, 0f, dw, dh, iw, ih, iw, ih)
//?} else {
/*        g.riverBlit(id, dx, dy, 0f, 0f, dw, dh, iw, ih, iw, ih)
*///?}
    }

    private fun metaLine(hit: ModrinthContent.Hit): String = buildString {
        if (hit.author.isNotBlank()) append("by ${hit.author}")
        if (hit.downloads > 0) { if (isNotEmpty()) append("  ·  "); append("${formatCount(hit.downloads)} downloads") }
    }

    private fun formatCount(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }

    private fun prettify(category: String): String =
        category.split("-").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun wrap(text: String, maxWidth: Int, maxLines: Int): List<FormattedCharSequence> {
        if (text.isBlank() || maxWidth <= 0) return emptyList()
        val lines = font.split(Component.literal(text), maxWidth)
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines)
    }

    private fun stripMarkdown(source: String): String = source
        .replace(Regex("(?s)```.*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[#>*_~|]"), "")
        .replace("\r", "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun centered(g: GuiGraphics, text: String, cx: Int, y: Int, color: Int) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false)
    }

    private fun trim(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
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
        const val HEADER_H = 34
        const val NAV_ROW = 20
        const val CHIP_H = 18
        const val CARD_H = 84
    }
}
