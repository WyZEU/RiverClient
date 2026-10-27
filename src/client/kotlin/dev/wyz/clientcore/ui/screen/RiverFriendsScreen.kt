package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.net.RiverSocial
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.PlayerHeads
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
//?} else {
/**///?}
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.lwjgl.glfw.GLFW
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Friends, requests, blocked players and direct messages.
 *
 * Three columns, built from the same pieces as the main menu so the two read as one
 * client: a narrow rail on the left with you at the top and one button per list (friends,
 * requests, blocked), the list itself next to it, and the conversation with whoever is
 * picked filling the rest. Square, flat, opaque surfaces stepped in brightness, the theme
 * accent for anything that is "yours" or selected.
 *
 * Friends are grouped by what they are doing - in a game, online, offline - and offline
 * friends get a compact row, so the people you could actually play with sit at the top
 * and take the space. Messages are bubbles: yours on the right in the accent, theirs on
 * the left with their face, so a conversation can be followed without reading names.
 *
 * Immediate mode like the rest of River's screens: everything is drawn each frame and
 * clicks resolve against the hit list built while drawing.
 */
class RiverFriendsScreen(
    private val parent: Screen?,
    initialFriend: String? = null,
    initialTab: String? = null
) : Screen(Component.literal("Friends")), RiverScreen {

    private enum class Tab(val label: String, val icon: String) {
        FRIENDS("Friends", "users"),
        REQUESTS("Requests", "inbox"),
        BLOCKED("Blocked", "ban")
    }
    private enum class Focus { NONE, SEARCH, COMPOSER }

    private val mc = Minecraft.getInstance()

    private var tab = when (initialTab) {
        "requests" -> Tab.REQUESTS
        "blocked" -> Tab.BLOCKED
        else -> Tab.FRIENDS
    }
    private var selectedUuid: String? = initialFriend
    private val selected: RiverSocial.Friend?
        get() = selectedUuid?.let { id -> RiverSocial.friends.firstOrNull { it.uuid == id } }

    private var messages: List<RiverSocial.Message> = emptyList()
    private var threadFor: String? = null
    private var loadingThread = false
    /** Index of the first message that arrived unread, for the "New" line. -1 = none. */
    private var firstUnread = -1

    private var search = ""
    private var composer = ""
    private var focus = Focus.NONE
    private var focusChangedAt = 0L

    private var blocked: List<RiverSocial.Friend> = emptyList()

    private var listScroll = 0f
    private var listScrollTarget = 0f
    private var listMaxScroll = 0
    /** Distance scrolled up from the newest message; 0 is the bottom. */
    private var chatScroll = 0f
    private var chatScrollTarget = 0f
    private var chatMaxScroll = 0

    private var menuOpen = false
    private var armed = ""
    private var armedAt = 0L

    private var notice = ""
    private var noticeOk = true
    private var noticeAt = 0L

    private var lastPoll = 0L
    private var seenSignal = RiverSocial.messageSignal

    private var tooltip: String? = null
    private var tooltipX = 0
    private var tooltipY = 0

    private var rosterRect = IntArray(4)
    private var chatRect = IntArray(4)

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Hit>()
    /** Hits from this index on belong to the open menu; while it is open nothing else is clickable. */
    private var menuHitFloor = -1

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    /** A hit clipped to a scrolling area, so a row scrolled out of view cannot be clicked. */
    private fun clippedHit(x: Int, y: Int, w: Int, h: Int, top: Int, bottom: Int, onClick: () -> Unit) {
        val y1 = max(y, top)
        val y2 = min(y + h, bottom)
        if (y2 > y1) hits.add(Hit(x, y1, x + w, y2, onClick))
    }

    private fun inside(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    private fun say(message: String, ok: Boolean = true) {
        notice = message
        noticeOk = ok
        noticeAt = System.currentTimeMillis()
    }

    private fun setFocus(next: Focus) {
        if (focus != next) focusChangedAt = System.currentTimeMillis()
        focus = next
    }

    // ------------------------------------------------------------------ lifecycle

    override fun init() {
        // Signing in is a Mojang round trip, so it happens when the screen is opened rather
        // than at startup for players who never use this.
        RiverSocial.ensureSession { ok ->
            if (ok) {
                RiverSocial.refresh(force = true)
                refreshBlocked()
                selectedUuid?.let { if (threadFor != it) openThread(it) }
            }
        }
    }

    private fun openThread(uuid: String) {
        selectedUuid = uuid
        threadFor = uuid
        messages = emptyList()
        loadingThread = true
        chatScroll = 0f
        chatScrollTarget = 0f
        menuOpen = false
        armed = ""
        val unread = RiverSocial.unread(uuid)
        RiverSocial.markRead(uuid)
        RiverSocial.history(uuid) { list ->
            if (mc.screen !== this || selectedUuid != uuid) return@history
            messages = list
            loadingThread = false
            firstUnread = if (unread > 0) max(0, list.size - unread) else -1
        }
        if (focus == Focus.NONE) setFocus(Focus.COMPOSER)
    }

    private fun refreshThread() {
        val uuid = selectedUuid ?: return
        RiverSocial.history(uuid) { list ->
            if (mc.screen === this && selectedUuid == uuid) messages = list
        }
    }

    private fun refreshBlocked() {
        RiverSocial.blockedList { list -> if (mc.screen === this) blocked = list }
    }

    private fun selectTab(entry: Tab) {
        if (tab == entry) return
        tab = entry
        listScrollTarget = 0f
        if (entry == Tab.BLOCKED) refreshBlocked()
    }

    // ------------------------------------------------------------------ render

//? if >=26.1 {
/*    override fun extractRenderState(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
*///?} else {
    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
//?}
        // Over another menu the backdrop is a lighter veil, so that menu still shows.
        if (parentShown) g.fill(0, 0, width, height, 0x8C05060C.toInt()) else ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        lastMouseX = mouseX
        lastMouseY = mouseY
        hits.clear()
        menuHitFloor = -1
        tooltip = null

        val now = System.currentTimeMillis()
        if (RiverSocial.signedIn) {
            // A message pushed over the socket reloads the open conversation at once; the
            // poll catches presence changes and anything the socket missed.
            if (RiverSocial.messageSignal != seenSignal) {
                seenSignal = RiverSocial.messageSignal
                refreshThread()
            }
            if (now - lastPoll > RiverSocial.REFRESH_INTERVAL_MS) {
                lastPoll = now
                RiverSocial.refresh()
                refreshThread()
            }
            selectedUuid?.let { RiverSocial.markRead(it) }
        }

        // The window: as large as is comfortable, never wider than a chat needs.
        val ww = min(width - 24, min(max(580, (width * 0.74f).toInt()), 760))
        val wh = min(height - 24, min(max(300, (height * 0.78f).toInt()), 440))
        val wx = (width - ww) / 2
        val wy = (height - wh) / 2
        ClientUi.drawPanel(g, wx, wy, ww, wh, seeThrough = parentShown)

        if (!RiverSocial.signedIn) {
            rosterRect = IntArray(4)
            chatRect = IntArray(4)
            drawSignedOut(g, wx, wy, ww, wh, mouseX, mouseY)
            drawClose(g, wx + ww - 28, wy + 7, mouseX, mouseY)
        } else {
            drawRail(g, wx, wy, wh, mouseX, mouseY)
            val rx = wx + RAIL_W
            val rw = (ww * 0.30f).toInt().coerceIn(150, 214)
            drawRoster(g, rx, wy, rw, wh, mouseX, mouseY)
            g.fill(rx + rw, wy + 1, rx + rw + 1, wy + wh - 1, ClientUi.BORDER)
            val cx = rx + rw + 1
            drawConversation(g, cx, wy, wx + ww - cx, wh, mouseX, mouseY)
        }

        ClientUi.withOverlayLayer(g) {
            if (menuOpen) drawMenu(g, mouseX, mouseY)
            drawNotice(g, wx, wy, ww, wh, now)
            drawTooltip(g)
        }
    }

    // ------------------------------------------------------------------ rail

    /*
      With the glass effect on, every surface here is see-through so the blurred world shows
      through the whole window, not only the conversation: solid colours become washes and
      an overlay is laid on as it is instead of being composited to an opaque colour. With
      glass off there is nothing behind worth seeing, so the surfaces stay solid.
    */
    /** [color] as a surface: itself, or at [glassAlpha] on glass. */
    private fun glass(color: Int, glassAlpha: Float): Int = if (ClientUi.glassFrame) ClientUi.alpha(color, glassAlpha) else color

    /** [overlay] on [base]: composited to an opaque colour on a solid surface, left translucent on glass. */
    private fun lay(overlay: Int, base: Int): Int = if (ClientUi.glassFrame) overlay else ClientUi.over(overlay, base)

    /** The rail sits a step below the panel, the way the main menu's sidebar does. */
    private fun railBg(): Int = glass(ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.28f), ClientUi.PANEL), 0.55f)

    /** The list's surface, between the rail and the conversation. */
    private fun rosterBg(): Int = glass(ClientUi.PANEL, 0.4f)

    private fun drawRail(g: GuiGraphics, x: Int, y: Int, h: Int, mx: Int, my: Int) {
        val w = RAIL_W
        val bg = railBg()
        g.fill(x + 1, y + 1, x + w, y + h - 1, bg)
        g.fill(x + w, y + 1, x + w + 1, y + h - 1, ClientUi.BORDER)

        // You, at the top, where a window title would be.
        val me = selfName()
        val hx = x + 8
        val hy = y + 7
        PlayerHeads.draw(g, RiverSocial.selfUuid, me, hx, hy, 20)
        drawPresenceDot(g, "online", hx + 15, hy + 15, bg)
        if (inside(mx, my, hx, hy, 20, 20)) showTooltipRight("Signed in as $me", hx + 24, hy + 3)
        g.fill(x + 7, y + HEADER_H - 1, x + w - 6, y + HEADER_H, ClientUi.alpha(ClientUi.BORDER, 0.9f))

        // One button per list. The bar on the rail's edge slides to the current one.
        val size = 24
        val bx = x + 6
        var by = y + HEADER_H + 8
        var activeY = by
        for (entry in Tab.entries) {
            val active = entry == tab
            val hovered = inside(mx, my, bx, by, size, size)
            val anim = ClientUi.hover("fr:rail:${entry.name}", hovered)
            if (active) {
                g.fill(bx, by, bx + size, by + size, lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.22f + 0.08f * anim), bg))
                ClientUi.drawRoundedBorder(g, bx, by, size, size, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.55f))
                activeY = by
            } else if (anim > 0.01f) {
                g.fill(bx, by, bx + size, by + size, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.07f * anim), bg))
            }
            val color = if (active) ClientUi.ACCENT_B else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim)
            RiverIcons.draw(g, entry.icon, bx + 6, by + 6, 12, color)

            // Friends counts unread messages, Requests counts people waiting on you.
            val count = when (entry) {
                Tab.FRIENDS -> RiverSocial.unreadTotal
                Tab.REQUESTS -> RiverSocial.requests.size
                Tab.BLOCKED -> 0
            }
            if (count > 0) drawBadge(g, count, bx + size + 2, by - 3, ring = bg)

            if (hovered) showTooltipRight(entry.label, bx + size + 6, by + 5)
            hit(bx, by, size, size) { selectTab(entry) }
            by += size + 6
        }
        val barY = ClientUi.animate("fr:railbar", activeY.toFloat(), 16f).roundToInt()
        g.fill(x + 1, barY + 5, x + 3, barY + size - 5, ClientUi.ACCENT_A)

        // The shortcut, so it can be found again without digging through settings.
        val ky = y + h - 30
        val keyHovered = inside(mx, my, bx, ky, size, size)
        RiverIcons.draw(g, "keyboard", bx + 6, ky + 6, 12, if (keyHovered) ClientUi.MUTED else ClientUi.DIM)
        if (keyHovered) showTooltipRight("${shortcutName()} opens Friends", bx + size + 6, ky + 5)
    }

    // ------------------------------------------------------------------ signed out

    private fun drawSignedOut(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        if (SeasonalFx.halloween()) SeasonalFx.drawPumpkinIcon(g, x + 12, y + 10, 12)
        else RiverIcons.draw(g, "users", x + 12, y + 10, 12, ClientUi.ACCENT_B)
        ClientUi.drawGradientTitle(g, font, "Friends", x + 30, y + 12)
        g.fill(x + 1, y + HEADER_H - 1, x + w - 1, y + HEADER_H, ClientUi.BORDER)

        val cx = x + w / 2
        val failed = RiverSocial.error.isNotEmpty() && !RiverSocial.signingIn
        var cy = y + HEADER_H + (h - HEADER_H) / 2 - 44

        if (failed && RiverSocial.accountBlocked) {
            drawIconTile(g, "ban", cx, cy, ClientUi.DANGER)
            cy += 46
            centered(g, "Account blocked", cx, cy, ClientUi.TEXT, true)
            cy += 14
            val message = "${RiverSocial.error} If you think this is a mistake, ask the owner."
            for (line in font.split(Component.literal(message), min(260, w - 40))) {
                g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.MUTED, false)
                cy += 10
            }
        } else if (failed) {
            drawIconTile(g, "wifi", cx, cy, ClientUi.DANGER)
            cy += 46
            centered(g, "Couldn't connect to River", cx, cy, ClientUi.TEXT, true)
            cy += 14
            for (line in font.split(Component.literal(RiverSocial.error), min(260, w - 40))) {
                g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.MUTED, false)
                cy += 10
            }
            cy += 8
            val bw = 96
            val bx = cx - bw / 2
            ClientUi.drawButton(g, font, bx, cy, bw, 20, "Try again", inside(mx, my, bx, cy, bw, 20), primary = true)
            hit(bx, cy, bw, 20) { RiverSocial.ensureSession() }
        } else {
            drawSpinner(g, cx, cy + 16)
            cy += 46
            centered(g, "Signing in to River", cx, cy, ClientUi.TEXT, true)
            cy += 14
            val why = "Signs in with your Minecraft account."
            for (line in font.split(Component.literal(why), min(260, w - 40))) {
                g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.DIM, false)
                cy += 10
            }
        }
    }

    /** Eight squares round a circle, the brightest one walking clockwise. */
    private fun drawSpinner(g: GuiGraphics, cx: Int, cy: Int) {
        val step = ((System.currentTimeMillis() / 90) % 8).toInt()
        for (i in 0 until 8) {
            val angle = Math.PI * 2 * i / 8 - Math.PI / 2
            val px = cx + (cos(angle) * 7).roundToInt()
            val py = cy + (sin(angle) * 7).roundToInt()
            val age = (step - i + 8) % 8
            val color = ClientUi.alpha(ClientUi.ACCENT_B, (1f - age / 8f).coerceAtLeast(0.15f))
            g.fill(px - 1, py - 1, px + 2, py + 2, color)
        }
    }

    /** A 32px square with an icon in it, tinted by [color]: the picture at the top of an empty or error state. */
    private fun drawIconTile(g: GuiGraphics, icon: String, cx: Int, y: Int, color: Int) {
        val size = 32
        val x = cx - size / 2
        g.fill(x, y, x + size, y + size, lay(ClientUi.alpha(color, 0.14f), ClientUi.PANEL_SOFT))
        ClientUi.drawRoundedBorder(g, x, y, size, size, 0, ClientUi.alpha(color, 0.5f))
        g.fill(x + 1, y + 1, x + size - 1, y + 2, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f))
        RiverIcons.draw(g, icon, x + 8, y + 8, 16, color)
    }

    // ------------------------------------------------------------------ roster

    private fun drawRoster(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val bg = rosterBg()
        g.fill(x + 1, y + 1, x + w, y + h - 1, bg)

        // Header: which list, and how much is in it.
        var tx = x + 12
        if (SeasonalFx.halloween()) {
            SeasonalFx.drawPumpkinIcon(g, x + 10, y + 11, 11)
            tx = x + 26
        }
        g.drawString(font, tab.label, tx, y + 13, ClientUi.TEXT, true)
        val summary = when (tab) {
            Tab.FRIENDS -> "${RiverSocial.friends.count { it.online }} online"
            Tab.REQUESTS -> "${RiverSocial.requests.size} pending"
            Tab.BLOCKED -> "${blocked.size} blocked"
        }
        val sx = x + w - 10 - font.width(summary)
        g.drawString(font, summary, sx, y + 13, ClientUi.DIM, false)
        if (tab == Tab.FRIENDS) {
            val anyOnline = RiverSocial.friends.any { it.online }
            g.fill(sx - 8, y + 15, sx - 4, y + 19, if (anyOnline) ClientUi.POSITIVE else ClientUi.DIM)
        }
        g.fill(x + 1, y + HEADER_H - 1, x + w, y + HEADER_H, ClientUi.BORDER)

        // Search, which is also where a name is typed to add someone.
        val fieldY = y + HEADER_H + 8
        drawField(
            g, x + 8, fieldY, w - 16, 20, mx, my,
            text = search,
            placeholder = if (tab == Tab.FRIENDS) "Search or add by name" else "Search",
            focused = focus == Focus.SEARCH,
            icon = "search",
            key = "fr:search"
        ) { setFocus(Focus.SEARCH) }

        val listTop = fieldY + 26
        val listBottom = y + h - 6
        rosterRect = intArrayOf(x, listTop, x + w, listBottom)

        listScroll = ClientUi.animate("fr:list", listScrollTarget, 18f)
        g.enableScissor(x + 1, listTop, x + w - 1, listBottom)
        val contentH = when (tab) {
            Tab.FRIENDS -> drawFriendsList(g, x, listTop, w, listBottom, mx, my)
            Tab.REQUESTS -> drawRequestsList(g, x, listTop, w, listBottom, mx, my)
            Tab.BLOCKED -> drawBlockedList(g, x, listTop, w, listBottom, mx, my)
        }
        g.disableScissor()

        val viewH = listBottom - listTop
        if (listScroll > 1f) g.fillGradient(x + 1, listTop, x + w, listTop + 10, bg, ClientUi.alpha(bg, 0f))
        if (contentH - viewH - listScroll > 1f) g.fillGradient(x + 1, listBottom - 10, x + w, listBottom, ClientUi.alpha(bg, 0f), bg)
        listMaxScroll = max(0, contentH - viewH)
        listScrollTarget = listScrollTarget.coerceIn(0f, listMaxScroll.toFloat())
        if (listMaxScroll > 0) {
            val trackX = x + w - 4
            val thumbH = max(16, viewH * viewH / max(1, contentH))
            val thumbY = listTop + ((viewH - thumbH) * (listScroll / listMaxScroll)).roundToInt()
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ClientUi.alpha(ClientUi.MUTED, 0.5f))
        }
    }

    private fun matches(name: String) = search.isBlank() || name.contains(search.trim(), ignoreCase = true)

    private class Groups(val inGame: List<RiverSocial.Friend>, val online: List<RiverSocial.Friend>, val offline: List<RiverSocial.Friend>) {
        val all: List<RiverSocial.Friend> get() = inGame + online + offline
    }

    /** The friends matching the search, in the order they are listed. */
    private fun groups(): Groups {
        val shown = RiverSocial.friends.filter { matches(it.name) }
        val byName = compareBy<RiverSocial.Friend> { it.name.lowercase() }
        return Groups(
            inGame = shown.filter { it.online && it.server.isNotEmpty() }.sortedWith(byName),
            online = shown.filter { it.online && it.server.isEmpty() }.sortedWith(byName),
            offline = shown.filter { !it.online }.sortedWith(byName)
        )
    }

    /** Returns the content height. */
    private fun drawFriendsList(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mx: Int, my: Int): Int {
        val query = search.trim()
        val all = RiverSocial.friends
        val groups = groups()
        val offerAdd = query.length >= 3 && all.none { it.name.equals(query, ignoreCase = true) }

        if (all.isEmpty() && query.isEmpty()) {
            drawEmpty(g, x, top, w, bottom, "user_plus", "No friends yet", "Type a player's name above and press Enter to send a request.")
            return 0
        }

        var cy = top + 2 - listScroll.roundToInt()
        val rowX = x + 6
        val rowW = w - 12

        if (offerAdd) {
            val hovered = inside(mx, my, rowX, cy, rowW, ROW_H) && my in top until bottom
            val anim = ClientUi.hover("fr:addrow", hovered)
            g.fill(rowX, cy, rowX + rowW, cy + ROW_H, lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.12f + 0.10f * anim), ClientUi.PANEL))
            ClientUi.drawRoundedBorder(g, rowX, cy, rowW, ROW_H, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.35f + 0.35f * anim))
            g.fill(rowX + 6, cy + 5, rowX + 22, cy + 21, ClientUi.ACCENT_A)
            RiverIcons.draw(g, "user_plus", rowX + 9, cy + 8, 10, ClientUi.TEXT)
            g.drawString(font, trim("Add $query", rowW - 36), rowX + 29, cy + 4, ClientUi.TEXT, true)
            g.drawString(font, "Send a friend request", rowX + 29, cy + 15, ClientUi.MUTED, false)
            clippedHit(rowX, cy, rowW, ROW_H, top, bottom) { submitAdd() }
            cy += ROW_H + 8
        }

        if (groups.inGame.isEmpty() && groups.online.isEmpty() && groups.offline.isEmpty()) {
            if (!offerAdd) g.drawString(font, trim("Nobody called \"$query\"", rowW - 4), rowX + 2, cy + 4, ClientUi.MUTED, false)
            return cy + listScroll.roundToInt() + 16 - top
        }

        for ((label, list) in listOf("IN GAME" to groups.inGame, "ONLINE" to groups.online)) {
            if (list.isEmpty()) continue
            cy = sectionLabel(g, label, list.size, rowX, cy, rowW)
            for (friend in list) {
                if (cy + ROW_H >= top && cy <= bottom) drawFriendRow(g, friend, rowX, cy, rowW, top, bottom, mx, my)
                cy += ROW_H + ROW_GAP
            }
            cy += 6
        }
        if (groups.offline.isNotEmpty()) {
            cy = sectionLabel(g, "OFFLINE", groups.offline.size, rowX, cy, rowW)
            for (friend in groups.offline) {
                if (cy + OFFLINE_ROW_H >= top && cy <= bottom) drawOfflineRow(g, friend, rowX, cy, rowW, top, bottom, mx, my)
                cy += OFFLINE_ROW_H + ROW_GAP
            }
        }
        return cy + listScroll.roundToInt() - top
    }

    /** A small caps label, its count, and a hairline running out to the edge of the list. */
    private fun sectionLabel(g: GuiGraphics, rawLabel: String, count: Int, x: Int, y: Int, w: Int): Int {
        val label = RiverText.small(rawLabel)
        g.drawString(font, label, x + 2, y + 3, ClientUi.DIM, false)
        val countX = x + 2 + font.width(label) + 4
        val countText = count.toString()
        g.drawString(font, countText, countX, y + 3, ClientUi.alpha(ClientUi.DIM, 0.7f), false)
        val lineX = countX + font.width(countText) + 6
        if (lineX < x + w - 2) g.fill(lineX, y + 7, x + w - 2, y + 8, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        return y + 14
    }

    /** Selected rows take the accent, hovered ones a lift; anything else is the list's own surface. */
    private fun rowFill(isSelected: Boolean, hover: Float): Int {
        if (ClientUi.glassFrame) {
            val base = if (isSelected) ClientUi.alpha(ClientUi.ACCENT_A, 0.24f) else 0x00FFFFFF
            val lifted = if (isSelected) ClientUi.alpha(ClientUi.ACCENT_A, 0.32f) else ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f)
            return ClientUi.mix(base, lifted, hover)
        }
        val base = if (isSelected) ClientUi.over(ClientUi.alpha(ClientUi.ACCENT_A, 0.18f), ClientUi.PANEL) else ClientUi.PANEL
        return ClientUi.mix(base, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.05f), base), hover)
    }

    private fun drawFriendRow(g: GuiGraphics, friend: RiverSocial.Friend, x: Int, y: Int, w: Int, top: Int, bottom: Int, mx: Int, my: Int) {
        val hovered = inside(mx, my, x, y, w, ROW_H) && my in top until bottom
        val isSelected = friend.uuid == selectedUuid
        val anim = ClientUi.hover("fr:row:${friend.uuid}", hovered)
        val fill = rowFill(isSelected, anim)
        if (isSelected || anim > 0.01f) g.fill(x, y, x + w, y + ROW_H, fill)
        if (isSelected) g.fill(x, y, x + 2, y + ROW_H, ClientUi.ACCENT_A)

        PlayerHeads.draw(g, friend.uuid, friend.name, x + 7, y + 4, 18)
        drawPresenceDot(g, friend.status, x + 7 + 13, y + 4 + 13, fill)

        val unread = RiverSocial.unread(friend.uuid)
        val textW = w - 32 - (if (unread > 0) badgeWidth(unread) + 10 else 6)
        g.drawString(font, trim(friend.name, textW), x + 31, y + 4, ClientUi.TEXT, true)
        g.drawString(font, trim(rosterLine(friend), textW), x + 31, y + 15, presenceColor(friend, inList = true), false)
        if (unread > 0) drawBadge(g, unread, x + w - 6, y + 8)

        clippedHit(x, y, w, ROW_H, top, bottom) {
            if (selectedUuid != friend.uuid) openThread(friend.uuid)
        }
    }

    /** Offline friends get a one-line row: there is less to say about them and less to do. */
    private fun drawOfflineRow(g: GuiGraphics, friend: RiverSocial.Friend, x: Int, y: Int, w: Int, top: Int, bottom: Int, mx: Int, my: Int) {
        val hovered = inside(mx, my, x, y, w, OFFLINE_ROW_H) && my in top until bottom
        val isSelected = friend.uuid == selectedUuid
        val anim = ClientUi.hover("fr:row:${friend.uuid}", hovered)
        if (isSelected || anim > 0.01f) g.fill(x, y, x + w, y + OFFLINE_ROW_H, rowFill(isSelected, anim))
        if (isSelected) g.fill(x, y, x + 2, y + OFFLINE_ROW_H, ClientUi.ACCENT_A)

        PlayerHeads.draw(g, friend.uuid, friend.name, x + 9, y + 3, 14, dim = true)
        val unread = RiverSocial.unread(friend.uuid)
        val textW = w - 32 - (if (unread > 0) badgeWidth(unread) + 10 else 6)
        val color = if (isSelected || unread > 0) ClientUi.TEXT else ClientUi.mix(ClientUi.MUTED, ClientUi.TEXT, anim)
        g.drawString(font, trim(friend.name, textW), x + 31, y + 6, color, false)
        if (unread > 0) drawBadge(g, unread, x + w - 6, y + 5)

        clippedHit(x, y, w, OFFLINE_ROW_H, top, bottom) {
            if (selectedUuid != friend.uuid) openThread(friend.uuid)
        }
    }

    private fun drawRequestsList(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mx: Int, my: Int): Int {
        val list = RiverSocial.requests.filter { matches(it.name) }
        if (RiverSocial.requests.isEmpty()) {
            drawEmpty(g, x, top, w, bottom, "inbox", "No requests", "When someone adds you, it shows up here.")
            return 0
        }
        var cy = top + 2 - listScroll.roundToInt()
        val rowX = x + 6
        val rowW = w - 12
        cy = sectionLabel(g, "WANT TO BE FRIENDS", list.size, rowX, cy, rowW)
        for (request in list) {
            if (cy + REQUEST_H >= top && cy <= bottom) {
                val hovered = inside(mx, my, rowX, cy, rowW, REQUEST_H) && my in top until bottom
                val anim = ClientUi.hover("fr:req:${request.from}", hovered)
                // A card rather than a row: each one is waiting on a decision.
                g.fill(rowX, cy, rowX + rowW, cy + REQUEST_H, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.03f + 0.03f * anim), ClientUi.PANEL))
                ClientUi.drawRoundedBorder(g, rowX, cy, rowW, REQUEST_H, 0, ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.12f), ClientUi.BORDER), anim))
                PlayerHeads.draw(g, request.from, request.name, rowX + 7, cy + 6, 18)
                g.drawString(font, trim(request.name, rowW - 80), rowX + 31, cy + 6, ClientUi.TEXT, true)
                g.drawString(font, trim("Friend request", rowW - 80), rowX + 31, cy + 17, ClientUi.DIM, false)

                val acceptX = rowX + rowW - 46
                val declineX = rowX + rowW - 24
                iconButton(g, "check", acceptX, cy + 6, 18, mx, my, "fr:acc:${request.from}", primary = true, tip = "Accept", clipTop = top, clipBottom = bottom) {
                    RiverSocial.acceptRequest(request.from) { ok, message ->
                        say(if (ok) "You and ${request.name} are now friends." else message, ok)
                        if (ok) RiverSocial.refresh(force = true)
                    }
                }
                iconButton(g, "x", declineX, cy + 6, 18, mx, my, "fr:dec:${request.from}", danger = true, tip = "Decline", clipTop = top, clipBottom = bottom) {
                    RiverSocial.declineRequest(request.from) { ok, message -> say(if (ok) "Request declined." else message, ok) }
                }
            }
            cy += REQUEST_H + 4
        }
        return cy + listScroll.roundToInt() - top
    }

    private fun drawBlockedList(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, mx: Int, my: Int): Int {
        if (blocked.isEmpty()) {
            drawEmpty(g, x, top, w, bottom, "ban", "Nobody blocked", "Players you block can't message you or send you requests.")
            return 0
        }
        val list = blocked.filter { matches(it.name) }
        var cy = top + 2 - listScroll.roundToInt()
        val rowX = x + 6
        val rowW = w - 12
        cy = sectionLabel(g, "BLOCKED", list.size, rowX, cy, rowW)
        for (entry in list) {
            if (cy + ROW_H >= top && cy <= bottom) {
                PlayerHeads.draw(g, entry.uuid, entry.name, rowX + 7, cy + 4, 18, dim = true)
                val bw = font.width("Unblock") + 12
                val bx = rowX + rowW - bw - 3
                g.drawString(font, trim(entry.name, rowW - bw - 42), rowX + 31, cy + 9, ClientUi.MUTED, false)
                val hovered = inside(mx, my, bx, cy + 5, bw, 16) && my in top until bottom
                val anim = ClientUi.hover("fr:unb:${entry.uuid}", hovered)
                g.fill(bx, cy + 5, bx + bw, cy + 21, ClientUi.mix(ClientUi.PANEL_ALT, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.08f), ClientUi.PANEL_ALT), anim))
                ClientUi.drawRoundedBorder(g, bx, cy + 5, bw, 16, 0, ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.16f), ClientUi.BORDER), anim))
                g.drawString(font, "Unblock", bx + 6, cy + 9, if (hovered) ClientUi.TEXT else ClientUi.MUTED, false)
                clippedHit(bx, cy + 5, bw, 16, top, bottom) {
                    RiverSocial.unblock(entry.uuid) { ok, message ->
                        say(if (ok) "${entry.name} is unblocked." else message, ok)
                        if (ok) { refreshBlocked(); RiverSocial.refresh(force = true) }
                    }
                }
            }
            cy += ROW_H + ROW_GAP
        }
        return cy + listScroll.roundToInt() - top
    }

    private fun drawEmpty(g: GuiGraphics, x: Int, top: Int, w: Int, bottom: Int, icon: String, title: String, body: String) {
        val cx = x + w / 2
        var cy = top + max(8, (bottom - top) / 2 - 48)
        val size = 28
        g.fill(cx - size / 2, cy, cx + size / 2, cy + size, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.04f), ClientUi.PANEL))
        ClientUi.drawRoundedBorder(g, cx - size / 2, cy, size, size, 0, ClientUi.BORDER)
        RiverIcons.draw(g, icon, cx - 7, cy + 7, 14, ClientUi.MUTED)
        cy += size + 10
        centered(g, title, cx, cy, ClientUi.TEXT, true)
        cy += 13
        for (line in font.split(Component.literal(body), w - 28)) {
            g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.DIM, false)
            cy += 10
        }
    }

    // ------------------------------------------------------------------ conversation

    private fun drawClose(g: GuiGraphics, bx: Int, by: Int, mx: Int, my: Int) {
        val hovered = inside(mx, my, bx, by, 20, 20)
        val anim = ClientUi.hover("fr:close", hovered)
        if (anim > 0.01f) g.fill(bx, by, bx + 20, by + 20, ClientUi.alpha(ClientUi.PANEL_ALT, anim))
        RiverIcons.draw(g, "x", bx + 5, by + 5, 10, if (hovered) ClientUi.TEXT else ClientUi.MUTED)
        hit(bx, by, 20, 20) { onClose() }
        if (hovered) showTooltip("Close (Esc)", bx + 10, by + 24)
    }

    private fun drawConversation(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int) {
        val closeX = x + w - 28
        drawClose(g, closeX, y + 7, mx, my)

        val friend = selected
        if (friend == null) {
            chatRect = IntArray(4)
            g.fill(x, y + HEADER_H - 1, x + w - 1, y + HEADER_H, ClientUi.BORDER)
            drawNoConversation(g, x, y + HEADER_H, w, h - HEADER_H)
            return
        }

        // Header: who, and what they are doing.
        PlayerHeads.draw(g, friend.uuid, friend.name, x + 12, y + 7, 20, dim = !friend.online)
        drawPresenceDot(g, friend.status, x + 12 + 15, y + 7 + 15, ClientUi.PANEL_SOFT)
        val room = w - 40 - 96
        g.drawString(font, trim(friend.name, room), x + 40, y + 8, ClientUi.TEXT, true)
        g.drawString(font, trim(statusLine(friend), room), x + 40, y + 19, presenceColor(friend), false)

        g.fill(closeX - 5, y + 11, closeX - 4, y + 23, ClientUi.alpha(ClientUi.BORDER, 0.9f))
        var ax = closeX - 30
        iconButton(g, "more", ax, y + 7, 20, mx, my, "fr:more", active = menuOpen, tip = "More") {
            menuOpen = !menuOpen
            armed = ""
        }
        menuAnchorX = ax + 20
        menuAnchorY = y + 30
        if (friend.online && friend.server.isNotEmpty()) {
            ax -= 24
            iconButton(g, "copy", ax, y + 7, 20, mx, my, "fr:copy", tip = "Copy server address") {
                mc.keyboardHandler.clipboard = friend.server
                say("Copied ${friend.server}")
            }
        }
        g.fill(x, y + HEADER_H - 1, x + w - 1, y + HEADER_H, ClientUi.BORDER)

        // Composer docked at the bottom, messages fill the rest.
        val composerH = 22
        val composerY = y + h - composerH - 9
        val listTop = y + HEADER_H
        val listBottom = composerY - 9
        g.fill(x, listBottom, x + w - 1, listBottom + 1, ClientUi.alpha(ClientUi.BORDER, 0.8f))
        chatRect = intArrayOf(x, listTop, x + w, listBottom)
        drawMessages(g, friend, x, listTop, w, listBottom, mx, my)

        val fieldX = x + 10
        val fieldW = w - 20
        drawField(
            g, fieldX, composerY, fieldW, composerH, mx, my,
            text = composer,
            placeholder = "Message ${friend.name}",
            focused = focus == Focus.COMPOSER,
            icon = null,
            key = "fr:composer",
            limit = MESSAGE_LIMIT,
            reserve = 22
        ) { setFocus(Focus.COMPOSER) }

        // Send sits inside the field, at the end of what is being typed.
        val sendX = fieldX + fieldW - 20
        val sendY = composerY + 2
        val canSend = composer.isNotBlank()
        val hovered = inside(mx, my, sendX, sendY, 18, 18)
        val anim = ClientUi.hover("fr:send", hovered && canSend)
        val sendFill = if (canSend) ClientUi.mix(ClientUi.ACCENT_A, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.ACCENT_A), anim)
            else lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.04f), fieldBg())
        g.fill(sendX, sendY, sendX + 18, sendY + 18, sendFill)
        RiverIcons.draw(g, "send", sendX + 4, sendY + 4, 10, if (canSend) ClientUi.TEXT else ClientUi.DIM)
        hit(sendX, sendY, 18, 18) { submitMessage() }
        if (hovered && canSend) showTooltip("Send (Enter)", sendX + 9, composerY - 16)
    }

    private var menuAnchorX = 0
    private var menuAnchorY = 0
    private var lastMouseX = 0
    private var lastMouseY = 0

    private fun drawNoConversation(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        val cx = x + w / 2
        var cy = y + h / 2 - 56
        drawIconTile(g, "message", cx, cy, ClientUi.ACCENT_B)
        cy += 44
        val title = if (RiverSocial.friends.isEmpty()) "Add your friends to chat" else "Pick a friend to chat"
        centered(g, title, cx, cy, ClientUi.TEXT, true)
        cy += 14
        val body = if (RiverSocial.friends.isEmpty()) {
            "Search a name on the left to send a friend request."
        } else {
            "Messages show in game and in the launcher."
        }
        for (line in font.split(Component.literal(body), min(240, w - 40))) {
            g.drawString(font, line, cx - font.width(line) / 2, cy, ClientUi.DIM, false)
            cy += 10
        }
        cy += 12
        val keyHint = "${shortcutName()} opens this from anywhere"
        val hw = font.width(keyHint) + 24
        val hx = cx - hw / 2
        g.fill(hx, cy, hx + hw, cy + 16, glass(ClientUi.PANEL, 0.5f))
        ClientUi.drawRoundedBorder(g, hx, cy, hw, 16, 0, ClientUi.BORDER)
        RiverIcons.draw(g, "keyboard", hx + 6, cy + 4, 8, ClientUi.MUTED)
        g.drawString(font, keyHint, hx + 18, cy + 4, ClientUi.MUTED, false)

        // Requests waiting are the one thing worth pointing at from here.
        val pending = RiverSocial.requests.size
        if (pending > 0 && tab != Tab.REQUESTS) {
            cy += 24
            val label = if (pending == 1) "1 friend request waiting" else "$pending friend requests waiting"
            val bw = font.width(label) + 28
            val bx = cx - bw / 2
            val hovered = inside(lastMouseX, lastMouseY, bx, cy, bw, 18)
            val anim = ClientUi.hover("fr:reqchip", hovered)
            g.fill(bx, cy, bx + bw, cy + 18, ClientUi.mix(ClientUi.over(ClientUi.alpha(ClientUi.ACCENT_A, 0.16f), ClientUi.PANEL_SOFT), ClientUi.over(ClientUi.alpha(ClientUi.ACCENT_A, 0.26f), ClientUi.PANEL_SOFT), anim))
            ClientUi.drawRoundedBorder(g, bx, cy, bw, 18, 0, ClientUi.alpha(ClientUi.ACCENT_B, 0.7f))
            RiverIcons.draw(g, "inbox", bx + 8, cy + 5, 9, ClientUi.ACCENT_B)
            g.drawString(font, label, bx + 21, cy + 5, ClientUi.TEXT, false)
            hit(bx, cy, bw, 18) { selectTab(Tab.REQUESTS) }
        }
    }

    private fun shortcutName(): String {
        val key = RiverRuntime.configOrNull()?.friendsKey ?: -1
        if (key < 0 || key == GLFW.GLFW_KEY_TAB) return "Left Shift + Tab"
        val name = runCatching { GLFW.glfwGetKeyName(key, 0) }.getOrNull()?.uppercase() ?: "your key"
        return "Left Shift + $name"
    }

    private sealed class ChatRow(val height: Int) {
        class Intro : ChatRow(74)
        class Day(val label: String) : ChatRow(24)
        class NewLine : ChatRow(18)
        /** Who sent the group of bubbles below it, and when. */
        class Header(val message: RiverSocial.Message, val mine: Boolean) : ChatRow(12)
        /** One message. [first] is set on the first bubble of a group, which carries the sender's face. */
        class Bubble(
            val message: RiverSocial.Message,
            val lines: List<FormattedCharSequence>,
            val textWidth: Int,
            val mine: Boolean,
            val first: Boolean
        ) : ChatRow(lines.size * 10 + 8)
        class Gap : ChatRow(6)
    }

    // Wrapping every message is the expensive part of a frame here, so the layout is kept
    // until the thread, the width or the day changes.
    private var layoutRows: List<ChatRow> = emptyList()
    private var layoutThread: List<RiverSocial.Message>? = null
    private var layoutSize = -1
    private var layoutWidth = -1
    private var layoutUnread = -2
    private var layoutDay: LocalDate? = null

    private fun chatRows(textWidth: Int): List<ChatRow> {
        val today = LocalDate.now(ZoneId.systemDefault())
        if (messages !== layoutThread || messages.size != layoutSize || textWidth != layoutWidth ||
            firstUnread != layoutUnread || today != layoutDay
        ) {
            layoutRows = layoutMessages(textWidth, today)
            layoutThread = messages
            layoutSize = messages.size
            layoutWidth = textWidth
            layoutUnread = firstUnread
            layoutDay = today
        }
        return layoutRows
    }

    private fun layoutMessages(textWidth: Int, today: LocalDate): List<ChatRow> {
        val rows = ArrayList<ChatRow>()
        rows.add(ChatRow.Intro())
        var lastFrom = ""
        var lastAt = 0L
        var lastDay: LocalDate? = null
        val zone = ZoneId.systemDefault()
        for ((index, message) in messages.withIndex()) {
            val at = if (message.at > 0) message.at else System.currentTimeMillis()
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            var newGroup = message.from != lastFrom || at - lastAt > GROUP_WINDOW_MS
            if (day != lastDay) {
                rows.add(ChatRow.Day(dayLabel(day, today)))
                newGroup = true
                lastDay = day
            }
            if (index == firstUnread && index > 0) {
                rows.add(ChatRow.NewLine())
                newGroup = true
            }
            val mine = message.from == RiverSocial.selfUuid
            if (newGroup) {
                if (rows.lastOrNull() is ChatRow.Bubble) rows.add(ChatRow.Gap())
                rows.add(ChatRow.Header(message, mine))
            }
            val lines = font.split(Component.literal(message.text), textWidth).ifEmpty { listOf(FormattedCharSequence.EMPTY) }
            val widest = lines.maxOf { font.width(it) }
            rows.add(ChatRow.Bubble(message, lines, widest, mine, newGroup))
            lastFrom = message.from
            lastAt = at
        }
        return rows
    }

    private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_FORMAT.format(day)
    }

    private fun time(at: Long): String =
        TIME_FORMAT.format(Instant.ofEpochMilli(if (at > 0) at else System.currentTimeMillis()).atZone(ZoneId.systemDefault()))

    private fun drawMessages(g: GuiGraphics, friend: RiverSocial.Friend, x: Int, top: Int, w: Int, bottom: Int, mx: Int, my: Int) {
        if (loadingThread) {
            drawSpinner(g, x + w / 2, top + (bottom - top) / 2 - 6)
            centered(g, "Loading messages", x + w / 2, top + (bottom - top) / 2 + 8, ClientUi.DIM, false)
            return
        }

        // Bubbles never run the full width, so a long message still reads as one side's.
        val textWidth = max(60, min((w * 0.68f).toInt(), w - BUBBLE_GUTTER - 24) - BUBBLE_PAD * 2)
        val rows = chatRows(textWidth)
        val contentH = rows.sumOf { it.height } + 6
        val viewH = bottom - top
        chatMaxScroll = max(0, contentH - viewH)
        chatScrollTarget = chatScrollTarget.coerceIn(0f, chatMaxScroll.toFloat())
        chatScroll = ClientUi.animate("fr:chat", chatScrollTarget, 18f)

        // Bottom-aligned like any chat: the newest message sits just above the composer.
        var cy = bottom - contentH + chatScroll.roundToInt() + 2
        g.enableScissor(x + 1, top, x + w - 1, bottom)
        for (row in rows) {
            if (cy + row.height >= top && cy <= bottom) {
                when (row) {
                    is ChatRow.Intro -> {
                        val cx = x + w / 2
                        ClientUi.drawRoundedBorder(g, cx - 18, cy + 6, 36, 36, 0, ClientUi.BORDER)
                        PlayerHeads.draw(g, friend.uuid, friend.name, cx - 16, cy + 8, 32, dim = !friend.online)
                        centered(g, trim(friend.name, w - 40), cx, cy + 48, ClientUi.TEXT, true)
                        val line = if (messages.isEmpty()) "No messages yet." else "Start of your chat with ${friend.name}."
                        centered(g, trim(line, w - 30), cx, cy + 60, ClientUi.DIM, false)
                    }
                    is ChatRow.Day -> {
                        val chipW = font.width(row.label) + 12
                        val chipX = x + w / 2 - chipW / 2
                        val ly = cy + 12
                        g.fill(x + 14, ly, chipX - 6, ly + 1, ClientUi.alpha(ClientUi.BORDER, 0.9f))
                        g.fill(chipX + chipW + 6, ly, x + w - 14, ly + 1, ClientUi.alpha(ClientUi.BORDER, 0.9f))
                        g.fill(chipX, cy + 6, chipX + chipW, cy + 18, lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.04f), ClientUi.PANEL_SOFT))
                        ClientUi.drawRoundedBorder(g, chipX, cy + 6, chipW, 12, 0, ClientUi.BORDER)
                        g.drawString(font, row.label, chipX + 6, cy + 8, ClientUi.MUTED, false)
                    }
                    is ChatRow.NewLine -> {
                        val label = "NEW"
                        val chipW = font.width(label) + 8
                        val chipX = x + w - 14 - chipW
                        g.fill(x + 14, cy + 8, chipX - 4, cy + 9, ClientUi.alpha(ClientUi.DANGER, 0.6f))
                        g.fill(chipX, cy + 3, chipX + chipW, cy + 14, lay(ClientUi.alpha(ClientUi.DANGER, 0.18f), ClientUi.PANEL_SOFT))
                        g.drawString(font, label, chipX + 4, cy + 5, ClientUi.DANGER, false)
                    }
                    is ChatRow.Header -> {
                        val at = time(row.message.at)
                        if (row.mine) {
                            val right = x + w - 12
                            g.drawString(font, at, right - font.width(at), cy + 1, ClientUi.DIM, false)
                            g.drawString(font, "You", right - font.width(at) - 5 - font.width("You"), cy + 1, ClientUi.ACCENT_B, false)
                        } else {
                            val name = row.message.fromName.ifEmpty { friend.name }
                            g.drawString(font, name, x + BUBBLE_GUTTER, cy + 1, ClientUi.TEXT, true)
                            g.drawString(font, at, x + BUBBLE_GUTTER + font.width(name) + 5, cy + 1, ClientUi.DIM, false)
                        }
                    }
                    is ChatRow.Bubble -> drawBubble(g, row, friend, x, cy, w, top, bottom, mx, my)
                    is ChatRow.Gap -> Unit
                }
            }
            cy += row.height
        }
        g.disableScissor()

        // Messages scrolled up under the header fade out instead of being cut in half: a
        // solid band hides the cut edge itself, and the fade starts below it.
        if (!ClientUi.glassFrame && bottom - contentH + chatScroll.roundToInt() + 2 < top) {
            g.fill(x + 1, top, x + w - 1, top + 4, ClientUi.PANEL_SOFT)
            g.fillGradient(x + 1, top + 4, x + w - 1, top + 22, ClientUi.PANEL_SOFT, ClientUi.alpha(ClientUi.PANEL_SOFT, 0f))
        }

        // Scrollbar, and a way back to the newest message once you have scrolled away from it.
        if (chatMaxScroll > 0) {
            val thumbH = max(16, viewH * viewH / max(1, contentH))
            val thumbY = bottom - thumbH - ((viewH - thumbH) * (chatScroll / chatMaxScroll)).roundToInt()
            g.fill(x + w - 5, thumbY, x + w - 3, thumbY + thumbH, ClientUi.alpha(ClientUi.MUTED, 0.5f))
        }
        if (chatScrollTarget > 40f) {
            val label = "Jump to latest"
            val bw = font.width(label) + 26
            val bx = x + w / 2 - bw / 2
            val by = bottom - 24
            val hovered = inside(mx, my, bx, by, bw, 18)
            g.fill(bx, by, bx + bw, by + 18, if (hovered) ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.12f), ClientUi.ACCENT_A) else ClientUi.ACCENT_A)
            g.fill(bx, by, bx + bw, by + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
            RiverIcons.draw(g, "chevron_down", bx + 7, by + 5, 9, ClientUi.TEXT)
            g.drawString(font, label, bx + 19, by + 5, ClientUi.TEXT, false)
            hit(bx, by, bw, 18) { chatScrollTarget = 0f }
        }
    }

    private fun drawBubble(g: GuiGraphics, row: ChatRow.Bubble, friend: RiverSocial.Friend, x: Int, y: Int, w: Int, top: Int, bottom: Int, mx: Int, my: Int) {
        val bw = row.textWidth + BUBBLE_PAD * 2
        val bh = row.height - 2
        val bx = if (row.mine) x + w - 12 - bw else x + BUBBLE_GUTTER
        val hovered = inside(mx, my, bx, y, bw, bh) && my in top until bottom

        if (row.mine) {
            // Yours: the accent, with the same lit top edge River's buttons have.
            val fill = lay(ClientUi.alpha(ClientUi.ACCENT_A, 0.72f), ClientUi.PANEL_SOFT)
            g.fill(bx, y, bx + bw, y + bh, fill)
            if (hovered) g.fill(bx, y, bx + bw, y + bh, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f))
            g.fill(bx, y, bx + bw, y + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
        } else {
            // Theirs: a card, and their face beside the first bubble of each group.
            val fill = glass(ClientUi.PANEL_ALT, 0.7f)
            g.fill(bx, y, bx + bw, y + bh, if (hovered) lay(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.05f), fill) else fill)
            if (hovered && ClientUi.glassFrame) g.fill(bx, y, bx + bw, y + bh, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.05f))
            ClientUi.drawRoundedBorder(g, bx, y, bw, bh, 0, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.05f), ClientUi.BORDER))
            if (row.first) {
                val name = row.message.fromName.ifEmpty { friend.name }
                PlayerHeads.draw(g, row.message.from, name, x + 12, y, 16)
            }
        }
        row.lines.forEachIndexed { i, line ->
            g.drawString(font, line, bx + BUBBLE_PAD, y + 4 + i * 10, ClientUi.TEXT, false)
        }

        // Follow-up bubbles carry no header, so their time shows beside them on hover.
        if (hovered && !row.first) {
            val t = time(row.message.at)
            val tx = if (row.mine) bx - 6 - font.width(t) else bx + bw + 6
            g.drawString(font, t, tx, y + (bh - 8) / 2, ClientUi.DIM, false)
        }
    }

    private fun selfName(): String = RiverSocial.selfName.ifEmpty { mc.user.name }

    // ------------------------------------------------------------------ menu

    private fun drawMenu(g: GuiGraphics, mx: Int, my: Int) {
        val friend = selected ?: run { menuOpen = false; return }
        val items = listOf(
            Triple("remove", "user_minus", "Remove friend"),
            Triple("block", "ban", "Block"),
            Triple("report", "flag", "Report")
        )
        val armedText = mapOf(
            "remove" to "Click again to remove",
            "block" to "Click again to block",
            "report" to "Click again to report"
        )
        if (armed.isNotEmpty() && System.currentTimeMillis() - armedAt > 4000) armed = ""

        val itemH = 20
        // Wide enough for the longest thing it can say, the armed confirmations included.
        val mw = max(132, (items.map { it.third } + armedText.values).maxOf { font.width(it) } + 38)
        val mh = items.size * itemH + 8
        val x = menuAnchorX - mw
        val y = menuAnchorY
        menuHitFloor = hits.size
        // Swallows clicks on the menu's own padding so they never reach what is underneath.
        hit(x, y, mw, mh) {}

        g.fill(x, y, x + mw, y + mh, ClientUi.PANEL_ALT)
        ClientUi.drawRoundedBorder(g, x, y, mw, mh, 0, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.10f), ClientUi.BORDER))
        g.fill(x + 1, y + 1, x + mw - 1, y + 2, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f))
        var iy = y + 4
        for ((id, icon, label) in items) {
            val hovered = inside(mx, my, x + 4, iy, mw - 8, itemH)
            val isArmed = armed == id
            val color = if (isArmed || (hovered && id != "report")) ClientUi.DANGER else if (hovered) ClientUi.TEXT else ClientUi.MUTED
            if (hovered || isArmed) {
                g.fill(x + 4, iy, x + mw - 4, iy + itemH, if (isArmed) ClientUi.over(ClientUi.alpha(ClientUi.DANGER, 0.14f), ClientUi.PANEL_ALT) else ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.06f), ClientUi.PANEL_ALT))
            }
            RiverIcons.draw(g, icon, x + 10, iy + 5, 10, color)
            g.drawString(font, if (isArmed) armedText[id] ?: label else label, x + 26, iy + 6, color, false)
            hit(x + 4, iy, mw - 8, itemH) {
                if (armed != id) {
                    armed = id
                    armedAt = System.currentTimeMillis()
                } else {
                    runAction(id, friend)
                }
            }
            iy += itemH
        }
    }

    private fun runAction(id: String, friend: RiverSocial.Friend) {
        menuOpen = false
        armed = ""
        when (id) {
            "remove" -> RiverSocial.removeFriend(friend.uuid) { ok, message ->
                say(if (ok) "${friend.name} was removed from your friends." else message, ok)
                if (ok) clearSelection()
            }
            "block" -> RiverSocial.block(friend.uuid) { ok, message ->
                say(if (ok) "${friend.name} is blocked." else message, ok)
                if (ok) { clearSelection(); refreshBlocked() }
            }
            "report" -> RiverSocial.report(friend.uuid, "Reported from the in-game friends screen") { ok, message ->
                say(if (ok) "Report sent. River's team will look at it." else message, ok)
            }
        }
    }

    private fun clearSelection() {
        selectedUuid = null
        threadFor = null
        messages = emptyList()
        if (focus == Focus.COMPOSER) setFocus(Focus.NONE)
    }

    // ------------------------------------------------------------------ shared pieces

    private fun drawPresenceDot(g: GuiGraphics, status: String, x: Int, y: Int, ring: Int) {
        val color = when (status) {
            "online" -> ClientUi.POSITIVE
            "idle" -> ClientUi.WARNING
            "dnd" -> ClientUi.DANGER
            else -> return
        }
        g.fill(x - 1, y - 1, x + 6, y + 6, ring)
        g.fill(x, y, x + 5, y + 5, color)
    }

    private fun badgeLabel(count: Int) = if (count > 9) "9+" else count.toString()

    private fun badgeWidth(count: Int) = max(10, font.width(badgeLabel(count)) + 5)

    /** An accent count, its right edge at [right]. [ring] cuts it out of whatever it overlaps. */
    private fun drawBadge(g: GuiGraphics, count: Int, right: Int, top: Int, ring: Int? = null) {
        val label = badgeLabel(count)
        val bw = badgeWidth(count)
        val bx = right - bw
        if (ring != null) g.fill(bx - 1, top - 1, right + 1, top + 11, ring)
        g.fill(bx, top, right, top + 10, ClientUi.ACCENT_A)
        g.drawString(font, label, bx + (bw - font.width(label) + 1) / 2, top + 1, ClientUi.TEXT, false)
    }

    private fun rosterLine(friend: RiverSocial.Friend): String = when {
        !friend.online -> "Offline"
        friend.server.isNotEmpty() -> friend.server
        friend.status == "idle" -> "Idle"
        friend.status == "dnd" -> "Do not disturb"
        else -> "Online"
    }

    private fun statusLine(friend: RiverSocial.Friend): String = when {
        !friend.online -> "Offline"
        friend.status == "dnd" -> if (friend.server.isNotEmpty()) "Do not disturb · ${friend.server}" else "Do not disturb"
        friend.status == "idle" -> if (friend.server.isNotEmpty()) "Idle · ${friend.server}" else "Idle"
        friend.server.isNotEmpty() -> "Playing on ${friend.server}"
        else -> "Online"
    }

    /**
     * The colour of a friend's status text: what they are doing, readable before it is read.
     * In the list a server name always takes the accent - the dot already says idle or do
     * not disturb, and a server in red reads as an error.
     */
    private fun presenceColor(friend: RiverSocial.Friend, inList: Boolean = false): Int = when {
        !friend.online -> ClientUi.DIM
        inList && friend.server.isNotEmpty() -> ClientUi.mix(ClientUi.ACCENT_B, ClientUi.MUTED, 0.3f)
        friend.status == "dnd" -> ClientUi.alpha(ClientUi.DANGER, 0.85f)
        friend.status == "idle" -> ClientUi.alpha(ClientUi.WARNING, 0.85f)
        friend.server.isNotEmpty() -> ClientUi.mix(ClientUi.ACCENT_B, ClientUi.MUTED, 0.3f)
        else -> ClientUi.alpha(ClientUi.POSITIVE, 0.8f)
    }

    private fun fieldBg(): Int = if (ClientUi.glassFrame) ClientUi.alpha(0xFF000000.toInt(), 0.38f) else ClientUi.over(ClientUi.alpha(0xFF000000.toInt(), 0.32f), ClientUi.PANEL)

    private fun drawField(
        g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, mx: Int, my: Int,
        text: String, placeholder: String, focused: Boolean, icon: String?, key: String,
        limit: Int = 0, reserve: Int = 0, onFocus: () -> Unit
    ) {
        val hovered = inside(mx, my, x, y, w, h)
        val anim = ClientUi.hover(key, hovered || focused)
        g.fill(x, y, x + w, y + h, fieldBg())
        val border = if (focused) ClientUi.alpha(ClientUi.ACCENT_B, 0.85f)
            else ClientUi.mix(ClientUi.BORDER, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f), ClientUi.BORDER), anim)
        ClientUi.drawRoundedBorder(g, x, y, w, h, 0, border)

        var tx = x + 7
        if (icon != null) {
            RiverIcons.draw(g, icon, x + 6, y + (h - 9) / 2, 9, if (focused) ClientUi.ACCENT_B else ClientUi.DIM)
            tx = x + 20
        }
        val ty = y + (h - 8) / 2
        val counter = if (limit > 0 && text.length > limit - 56) "${text.length}/$limit" else ""
        val counterW = if (counter.isNotEmpty()) font.width(counter) + 8 else 0
        val room = x + w - 6 - reserve - counterW - tx
        if (text.isEmpty() && !focused) {
            g.drawString(font, trim(placeholder, room), tx, ty, ClientUi.DIM, false)
        } else if (text.isEmpty()) {
            g.drawString(font, trim(placeholder, room), tx, ty, ClientUi.alpha(ClientUi.DIM, 0.6f), false)
        }
        // A long message shows its end, where the typing is happening.
        val visible = tail(text, room - 2)
        if (visible.isNotEmpty()) g.drawString(font, visible, tx, ty, ClientUi.TEXT, false)
        if (focused && ((System.currentTimeMillis() - focusChangedAt) / 530) % 2 == 0L) {
            val cx = tx + font.width(visible) + 1
            g.fill(cx, ty - 1, cx + 1, ty + 9, ClientUi.TEXT)
        }
        if (counter.isNotEmpty()) {
            val over = text.length >= limit
            g.drawString(font, counter, x + w - 6 - reserve - font.width(counter), ty, if (over) ClientUi.DANGER else ClientUi.DIM, false)
        }
        hit(x, y, w, h, onFocus)
    }

    private fun iconButton(
        g: GuiGraphics, icon: String, x: Int, y: Int, size: Int, mx: Int, my: Int, key: String,
        primary: Boolean = false, danger: Boolean = false, active: Boolean = false, tip: String? = null,
        clipTop: Int = Int.MIN_VALUE, clipBottom: Int = Int.MAX_VALUE,
        onClick: () -> Unit
    ) {
        val hovered = inside(mx, my, x, y, size, size) && my >= clipTop && my < clipBottom
        val anim = ClientUi.hover(key, hovered || active)
        val base = when {
            primary -> ClientUi.ACCENT_A
            else -> ClientUi.PANEL_ALT
        }
        val fill = when {
            danger && hovered -> ClientUi.over(ClientUi.alpha(ClientUi.DANGER, 0.22f), ClientUi.PANEL_ALT)
            else -> ClientUi.mix(base, ClientUi.over(ClientUi.alpha(0xFFFFFFFF.toInt(), if (primary) 0.14f else 0.08f), base), anim)
        }
        if (primary || danger || anim > 0.01f || active) g.fill(x, y, x + size, y + size, fill)
        if (primary) g.fill(x, y, x + size, y + 1, ClientUi.alpha(0xFFFFFFFF.toInt(), 0.14f))
        val iconSize = if (size >= 20) 12 else 10
        val color = when {
            danger && hovered -> ClientUi.DANGER
            primary || hovered || active -> ClientUi.TEXT
            else -> ClientUi.MUTED
        }
        RiverIcons.draw(g, icon, x + (size - iconSize) / 2, y + (size - iconSize) / 2, iconSize, color)
        clippedHit(x, y, size, size, clipTop, clipBottom, onClick)
        if (hovered && tip != null) showTooltip(tip, x + size / 2, y + size + 4)
    }

    private fun showTooltip(text: String, centerX: Int, y: Int) {
        tooltip = text
        tooltipX = centerX
        tooltipY = y
    }

    /** A tooltip that starts at [left] instead of centring on a point, for the rail. */
    private fun showTooltipRight(text: String, left: Int, y: Int) {
        showTooltip(text, left + (font.width(text) + 10) / 2, y)
    }

    private fun drawTooltip(g: GuiGraphics) {
        val text = tooltip ?: return
        if (menuOpen) return
        val w = font.width(text) + 10
        val x = (tooltipX - w / 2).coerceIn(2, width - w - 2)
        val y = tooltipY.coerceIn(2, height - 16)
        g.fill(x, y, x + w, y + 14, 0xF00C0D11.toInt())
        ClientUi.drawRoundedBorder(g, x, y, w, 14, 0, ClientUi.BORDER)
        g.drawString(font, text, x + 5, y + 3, ClientUi.TEXT, false)
    }

    private fun drawNotice(g: GuiGraphics, wx: Int, wy: Int, ww: Int, wh: Int, now: Long) {
        if (notice.isEmpty()) return
        val age = max(0L, now - noticeAt)
        if (age > NOTICE_MS) return
        val fade = ((NOTICE_MS - age) / 300f).coerceIn(0f, 1f)
        val rise = ((age / 160f).coerceIn(0f, 1f))
        val text = trim(notice, ww - 80)
        val w = font.width(text) + 30
        val x = wx + (ww - w) / 2
        val y = wy + wh - 66 - (4 * rise).roundToInt()
        val accent = if (noticeOk) ClientUi.POSITIVE else ClientUi.DANGER
        g.fill(x, y, x + w, y + 20, ClientUi.alpha(ClientUi.PANEL_ALT, fade))
        ClientUi.drawRoundedBorder(g, x, y, w, 20, 0, ClientUi.alpha(accent, 0.55f * fade))
        g.fill(x, y, x + 2, y + 20, ClientUi.alpha(accent, fade))
        RiverIcons.draw(g, if (noticeOk) "check" else "x", x + 8, y + 6, 8, ClientUi.alpha(accent, fade))
        g.drawString(font, text, x + 20, y + 6, ClientUi.alpha(ClientUi.TEXT, fade), false)
    }

    private fun centered(g: GuiGraphics, text: String, cx: Int, y: Int, color: Int, shadow: Boolean) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, shadow)
    }

    private fun trim(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun tail(text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width(t) > maxWidth) t = t.drop(1)
        return t
    }

    // ------------------------------------------------------------------ actions

    private fun submitAdd() {
        val name = search.trim()
        if (name.length < 3) return
        if (!name.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            say("That isn't a Minecraft name.", false)
            return
        }
        search = ""
        RiverSocial.addFriend(name) { ok, message ->
            say(if (ok) "Friend request sent to $name." else message, ok)
            if (ok) RiverSocial.refresh(force = true)
        }
    }

    private fun submitMessage() {
        val friend = selected ?: return
        val text = composer.trim()
        if (text.isEmpty()) return
        composer = ""
        chatScrollTarget = 0f
        RiverSocial.send(friend.uuid, text) { ok, message ->
            if (ok) refreshThread() else {
                say(message, false)
                // Put it back so nothing typed is lost to a failed send.
                if (composer.isEmpty()) composer = text
            }
        }
    }

    /** The friends currently listed, in order, for moving the selection with the arrow keys. */
    private fun visibleFriends(): List<RiverSocial.Friend> = groups().all

    private fun moveSelection(delta: Int) {
        val list = visibleFriends()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.uuid == selectedUuid }
        val next = if (index < 0) (if (delta > 0) 0 else list.size - 1) else (index + delta).coerceIn(0, list.size - 1)
        if (list[next].uuid != selectedUuid) openThread(list[next].uuid)
    }

    private fun typeInto(text: String) {
        val clean = text.filter { it >= ' ' && it != '§' && it != '\u007F' }
        if (clean.isEmpty()) return
        when (focus) {
            Focus.SEARCH -> search = (search + clean).take(16)
            Focus.COMPOSER -> composer = (composer + clean).take(MESSAGE_LIMIT)
            Focus.NONE -> Unit
        }
    }

    private fun backspace(word: Boolean) {
        fun cut(s: String): String = if (!word) s.dropLast(1) else s.trimEnd().dropLastWhile { it != ' ' }
        when (focus) {
            Focus.SEARCH -> search = cut(search)
            Focus.COMPOSER -> composer = cut(composer)
            Focus.NONE -> Unit
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
        // A dev screenshot run must not react to the mouse of whoever is using the machine.
        if (dev.wyz.clientcore.dev.DevShot.active) return true
        val previousFocus = focus
        setFocus(Focus.NONE)
        // With the menu open only the menu takes clicks; anywhere else just closes it.
        val floor = if (menuOpen && menuHitFloor >= 0) menuHitFloor else 0
        for (i in hits.indices.reversed()) {
            if (i < floor) break
            val region = hits[i]
            if (mx >= region.x1 && mx < region.x2 && my >= region.y1 && my < region.y2) {
                region.onClick()
                return true
            }
        }
        if (menuOpen) {
            menuOpen = false
            armed = ""
            setFocus(previousFocus)
            return true
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val mx = mouseX.toInt()
        val my = mouseY.toInt()
        if (rosterRect[2] > 0 && mx in rosterRect[0] until rosterRect[2] && my in rosterRect[1] until rosterRect[3]) {
            listScrollTarget = (listScrollTarget - (deltaY * 24).toFloat()).coerceIn(0f, listMaxScroll.toFloat())
        } else if (chatRect[2] > 0 && mx in chatRect[0] until chatRect[2] && my in chatRect[1] until chatRect[3]) {
            chatScrollTarget = (chatScrollTarget + (deltaY * 24).toFloat()).coerceIn(0f, chatMaxScroll.toFloat())
        }
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
        val ctrl = (modifiers and GLFW.GLFW_MOD_CONTROL) != 0

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            when {
                menuOpen -> { menuOpen = false; armed = "" }
                focus != Focus.NONE -> setFocus(Focus.NONE)
                else -> onClose()
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            setFocus(if (focus == Focus.SEARCH && selected != null) Focus.COMPOSER else Focus.SEARCH)
            return true
        }
        if (ctrl && key == GLFW.GLFW_KEY_F && RiverSocial.signedIn) {
            setFocus(Focus.SEARCH)
            return true
        }
        if (focus != Focus.NONE) {
            when {
                key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER -> {
                    if (focus == Focus.COMPOSER) submitMessage()
                    else if (tab == Tab.FRIENDS) {
                        val found = visibleFriends()
                        val exact = found.firstOrNull { it.name.equals(search.trim(), ignoreCase = true) }
                        when {
                            exact != null -> { openThread(exact.uuid); search = "" }
                            found.size == 1 -> { openThread(found[0].uuid); search = "" }
                            else -> submitAdd()
                        }
                    }
                }
                key == GLFW.GLFW_KEY_BACKSPACE -> backspace(word = ctrl)
                ctrl && key == GLFW.GLFW_KEY_V -> typeInto(mc.keyboardHandler.clipboard.replace('\n', ' ').replace('\r', ' '))
                ctrl && key == GLFW.GLFW_KEY_A -> Unit
                (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) && focus == Focus.SEARCH && tab == Tab.FRIENDS ->
                    moveSelection(if (key == GLFW.GLFW_KEY_DOWN) 1 else -1)
            }
            return true
        }
        if (tab == Tab.FRIENDS && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
            moveSelection(if (key == GLFW.GLFW_KEY_DOWN) 1 else -1)
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
        val text = event.codepointAsString()
//?} else {
/*    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        val text = codePoint.toString()
*///?}
        // Typing with nothing focused goes where it obviously should: into the open
        // conversation if there is one, otherwise into search.
        if (focus == Focus.NONE && RiverSocial.signedIn) {
            setFocus(if (selected != null) Focus.COMPOSER else Focus.SEARCH)
        }
        if (focus != Focus.NONE) {
            typeInto(text)
            return true
        }
//? if >=1.21.9 {
        return super.charTyped(event)
//?} else {
/*        return super.charTyped(codePoint, modifiers)
*///?}
    }

    /** Whether the screen this was opened from was drawn behind it this frame. */
    private var parentShown = false

    /** The screen this was opened from, behind it, when there is one. */
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
        mc.setScreen(parent)
    }

    /** Puts the screen in a given state for the dev screenshots (see dev.DevShot). */
    internal fun preview(searchText: String = "", armedMenu: String? = null, noticeText: String? = null, noticeIsError: Boolean = false) {
        search = searchText
        if (searchText.isNotEmpty()) setFocus(Focus.SEARCH)
        if (armedMenu != null) {
            menuOpen = true
            armed = armedMenu
            armedAt = System.currentTimeMillis() + 60_000L
        }
        if (noticeText != null) {
            say(noticeText, ok = !noticeIsError)
            noticeAt = System.currentTimeMillis() + 60_000L
        }
    }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val RAIL_W = 36
        const val HEADER_H = 34
        const val ROW_H = 26
        const val OFFLINE_ROW_H = 20
        const val REQUEST_H = 30
        const val ROW_GAP = 2
        /** Where a friend's bubbles start: their face sits in the space to the left. */
        const val BUBBLE_GUTTER = 34
        const val BUBBLE_PAD = 6
        const val MESSAGE_LIMIT = 256
        const val GROUP_WINDOW_MS = 5 * 60_000L
        const val NOTICE_MS = 3600L
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}
