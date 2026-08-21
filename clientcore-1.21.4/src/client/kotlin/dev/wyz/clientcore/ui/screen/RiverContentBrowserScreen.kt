package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.net.ModrinthContent
import dev.wyz.clientcore.net.RemoteIconCache
import dev.wyz.clientcore.resources.RiverGlobalDataPacks
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.min

/**
 * Fullscreen in-game browser for downloading shaders, resource packs and data packs from
 * Modrinth. A card grid with sort + category filters; clicking a card opens a detail view
 * with the project's gallery and description. Shaders land in shaderpacks/, resource packs
 * in resourcepacks/, and data packs in River's global data-pack folder (auto-applied to
 * every singleplayer world).
 */
class RiverContentBrowserScreen(private val parent: Screen?) : Screen(Component.literal("Content")), RiverScreen {

    private val mcClient = Minecraft.getInstance()

    private var tab = ModrinthContent.Type.SHADER
    private var sort = ModrinthContent.Sort.POPULAR
    private val categories = HashSet<String>()

    private var query = ""
    private var searchFocused = false
    private var queryDirtyAt = 0L

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

    private class Region(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Region>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Region(x, y, x + w, y + h, onClick))
    }

    override fun init() {
        if (results.isEmpty() && !loading) runSearch()
    }

    private fun targetDir(type: ModrinthContent.Type): Path = when (type) {
        ModrinthContent.Type.SHADER -> mcClient.gameDirectory.toPath().resolve("shaderpacks")
        ModrinthContent.Type.RESOURCE_PACK -> mcClient.gameDirectory.toPath().resolve("resourcepacks")
        ModrinthContent.Type.DATA_PACK -> RiverGlobalDataPacks.folder()
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

    // ------------------------------------------------------------------ render

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        hits.clear()

        if (queryDirtyAt != 0L && System.currentTimeMillis() - queryDirtyAt > 300L) {
            queryDirtyAt = 0L
            runSearch()
        }

        val pad = 20
        val x = pad
        val top = 16
        val right = width - pad

        // Header
        RiverIcons.draw(g, "box", x, top, 16, ClientUi.ACCENT_B)
        g.drawString(font, "Download content", x + 22, top + 3, ClientUi.TEXT, true)
        val closeSize = 22
        val closeHovered = mouseX in (right - closeSize)..right && mouseY in top..(top + closeSize)
        RiverIcons.draw(g, "x", right - closeSize + 4, top + 3, 12, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(right - closeSize, top - 2, closeSize + 4, closeSize + 4) { onClose() }

        // Content-type tabs
        val tabsY = top + 26
        val tabW = 108
        ModrinthContent.Type.entries.forEachIndexed { i, type ->
            val tx = x + i * (tabW + 6)
            val active = tab == type
            val hovered = mouseX in tx..(tx + tabW) && mouseY in tabsY..(tabsY + 22)
            ClientUi.drawFlatButton(g, font, tx, tabsY, tabW, 22, type.label, hovered, active)
            hit(tx, tabsY, tabW, 22) { switchTab(type) }
        }

        // Sort segmented (right-aligned)
        val sortW = 72
        val sorts = ModrinthContent.Sort.entries
        val sortTotal = sorts.size * (sortW + 4)
        var sxCursor = right - sortTotal + 4
        sorts.forEach { option ->
            val active = sort == option
            val hovered = mouseX in sxCursor..(sxCursor + sortW) && mouseY in tabsY..(tabsY + 22)
            ClientUi.drawFlatButton(g, font, sxCursor, tabsY, sortW, 22, option.label, hovered, active)
            hit(sxCursor, tabsY, sortW, 22) { setSort(option) }
            sxCursor += sortW + 4
        }

        // Search
        val searchY = tabsY + 30
        val searchW = right - x
        drawSearch(g, x, searchY, searchW, mouseX, mouseY)

        // Category chips
        val chipsY = searchY + 28
        var chipX = x
        tab.categories.forEach { category ->
            val label = prettify(category)
            val cw = font.width(label) + 16
            val active = categories.contains(category)
            val hovered = mouseX in chipX..(chipX + cw) && mouseY in chipsY..(chipsY + 18)
            ClientUi.drawFlatButton(g, font, chipX, chipsY, cw, 18, label, hovered, active)
            hit(chipX, chipsY, cw, 18) { toggleCategory(category) }
            chipX += cw + 6
        }

        // Results grid
        val gridTop = chipsY + 26
        val gridBottom = height - 14
        val gridW = right - x
        drawGrid(g, x, gridTop, gridW, gridBottom, mouseX, mouseY)

        if (selected != null) {
            // The detail view is modal: drop every click target from the list underneath so
            // the cards peeking out behind the overlay can't be clicked through.
            hits.clear()
            drawDetail(g, mouseX, mouseY)
        }
    }

    private fun drawSearch(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 22)
        ClientUi.drawListRow(g, x, y, w, 22, ClientUi.hover("content:search", hovered || searchFocused), searchFocused)
        RiverIcons.draw(g, "search", x + 7, y + 6, 11, ClientUi.DIM)
        val placeholder = query.isEmpty() && !searchFocused
        val shown = if (placeholder) "Search Modrinth for ${tab.label.lowercase()}…" else query
        val color = if (placeholder) ClientUi.DIM else ClientUi.TEXT
        val caret = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        g.drawString(font, trim(shown, w - 32) + caret, x + 24, y + 7, color, true)
        hit(x, y, w, 22) { searchFocused = true }
    }

    // ------------------------------------------------------------------ grid

    private fun columnsFor(w: Int): Int = when {
        w >= 1040 -> 3
        w >= 680 -> 2
        else -> 1
    }

    private fun drawGrid(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        if (loading && results.isEmpty()) {
            g.drawString(font, "Loading…", x, top + 4, ClientUi.MUTED, true)
            return
        }
        if (error != null && results.isEmpty()) {
            g.drawString(font, trim(error!!, w), x, top + 4, ClientUi.DIM, true)
            return
        }

        val cols = columnsFor(w)
        val gap = 10
        val scrollbarSpace = 10
        val cardW = (w - scrollbarSpace - (cols - 1) * gap) / cols
        val cardH = 96
        val rows = (results.size + cols - 1) / cols
        val viewH = bottom - top
        val contentH = rows * (cardH + gap)
        scroll = scroll.coerceIn(0, max(0, contentH - viewH))

        g.enableScissor(x, top, x + w, bottom)
        results.forEachIndexed { i, entry ->
            val col = i % cols
            val row = i / cols
            val cx = x + col * (cardW + gap)
            val cy = top - scroll + row * (cardH + gap)
            if (cy + cardH >= top && cy <= bottom) {
                drawCard(g, entry, cx, cy, cardW, cardH, mouseX, mouseY, folderNames(tab))
            }
        }
        g.disableScissor()

        ClientUi.drawScrollbar(g, x + w - 5, top, viewH, contentH, viewH, scroll)
    }

    private fun drawCard(
        g: GuiGraphics, entry: ModrinthContent.Hit, x: Int, y: Int, w: Int, h: Int,
        mouseX: Int, mouseY: Int, folder: Set<String>
    ) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + h)
        ClientUi.drawSectionCard(g, x, y, w, h, ClientUi.hover("content:card:${entry.projectId}", hovered))
        hit(x, y, w, h) { openDetail(entry) }

        val iconSize = 52
        val ix = x + 10
        val iy = y + 10
        drawIcon(g, entry.iconUrl, ix, iy, iconSize)

        val textX = ix + iconSize + 10
        val textW = (x + w) - textX - 12
        g.drawString(font, trim(entry.title, textW), textX, y + 11, ClientUi.TEXT, true)
        g.drawString(font, trim(metaLine(entry), textW), textX, y + 23, ClientUi.DIM, true)

        wrap(entry.description, textW, 2).forEachIndexed { i, line ->
            g.drawString(font, line, textX, y + 37 + i * 10, ClientUi.MUTED, true)
        }

        // Install button (bottom-right of the card). Registered after the card click so it
        // wins the overlap in reversed hit-testing.
        val bw = 66
        val bx = x + w - bw - 10
        val by = y + h - 26
        val bHovered = mouseX in bx..(bx + bw) && mouseY in by..(by + 18)
        when {
            installing.contains(entry.projectId) -> ClientUi.drawFlatButton(g, font, bx, by, bw, 18, "…", false, false)
            isInstalled(entry, folder) -> ClientUi.drawFlatButton(g, font, bx, by, bw, 18, "Installed", false, false)
            else -> {
                ClientUi.drawFlatButton(g, font, bx, by, bw, 18, "Get", bHovered, true)
                hit(bx, by, bw, 18) { download(entry) }
            }
        }
    }

    private fun drawIcon(g: GuiGraphics, url: String, x: Int, y: Int, size: Int) {
        val icon = RemoteIconCache.get(url)
        if (icon != null) {
            drawImageFit(g, icon.id, icon.width, icon.height, x, y, size, size)
        } else {
            ClientUi.drawSectionCard(g, x, y, size, size)
            RiverIcons.draw(g, tab.icon, x + (size - 16) / 2, y + (size - 16) / 2, 16, ClientUi.DIM)
        }
    }

    // ------------------------------------------------------------------ detail

    private fun drawDetail(g: GuiGraphics, mouseX: Int, mouseY: Int) {
        val entry = selected ?: return
        g.fill(0, 0, width, height, 0xCC05060A.toInt())
        // Swallow any click that misses the panel's own controls (registered after this),
        // so nothing behind the overlay reacts.
        hit(0, 0, width, height) { }

        val margin = if (width >= 900) 48 else 24
        val px = margin
        val py = margin
        val pw = width - margin * 2
        val ph = height - margin * 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        // Header: back + title, close
        val backHovered = mouseX in (px + 10)..(px + 64) && mouseY in (py + 10)..(py + 30)
        ClientUi.drawFlatButton(g, font, px + 10, py + 10, 54, 20, "Back", backHovered, false)
        hit(px + 10, py + 10, 54, 20) { closeDetail() }
        g.drawString(font, trim(entry.title, pw - 200), px + 76, py + 15, ClientUi.TEXT, true)
        val closeHovered = mouseX in (px + pw - 26)..(px + pw) && mouseY in py..(py + 26)
        RiverIcons.draw(g, "x", px + pw - 22, py + 13, 12, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(px + pw - 26, py + 8, 26, 26) { closeDetail() }

        g.fill(px + 12, py + 36, px + pw - 12, py + 37, ClientUi.alpha(ClientUi.BORDER, 0.7f))

        val bodyTop = py + 46
        val galleryW = if (pw >= 720) (pw * 0.56f).toInt() else pw - 24
        val galleryX = px + 12
        val galleryImages = detail?.gallery ?: emptyList()
        val galleryH = 200

        // Featured gallery image (or the icon if there is no gallery).
        ClientUi.drawSectionCard(g, galleryX, bodyTop, galleryW, galleryH)
        if (galleryImages.isNotEmpty()) {
            val idx = galleryIndex.coerceIn(0, galleryImages.size - 1)
            val image = RemoteIconCache.get(galleryImages[idx])
            if (image != null) drawImageFit(g, image.id, image.width, image.height, galleryX + 4, bodyTop + 4, galleryW - 8, galleryH - 8)
            else g.drawString(font, "Loading image…", galleryX + 10, bodyTop + 10, ClientUi.DIM, true)

            // Thumbnails
            var thumbX = galleryX
            val thumbY = bodyTop + galleryH + 8
            val thumbSize = 44
            galleryImages.take(8).forEachIndexed { i, imageUrl ->
                if (thumbX + thumbSize <= galleryX + galleryW) {
                    val selectedThumb = i == idx
                    ClientUi.drawSectionCard(g, thumbX, thumbY, thumbSize, thumbSize, 0f, selectedThumb)
                    val thumb = RemoteIconCache.get(imageUrl)
                    if (thumb != null) drawImageFit(g, thumb.id, thumb.width, thumb.height, thumbX + 3, thumbY + 3, thumbSize - 6, thumbSize - 6)
                    hit(thumbX, thumbY, thumbSize, thumbSize) { galleryIndex = i }
                    thumbX += thumbSize + 6
                }
            }
        } else {
            drawIcon(g, entry.iconUrl, galleryX + (galleryW - 72) / 2, bodyTop + (galleryH - 72) / 2, 72)
            if (detailLoading) g.drawString(font, "Loading…", galleryX + 10, bodyTop + 10, ClientUi.DIM, true)
        }

        // Right column: meta + Get.
        if (pw >= 720) {
            val metaX = galleryX + galleryW + 16
            val metaW = px + pw - 12 - metaX
            g.drawString(font, trim("by ${entry.author}", metaW), metaX, bodyTop + 2, ClientUi.MUTED, true)
            g.drawString(font, "${formatCount(entry.downloads)} downloads", metaX, bodyTop + 16, ClientUi.DIM, true)

            val folder = folderNames(tab)
            val bw = min(metaW, 140)
            val by = bodyTop + 40
            val bHovered = mouseX in metaX..(metaX + bw) && mouseY in by..(by + 22)
            when {
                installing.contains(entry.projectId) -> ClientUi.drawFlatButton(g, font, metaX, by, bw, 22, "Installing…", false, false)
                isInstalled(entry, folder) -> ClientUi.drawFlatButton(g, font, metaX, by, bw, 22, "Installed", false, false)
                else -> {
                    ClientUi.drawFlatButton(g, font, metaX, by, bw, 22, "Get", bHovered, true)
                    hit(metaX, by, bw, 22) { download(entry) }
                }
            }
            if (tab == ModrinthContent.Type.DATA_PACK) {
                g.drawString(font, "Applies to all", metaX, by + 30, ClientUi.DIM, true)
                g.drawString(font, "singleplayer worlds.", metaX, by + 40, ClientUi.DIM, true)
            }
        }

        // Description (scrollable), below the gallery / meta.
        val descTop = bodyTop + galleryH + (if (galleryImages.isNotEmpty()) 58 else 12)
        val descBottom = py + ph - 12
        val descX = px + 12
        val descW = pw - 24
        if (descTop < descBottom - 12) {
            if (descWidth != descW || descLines.isEmpty()) {
                descWidth = descW
                descLines = if (descBody.isBlank()) emptyList()
                    else font.split(Component.literal(descBody), descW)
            }
            val lineH = font.lineHeight + 1
            val viewH = descBottom - descTop
            val contentH = descLines.size * lineH
            detailScroll = detailScroll.coerceIn(0, max(0, contentH - viewH))
            g.enableScissor(descX, descTop, descX + descW, descBottom)
            descLines.forEachIndexed { i, line ->
                val ly = descTop - detailScroll + i * lineH
                if (ly + lineH >= descTop && ly <= descBottom) g.drawString(font, line, descX, ly, ClientUi.MUTED, false)
            }
            g.disableScissor()
            ClientUi.drawScrollbar(g, descX + descW - 5, descTop, viewH, contentH, viewH, detailScroll)
        }
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(mouseX, mouseY, button)
        val mx = mouseX
        val my = mouseY
        searchFocused = false
        hits.asReversed().forEach { region ->
            if (mx >= region.x1 && mx <= region.x2 && my >= region.y1 && my <= region.y2) {
                region.onClick()
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val delta = (deltaY * 28).toInt()
        if (selected != null) detailScroll = (detailScroll - delta).coerceAtLeast(0)
        else scroll = (scroll - delta).coerceAtLeast(0)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode
        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { searchFocused = false; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { searchFocused = false; queryDirtyAt = 0L; runSearch(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { query = query.dropLast(1); queryDirtyAt = System.currentTimeMillis(); return true }
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selected != null) { closeDetail(); return true }
            onClose(); return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (searchFocused) {
            if (query.length < 60) { query += codePoint.toString(); queryDirtyAt = System.currentTimeMillis() }
            return true
        }
        return super.charTyped(codePoint, modifiers)
    }

    override fun onClose() {
        mcClient.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    // ------------------------------------------------------------------ helpers

    private fun drawImageFit(g: GuiGraphics, id: net.minecraft.resources.ResourceLocation, iw: Int, ih: Int, boxX: Int, boxY: Int, boxW: Int, boxH: Int) {
        if (iw <= 0 || ih <= 0) return
        val scale = min(boxW.toFloat() / iw, boxH.toFloat() / ih)
        val dw = max(1, (iw * scale).toInt())
        val dh = max(1, (ih * scale).toInt())
        val dx = boxX + (boxW - dw) / 2
        val dy = boxY + (boxH - dh) / 2
        g.blit(RenderType::guiTextured, id, dx, dy, 0f, 0f, dw, dh, iw, ih, iw, ih)
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

    private fun trim(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
