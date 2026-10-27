package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.net.RemoteIconCache
import dev.wyz.clientcore.net.ServerDirectory
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import dev.wyz.clientcore.compat.riverBlit
import kotlin.math.max

/**
 * River's server browser: a list of public servers, each pinged from the player's own
 * connection, with one click to join or to keep it in the multiplayer list.
 *
 * The list itself is ServerDirectory's, which River's update server can change without a
 * release. Sorted with featured servers first, then by how many people are on right now.
 */
class RiverServerBrowserScreen(private val parent: Screen?) : Screen(Component.literal("Servers")), RiverScreen {

    private val mcClient = Minecraft.getInstance()

    private var tag = "All"
    private var query = ""
    private var searchFocused = false
    private var scroll = 0
    private val added = HashSet<String>()
    private var savedAddresses: Set<String> = emptySet()

    private class Region(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Region>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Region(x, y, x + w, y + h, onClick))
    }

    override fun init() {
        ServerDirectory.refresh()
        savedAddresses = runCatching {
            val list = ServerList(mcClient).apply { load() }
            (0 until list.size()).map { list.get(it).ip.lowercase() }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun visible(): List<ServerDirectory.Entry> {
        val q = query.trim().lowercase()
        return ServerDirectory.entries
            .filter { tag == "All" || it.tags.any { t -> t.equals(tag, true) } }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.address.lowercase().contains(q) || it.description.lowercase().contains(q) }
            .sortedWith(
                compareByDescending<ServerDirectory.Entry> { it.featured }
                    .thenByDescending { (ServerDirectory.status(it) as? ServerDirectory.Status.Online)?.result?.online ?: -1 }
            )
    }

    // ------------------------------------------------------------------ render

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        hits.clear()

        val margin = if (width >= 900) 40 else 16
        val px = margin
        val py = 14
        val pw = width - margin * 2
        val ph = height - py * 2
        ClientUi.drawPanel(g, px, py, pw, ph)

        val x = px + 16
        val right = px + pw - 16
        val top = py + 14

        // Header
        RiverIcons.draw(g, "server", x, top, 16, ClientUi.ACCENT_B)
        g.drawString(font, "Servers", x + 22, top + 4, ClientUi.TEXT, true)
        g.drawString(font, "Pinged from your connection", x + 22 + font.width("Servers") + 8, top + 4, ClientUi.DIM, false)

        val closeSize = 22
        val closeHovered = mouseX in (right - closeSize)..right && mouseY in top..(top + closeSize)
        RiverIcons.draw(g, "x", right - closeSize + 6, top + 3, 12, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(right - closeSize, top - 2, closeSize + 4, closeSize + 4) { onClose() }

        val refreshW = font.width("Refresh") + 26
        val refreshX = right - closeSize - 8 - refreshW
        val refreshHovered = mouseX in refreshX..(refreshX + refreshW) && mouseY in (top - 2)..(top + 18)
        ClientUi.drawFlatButton(g, font, refreshX, top - 2, refreshW, 20, "Refresh", refreshHovered, false)
        hit(refreshX, top - 2, refreshW, 20) { ServerDirectory.refresh(force = true) }

        // Tag chips, then the search box on the same row when there is room.
        val chipsY = top + 28
        val tags = listOf("All") + ServerDirectory.entries.flatMap { it.tags }.distinct().sorted()
        var chipX = x
        tags.forEach { label ->
            val cw = font.width(label) + 16
            val active = tag == label
            val hovered = mouseX in chipX..(chipX + cw) && mouseY in chipsY..(chipsY + 18)
            ClientUi.drawFlatButton(g, font, chipX, chipsY, cw, 18, label, hovered, active)
            hit(chipX, chipsY, cw, 18) { tag = label; scroll = 0 }
            chipX += cw + 6
        }
        val searchW = 180
        val searchX = right - searchW
        val searchY = if (chipX + 12 < searchX) chipsY - 2 else chipsY + 24
        drawSearch(g, searchX, searchY, searchW, mouseX, mouseY)

        val listTop = max(chipsY + 26, searchY + 30)
        val listBottom = py + ph - 26
        drawList(g, x, listTop, right - x, listBottom, mouseX, mouseY)

        val footer = "Suggest a server in River's Discord."
        g.drawString(font, trim(footer, right - x), x, py + ph - 18, ClientUi.DIM, false)
    }

    private fun drawSearch(g: GuiGraphics, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 22)
        ClientUi.drawListRow(g, x, y, w, 22, ClientUi.hover("servers:search", hovered || searchFocused), searchFocused)
        RiverIcons.draw(g, "search", x + 7, y + 6, 11, ClientUi.DIM)
        val placeholder = query.isEmpty() && !searchFocused
        val shown = if (placeholder) "Search servers" else query
        val caret = if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        g.drawString(font, trim(shown, w - 32) + caret, x + 24, y + 7, if (placeholder) ClientUi.DIM else ClientUi.TEXT, true)
        hit(x, y, w, 22) { searchFocused = true }
    }

    private fun drawList(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        val servers = visible()
        if (servers.isEmpty()) {
            g.drawString(font, "No servers match that.", x, top + 4, ClientUi.DIM, true)
            return
        }
        val cols = if (w >= 760) 2 else 1
        val gap = 8
        val scrollbarSpace = 10
        val cardW = (w - scrollbarSpace - (cols - 1) * gap) / cols
        val cardH = 64
        val rows = (servers.size + cols - 1) / cols
        val viewH = bottom - top
        val contentH = rows * (cardH + gap)
        scroll = scroll.coerceIn(0, max(0, contentH - viewH))

        g.enableScissor(x, top, x + w, bottom)
        servers.forEachIndexed { i, entry ->
            val cx = x + (i % cols) * (cardW + gap)
            val cy = top - scroll + (i / cols) * (cardH + gap)
            if (cy + cardH >= top && cy <= bottom) drawCard(g, entry, cx, cy, cardW, cardH, mouseX, mouseY, top, bottom)
        }
        g.disableScissor()
        ClientUi.drawScrollbar(g, x + w - 5, top, viewH, contentH, viewH, scroll)
    }

    private fun drawCard(
        g: GuiGraphics, entry: ServerDirectory.Entry, x: Int, y: Int, w: Int, h: Int,
        mouseX: Int, mouseY: Int, clipTop: Int, clipBottom: Int
    ) {
        val inView = mouseY in clipTop..clipBottom
        val hovered = inView && mouseX in x..(x + w) && mouseY in y..(y + h)
        ClientUi.drawSectionCard(g, x, y, w, h, ClientUi.hover("servers:card:${entry.address}", hovered))
        val status = ServerDirectory.status(entry)
        val online = status as? ServerDirectory.Status.Online

        // Icon: the server's own, or River's server glyph until it arrives.
        val iconSize = 44
        val ix = x + 10
        val iy = y + (h - iconSize) / 2
        val icon = online?.result?.favicon?.let { RemoteIconCache.getData(entry.address, it) }
        if (icon != null) {
            g.riverBlit(icon.id, ix, iy, 0f, 0f, iconSize, iconSize, icon.width, icon.height, icon.width, icon.height)
        } else {
            ClientUi.fillRounded(g, ix, iy, iconSize, iconSize, ClientUi.RADIUS_CARD, ClientUi.alpha(0xFF000000.toInt(), 0.25f))
            RiverIcons.draw(g, "server", ix + 12, iy + 12, 20, ClientUi.DIM)
        }

        // Right side: players and ping, then the buttons under them.
        val buttonH = 18
        val joinW = 48
        val addW = 22
        val bx = x + w - 10 - joinW
        val by = y + h - 10 - buttonH
        val joinHovered = inView && mouseX in bx..(bx + joinW) && mouseY in by..(by + buttonH)
        ClientUi.drawFlatButton(g, font, bx, by, joinW, buttonH, "Join", joinHovered, true)
        if (inView) hit(bx, by, joinW, buttonH) { join(entry) }

        val alreadySaved = entry.address.lowercase() in savedAddresses || entry.address in added
        val ax = bx - 6 - addW
        val addHovered = inView && !alreadySaved && mouseX in ax..(ax + addW) && mouseY in by..(by + buttonH)
        ClientUi.drawFlatButton(g, font, ax, by, addW, buttonH, "", addHovered, false)
        RiverIcons.draw(g, if (alreadySaved) "check" else "plus", ax + 6, by + 4, 10, if (alreadySaved) ClientUi.POSITIVE else ClientUi.MUTED)
        if (inView && !alreadySaved) hit(ax, by, addW, buttonH) { addToList(entry) }

        val statusText = when (status) {
            is ServerDirectory.Status.Online -> "%,d online".format(status.result.online)
            is ServerDirectory.Status.Offline -> "Offline"
            else -> "Pinging..."
        }
        val statusColor = when (status) {
            is ServerDirectory.Status.Online -> ClientUi.TEXT
            is ServerDirectory.Status.Offline -> ClientUi.DANGER
            else -> ClientUi.DIM
        }
        val barsW = 14
        val statusRight = x + w - 10 - barsW - 6
        g.drawString(font, statusText, statusRight - font.width(statusText), y + 11, statusColor, true)
        drawPingBars(g, x + w - 10 - barsW, y + 10, online?.result?.pingMs)

        // Name, address and the server's own message of the day.
        val textX = ix + iconSize + 10
        val textW = ax - 10 - textX
        val nameW = statusRight - font.width(statusText) - 10 - textX
        g.drawString(font, trim(entry.name, nameW), textX, y + 11, ClientUi.TEXT, true)
        g.drawString(font, trim(entry.address, textW), textX, y + 24, ClientUi.DIM, false)
        val line = online?.result?.motd?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: entry.description
        g.drawString(font, trim(line, textW), textX, y + 40, ClientUi.MUTED, false)
    }

    private fun drawPingBars(g: GuiGraphics, x: Int, y: Int, pingMs: Long?) {
        val bars = when {
            pingMs == null -> 0
            pingMs < 80 -> 5
            pingMs < 150 -> 4
            pingMs < 250 -> 3
            pingMs < 400 -> 2
            else -> 1
        }
        val color = when {
            bars >= 4 -> ClientUi.POSITIVE
            bars == 3 -> ClientUi.WARNING
            bars > 0 -> ClientUi.DANGER
            else -> ClientUi.DIM
        }
        for (i in 0 until 5) {
            val bh = 2 + i * 2
            val bx = x + i * 3
            g.fill(bx, y + 10 - bh, bx + 2, y + 10, if (i < bars) color else ClientUi.alpha(ClientUi.DIM, 0.5f))
        }
    }

    private fun join(entry: ServerDirectory.Entry) {
        val data = ServerData(entry.name, entry.address, ServerData.Type.OTHER)
        ConnectScreen.startConnecting(this, mcClient, ServerAddress.parseString(entry.address), data, false, null)
    }

    private fun addToList(entry: ServerDirectory.Entry) {
        runCatching {
            val list = ServerList(mcClient).apply { load() }
            val exists = (0 until list.size()).any { list.get(it).ip.equals(entry.address, true) }
            if (!exists) {
                list.add(ServerData(entry.name, entry.address, ServerData.Type.OTHER), false)
                list.save()
            }
            added.add(entry.address)
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
        searchFocused = false
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
        scroll = (scroll - (deltaY * 28).toInt()).coerceAtLeast(0)
        return true
    }

//? if >=1.21.9 {
    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
//?} else {
/*    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = keyCode
*///?}
        if (searchFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> searchFocused = false
                GLFW.GLFW_KEY_BACKSPACE -> { query = query.dropLast(1); scroll = 0 }
            }
            return true
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

//? if >=1.21.9 {
    override fun charTyped(event: CharacterEvent): Boolean {
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
*///?}
        if (searchFocused) {
//? if >=1.21.9 {
            if (query.length < 40) query += event.codepointAsString()
//?} else {
/*            if (query.length < 40) query += codePoint.toString()
*///?}
            scroll = 0
            return true
        }
//? if >=1.21.9 {
        return super.charTyped(event)
//?} else {
/*        return super.charTyped(codePoint, modifiers)
*///?}
    }

    override fun onClose() {
        mcClient.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        /** For the multiplayer screen's button (a Java mixin), which cannot reach setScreen on 26.2. */
        @JvmStatic
        fun open(parent: Screen?) {
            Minecraft.getInstance().setScreen(RiverServerBrowserScreen(parent))
        }
    }

    private fun trim(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
