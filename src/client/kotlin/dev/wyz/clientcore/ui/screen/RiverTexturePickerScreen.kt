//? if >=1.21.11 && <26.1 {
package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.net.RemoteIconCache
import dev.wyz.clientcore.resources.TextureCategories
import dev.wyz.clientcore.resources.TextureCategories.Category
import dev.wyz.clientcore.resources.TextureCategories.Group
import dev.wyz.clientcore.resources.TextureMixer
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import dev.wyz.clientcore.ui.RiverText
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import dev.wyz.clientcore.compat.riverBlit

/**
 * The texture picker, opened from the vanilla resource pack screen. Pick a category on the
 * left, and every installed pack's version of it is laid out in the middle; click one to use
 * it for that category. Picks are staged until Apply, which builds the River Picks pack
 * (TextureMixer) and reloads once. Presets save a whole set of picks.
 */
class RiverTexturePickerScreen(private val parent: Screen?, startSearch: String = "") : Screen(Component.literal("Texture picker")), RiverScreen {

    private var group = Group.PVP
    private var category: Category = TextureCategories.fixed.first()

    /** Category id to pack id, as shown here; [TextureMixer.picks] is what is applied. */
    private val staged = LinkedHashMap<String, String>()

    /** The pack the inspector shows, per category. */
    private val focus = HashMap<String, String>()

    private var favoritesOnly = false
    private var search = ""
    private var categorySearch = startSearch

    /** Which search box takes typing. */
    private enum class Field { NONE, PACKS, CATEGORIES }
    private var focused = Field.NONE
    private var showPresets = false
    private var gridScroll = 0
    private var maxGridScroll = 0
    private var gridRect = IntArray(4)
    private var sidebarScroll = 0
    private var maxSidebarScroll = 0
    private var sidebarRect = IntArray(4)

    private var status = ""
    private var statusIsError = false
    private var parentShown = false
    private val hits = ArrayList<Hit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val onClick: () -> Unit)

    init {
        TextureMixer.rescan()
        staged.putAll(TextureMixer.picks)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, if (parentShown) 0x8C05060C.toInt() else 0x66040507)
        ClientUi.beginFrame()
        hits.clear()

        val pw = min(width - 20, 900)
        val ph = height - 20
        val wx = (width - pw) / 2
        val wy = 10
        ClientUi.drawPanel(g, wx, wy, pw, ph, seeThrough = parentShown)

        // Header.
        ClientUi.drawGradientTitle(g, font, "Texture picker", wx + 12, wy + 13)
        val closeX = wx + pw - 26
        RiverIcons.draw(g, "x", closeX + 5, wy + 12, 10, if (inside(mouseX, mouseY, closeX, wy + 7, 20, 20)) ClientUi.TEXT else ClientUi.MUTED)
        hit(closeX, wy + 7, 20, 20) { onClose() }
        g.fill(wx + 1, wy + HEADER_H - 1, wx + pw - 1, wy + HEADER_H, ClientUi.BORDER)

        val bodyTop = wy + HEADER_H
        val bodyBottom = wy + ph - FOOTER_H
        drawSidebar(g, wx, bodyTop, bodyBottom, mouseX, mouseY)
        val inspectorX = wx + pw - INSPECTOR_W
        g.fill(inspectorX - 1, bodyTop, inspectorX, bodyBottom, ClientUi.BORDER)
        drawCenter(g, wx + SIDEBAR_W + 13, bodyTop, inspectorX - 13 - (wx + SIDEBAR_W + 13), bodyBottom, mouseX, mouseY)
        drawInspector(g, inspectorX, bodyTop, INSPECTOR_W, bodyBottom, mouseX, mouseY)
        drawFooter(g, wx, bodyBottom, pw, mouseX, mouseY)
    }

    // ------------------------------------------------------------------ sidebar

    private fun drawSidebar(g: GuiGraphics, x: Int, top: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        g.fill(x + SIDEBAR_W, top, x + SIDEBAR_W + 1, bottom, ClientUi.BORDER)
        val tabW = (SIDEBAR_W - 24) / 3
        Group.entries.forEachIndexed { index, entry ->
            // Five groups: three tabs on the first row, two on the second.
            val tx = x + 8 + (index % 3) * (tabW + 4)
            val ty = top + 8 + (index / 3) * 22
            ClientUi.drawFlatButton(g, font, tx, ty, tabW, 18, entry.label, inside(mouseX, mouseY, tx, ty, tabW, 18), primary = group == entry && categorySearch.isBlank())
            hit(tx, ty, tabW, 18) {
                categorySearch = ""
                if (group != entry) {
                    group = entry
                    sidebarScroll = 0
                    TextureCategories.every().firstOrNull { it.group == entry }?.let { selectCategory(it) }
                }
            }
        }

        // Search over every category, blocks included.
        val sx = x + 8
        val sy = top + 54
        val sw = SIDEBAR_W - 16
        drawSearchBox(g, sx, sy, sw, categorySearch, "Search blocks and items", focused == Field.CATEGORIES)
        hit(sx, sy, sw, 18) { focused = Field.CATEGORIES }

        val sources = TextureMixer.sources
        val listTop = top + 80
        val entries = if (categorySearch.isBlank()) TextureCategories.every().filter { it.group == group }
        else TextureCategories.every().filter { it.matches(categorySearch.trim()) }
        sidebarRect = intArrayOf(x, listTop, x + SIDEBAR_W, bottom)
        maxSidebarScroll = max(0, entries.size * ROW_H - (bottom - listTop))
        sidebarScroll = sidebarScroll.coerceIn(0, maxSidebarScroll)
        if (entries.isEmpty()) {
            val message = if (group != Group.PVP && group != Group.EXTRAS && TextureCategories.perTexture.isEmpty()) "Reading your packs..." else "Nothing matches."
            g.drawString(font, message, x + 10, listTop + 4, ClientUi.MUTED, false)
            return
        }

        ClientUi.withScissor(g, x, listTop, x + SIDEBAR_W, bottom) {
            // Only the rows in view are drawn: the block tab has around a thousand.
            val first = sidebarScroll / ROW_H
            val last = min(entries.size - 1, (sidebarScroll + bottom - listTop) / ROW_H)
            for (index in first..last) {
                val entry = entries[index]
                val y = listTop + index * ROW_H - sidebarScroll
                val selected = entry == category
                val hovered = inside(mouseX, mouseY, x + 4, y, SIDEBAR_W - 8, ROW_H) && mouseY in listTop until bottom
                ClientUi.drawListRow(g, x + 4, y, SIDEBAR_W - 8, ROW_H - 2, ClientUi.hover("tex:cat:${entry.id}", hovered), selected)
                val pickedId = staged[entry.id]
                val shown = sources?.firstOrNull { it.id == (pickedId ?: TextureMixer.VANILLA) }
                drawPreview(g, shown, entry, x + 9, y + 6, 16)
                g.drawString(font, trim(entry.label, SIDEBAR_W - 70), x + 31, y + 4, ClientUi.TEXT, false)
                val count = sources?.count { it.files.containsKey(entry.id) }
                if (count != null) {
                    val text = count.toString()
                    g.drawString(font, text, x + SIDEBAR_W - 12 - font.width(text), y + 4, ClientUi.DIM, false)
                }
                val now = pickedId?.let { id -> sources?.firstOrNull { it.id == id }?.name ?: "Missing pack" } ?: "Pack order"
                g.drawString(font, trim(now, SIDEBAR_W - 44), x + 31, y + 15, if (pickedId != null) ClientUi.ACCENT_B else ClientUi.DIM, false)
                val y0 = max(y, listTop)
                val y1 = min(y + ROW_H - 2, bottom)
                if (y1 > y0) hits.add(Hit(x + 4, y0, SIDEBAR_W - 8, y1 - y0) { selectCategory(entry) })
            }
        }
        if (maxSidebarScroll > 0) ClientUi.drawScrollbar(g, x + SIDEBAR_W - 4, listTop, bottom - listTop, entries.size * ROW_H, bottom - listTop, sidebarScroll)
    }

    private fun drawSearchBox(g: GuiGraphics, x: Int, y: Int, w: Int, text: String, placeholder: String, active: Boolean) {
        g.fill(x, y, x + w, y + 18, ClientUi.PANEL)
        ClientUi.drawRoundedBorder(g, x, y, w, 18, 0, if (active) ClientUi.ACCENT_B else ClientUi.BORDER)
        RiverIcons.draw(g, "search", x + 5, y + 5, 8, ClientUi.DIM)
        val shown = if (text.isEmpty() && !active) placeholder else text + if (active && System.currentTimeMillis() / 500 % 2 == 0L) "_" else ""
        g.drawString(font, trim(shown, w - 22), x + 17, y + 5, if (text.isEmpty()) ClientUi.DIM else ClientUi.TEXT, false)
    }

    private fun selectCategory(next: Category) {
        category = next
        gridScroll = 0
    }

    // ------------------------------------------------------------------ grid

    private fun drawCenter(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        val sources = TextureMixer.sources
        val all = sources?.filter { it.files.containsKey(category.id) }.orEmpty()
        g.drawString(font, category.label, x, top + 10, ClientUi.TEXT, false)
        val now = staged[category.id]?.let { id -> sources?.firstOrNull { it.id == id }?.name ?: "Missing pack" } ?: "Pack order"
        g.drawString(font, trim("${all.size} packs. In use: $now", w - 160), x, top + 23, ClientUi.DIM, false)

        // Search over the packs in this category.
        val sw = 150
        val sx = x + w - sw
        val sy = top + 8
        drawSearchBox(g, sx, sy, sw, search, "Search packs", focused == Field.PACKS)
        hit(sx, sy, sw, 18) { focused = Field.PACKS }

        // Filter chips.
        val chipY = top + 38
        listOf("All" to false, "Favorites" to true).fold(x) { cx, (label, favorites) ->
            val cw = font.width(label) + 16
            ClientUi.drawFlatButton(g, font, cx, chipY, cw, 16, label, inside(mouseX, mouseY, cx, chipY, cw, 16), primary = favoritesOnly == favorites)
            hit(cx, chipY, cw, 16) { favoritesOnly = favorites; gridScroll = 0 }
            cx + cw + 6
        }

        val gridTop = chipY + 24
        gridRect = intArrayOf(x, gridTop, x + w, bottom)
        if (sources == null) {
            g.drawString(font, "Reading your packs...", x, gridTop + 6, ClientUi.MUTED, false)
            return
        }
        val shown = all.filter { source ->
            (!favoritesOnly || source.id in TextureMixer.favorites) &&
                (search.isBlank() || source.name.contains(search.trim(), ignoreCase = true))
        }
        if (shown.isEmpty()) {
            val message = when {
                all.size <= 1 -> "None of your packs change this. Add packs in the resource pack screen."
                favoritesOnly -> "No favorites here yet. Pick a pack and press Favorite."
                else -> "No packs match \"$search\"."
            }
            g.drawString(font, trim(message, w), x, gridTop + 6, ClientUi.MUTED, false)
            return
        }

        val columns = max(1, (w + CARD_GAP) / (CARD_W + CARD_GAP))
        val cardW = (w - (columns - 1) * CARD_GAP - 8) / columns
        val rows = (shown.size + columns - 1) / columns
        val content = rows * (CARD_H + CARD_GAP)
        val view = bottom - 8 - gridTop
        maxGridScroll = max(0, content - view)
        gridScroll = gridScroll.coerceIn(0, maxGridScroll)

        ClientUi.withScissor(g, x, gridTop, x + w, bottom - 8) {
            shown.forEachIndexed { index, source ->
                val cx = x + (index % columns) * (cardW + CARD_GAP)
                val cy = gridTop + (index / columns) * (CARD_H + CARD_GAP) - gridScroll
                if (cy + CARD_H < gridTop || cy > bottom) return@forEachIndexed
                val picked = staged[category.id] == source.id
                val hovered = inside(mouseX, mouseY, cx, cy, cardW, CARD_H) && mouseY in gridTop until bottom - 8
                ClientUi.drawSectionCard(g, cx, cy, cardW, CARD_H, ClientUi.hover("tex:card:${source.id}", hovered), picked)
                drawPreview(g, source, category, cx + (cardW - 48) / 2, cy + 10, 48)
                val name = trim(source.name, cardW - 10)
                g.drawString(font, name, cx + (cardW - font.width(name)) / 2, cy + CARD_H - 18, if (picked) ClientUi.TEXT else ClientUi.MUTED, false)
                if (source.id in TextureMixer.favorites) RiverIcons.draw(g, "star", cx + cardW - 13, cy + 5, 8, ClientUi.WARNING)
                val y0 = max(cy, gridTop)
                val y1 = min(cy + CARD_H, bottom - 8)
                if (y1 > y0) hits.add(Hit(cx, y0, cardW, y1 - y0) {
                    staged[category.id] = source.id
                    focus[category.id] = source.id
                    status = ""
                    statusIsError = false
                })
            }
        }
        if (maxGridScroll > 0) ClientUi.drawScrollbar(g, x + w - 4, gridTop, view, content, view, gridScroll)
    }

    // ------------------------------------------------------------------ inspector and presets

    private fun drawInspector(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        val tabW = (w - 20) / 2
        listOf("Inspector" to false, "Presets" to true).forEachIndexed { index, (label, presets) ->
            val tx = x + 8 + index * (tabW + 4)
            ClientUi.drawFlatButton(g, font, tx, top + 8, tabW, 18, label, inside(mouseX, mouseY, tx, top + 8, tabW, 18), primary = showPresets == presets)
            hit(tx, top + 8, tabW, 18) { showPresets = presets }
        }
        if (showPresets) drawPresets(g, x + 10, top + 36, w - 20, bottom, mouseX, mouseY)
        else drawDetails(g, x + 10, top + 36, w - 20, mouseX, mouseY)
    }

    private fun drawDetails(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val sources = TextureMixer.sources ?: return
        val focusId = focus[category.id] ?: staged[category.id] ?: TextureMixer.VANILLA
        val source = sources.firstOrNull { it.id == focusId && it.files.containsKey(category.id) } ?: return
        val size = min(w, 112)
        g.fill(x, y, x + w, y + size + 8, ClientUi.PANEL)
        drawPreview(g, source, category, x + (w - size) / 2 + 4, y + 4, size - 8)

        var cy = y + size + 16
        g.drawString(font, trim(source.name, w), x, cy, ClientUi.TEXT, false)
        cy += 12
        val count = source.files[category.id].orEmpty().count { it.endsWith(".png") }
        g.drawString(font, "$count texture${if (count == 1) "" else "s"}", x, cy, ClientUi.DIM, false)
        cy += 18

        val inUse = staged[category.id] == source.id
        val useLabel = if (inUse) "Stop using" else "Use"
        ClientUi.drawFlatButton(g, font, x, cy, w, 20, useLabel, inside(mouseX, mouseY, x, cy, w, 20), primary = !inUse)
        hit(x, cy, w, 20) {
            if (inUse) staged.remove(category.id) else staged[category.id] = source.id
        }
        cy += 26
        val favorite = source.id in TextureMixer.favorites
        ClientUi.drawFlatButton(g, font, x, cy, w, 20, if (favorite) "Unfavorite" else "Favorite", inside(mouseX, mouseY, x, cy, w, 20))
        hit(x, cy, w, 20) {
            if (favorite) TextureMixer.favorites.remove(source.id) else TextureMixer.favorites.add(source.id)
            TextureMixer.save()
        }
    }

    private fun drawPresets(g: GuiGraphics, x: Int, y: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        ClientUi.drawFlatButton(g, font, x, y, w, 20, "Save current picks", inside(mouseX, mouseY, x, y, w, 20))
        hit(x, y, w, 20) { savePreset() }
        var cy = y + 28
        if (TextureMixer.presets.isEmpty()) {
            g.drawString(font, trim("No presets yet.", w), x, cy + 4, ClientUi.DIM, false)
            return
        }
        TextureMixer.presets.toList().forEach { preset ->
            if (cy + 22 > bottom) return
            val hovered = inside(mouseX, mouseY, x, cy, w, 22)
            ClientUi.drawListRow(g, x, cy, w, 22, ClientUi.hover("tex:preset:${preset.name}", hovered))
            g.drawString(font, trim(preset.name, w - 60), x + 6, cy + 7, ClientUi.TEXT, false)
            g.drawString(font, "${preset.picks.size}", x + w - 30 - font.width("${preset.picks.size}"), cy + 7, ClientUi.DIM, false)
            RiverIcons.draw(g, "trash", x + w - 16, cy + 6, 10, if (hovered) ClientUi.TEXT else ClientUi.DIM)
            hit(x, cy, w, 22) {
                staged.clear()
                staged.putAll(preset.picks)
                status = "Loaded ${preset.name}. Apply to use it."
                statusIsError = false
            }
            hit(x + w - 20, cy, 20, 22) {
                TextureMixer.presets.remove(preset)
                saveState("Deleted ${preset.name}")
            }
            cy += 26
        }
    }

    private fun savePreset() {
        if (staged.isEmpty()) {
            status = "Pick some textures before saving a preset"
            statusIsError = true
            return
        }
        val preset = TextureMixer.Preset(TextureMixer.nextPresetName(), LinkedHashMap(staged))
        TextureMixer.presets += preset
        saveState("Saved ${preset.name}")
    }

    private fun saveState(success: String) {
        val ok = TextureMixer.save()
        status = if (ok) success else "Could not save River's texture settings"
        statusIsError = !ok
    }

    // ------------------------------------------------------------------ footer

    private fun drawFooter(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        g.fill(x + 1, y, x + w - 1, y + 1, ClientUi.BORDER)
        val dirty = staged != TextureMixer.picks
        val message = when {
            TextureMixer.applying -> "Building River Picks..."
            status.isNotEmpty() -> status
            dirty -> "Changes are not applied yet"
            else -> "Picked textures go into a pack called River Picks, on top of your other packs."
        }
        g.drawString(font, trim(message, w - 220), x + 12, y + 14, if (statusIsError) ClientUi.DANGER else ClientUi.MUTED, false)
        if (!dirty || TextureMixer.applying) return

        val applyX = x + w - 12 - 84
        val discardX = applyX - 8 - 74
        ClientUi.drawFlatButton(g, font, applyX, y + 8, 84, 20, "Apply", inside(mouseX, mouseY, applyX, y + 8, 84, 20), primary = true)
        hit(applyX, y + 8, 84, 20) { applyPicks() }
        ClientUi.drawFlatButton(g, font, discardX, y + 8, 74, 20, "Discard", inside(mouseX, mouseY, discardX, y + 8, 74, 20))
        hit(discardX, y + 8, 74, 20) {
            staged.clear()
            staged.putAll(TextureMixer.picks)
            status = ""
            statusIsError = false
        }
    }

    private fun applyPicks() {
        status = ""
        TextureMixer.apply(LinkedHashMap(staged)) { error ->
            status = error ?: "Applied"
            statusIsError = error != null
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    /** A source's card for [entry], drawn by ModelPreview in the background; a plain tile until it is ready. */
    private fun drawPreview(g: GuiGraphics, source: TextureMixer.Source?, entry: Category, x: Int, y: Int, size: Int) {
        val bytes = source?.takeIf { it.files.containsKey(entry.id) }?.let { TextureMixer.preview(it, entry) }
        val icon = bytes?.let { RemoteIconCache.getData("tex:${TextureMixer.generation}:${source.id}:${entry.id}", it) }
        if (icon == null) {
            g.fill(x, y, x + size, y + size, ClientUi.PANEL_ALT)
            return
        }
        g.riverBlit(icon.id, x, y, 0f, 0f, size, size, icon.width, icon.height, icon.width, icon.height)
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        val mx = event.x()
        val my = event.y()
        focused = Field.NONE
        hits.lastOrNull { mx >= it.x && mx < it.x + it.w && my >= it.y && my < it.y + it.h }?.onClick?.invoke()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        if (mouseX >= gridRect[0] && mouseX < gridRect[2]) gridScroll = (gridScroll - (deltaY * 24).toInt()).coerceIn(0, maxGridScroll)
        if (mouseX >= sidebarRect[0] && mouseX < sidebarRect[2]) sidebarScroll = (sidebarScroll - (deltaY * 30).toInt()).coerceIn(0, maxSidebarScroll)
        return true
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        when (focused) {
            Field.PACKS -> if (search.length < 40) { search += event.codepointAsString(); gridScroll = 0 }
            Field.CATEGORIES -> if (categorySearch.length < 40) { categorySearch += event.codepointAsString(); sidebarScroll = 0 }
            Field.NONE -> return super.charTyped(event)
        }
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (focused != Field.NONE) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> if (focused == Field.PACKS) search = search.dropLast(1) else categorySearch = categorySearch.dropLast(1)
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER -> focused = Field.NONE
            }
            return true
        }
        return super.keyPressed(event)
    }

    override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        parentShown = ClientUi.drawParentScreen(g, parent, width, height, partialTick)
        if (parentShown) return
        super.renderBackground(g, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        TextureMixer.closePreviews()
        minecraft.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, w, h, onClick))
    }

    private fun inside(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        mx >= x && mx < x + w && my >= y && my < y + h

    private fun trim(text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private companion object {
        const val HEADER_H = 34
        const val FOOTER_H = 36
        const val SIDEBAR_W = 168
        const val INSPECTOR_W = 180
        const val ROW_H = 30
        const val CARD_W = 92
        const val CARD_H = 84
        const val CARD_GAP = 6
    }
}
//?}
