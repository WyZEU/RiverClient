package dev.wyz.clientcore.ui.screen

import dev.wyz.clientcore.net.RiverSocial
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverIcons
import dev.wyz.clientcore.ui.RiverScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * Friends, pending requests and direct messages.
 *
 * Two panes: the roster on the left (incoming requests pinned above the friends list,
 * each with Accept / Decline) and the conversation with whoever is selected on the right.
 * Immediate-mode like the rest of River's screens, with a hit list for clicks.
 */
class RiverFriendsScreen(private val parent: Screen?) : Screen(Component.literal("Friends")), RiverScreen {

    private val mcClient = Minecraft.getInstance()

    private var selected: RiverSocial.Friend? = null
    private var messages: List<RiverSocial.Message> = emptyList()
    private var loadingThread = false

    private var addInput = ""
    private var addFocused = false
    private var chatInput = ""
    private var chatFocused = false

    private var notice = ""
    private var noticeAt = 0L
    private var showingBlocked = false
    private var blocked: List<RiverSocial.Friend> = emptyList()
    private var listScroll = 0
    private var chatScroll = 0
    private var lastPoll = 0L

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val onClick: () -> Unit)
    private val hits = ArrayList<Hit>()

    private fun hit(x: Int, y: Int, w: Int, h: Int, onClick: () -> Unit) {
        hits.add(Hit(x, y, x + w, y + h, onClick))
    }

    private fun say(message: String) {
        notice = message
        noticeAt = System.currentTimeMillis()
    }

    override fun init() {
        // Signing in is a Mojang round trip, so it only happens when the screen is opened
        // rather than at startup for players who never use this.
        RiverSocial.ensureSession { ok -> if (ok) { RiverSocial.refresh(force = true); refreshBlocked() } }
    }

    private fun openThread(friend: RiverSocial.Friend) {
        selected = friend
        messages = emptyList()
        loadingThread = true
        chatScroll = 0
        RiverSocial.history(friend.uuid) { list ->
            if (mcClient.screen !== this) return@history
            if (selected?.uuid == friend.uuid) {
                messages = list
                loadingThread = false
            }
        }
    }

    private fun refreshThread() {
        val friend = selected ?: return
        RiverSocial.history(friend.uuid) { list ->
            if (mcClient.screen === this && selected?.uuid == friend.uuid) messages = list
        }
    }

    // ------------------------------------------------------------------ render

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        ClientUi.drawBackdrop(g, width, height)
        ClientUi.beginFrame()
        hits.clear()

        // Poll while the screen is open so requests and new messages appear on their own.
        val now = System.currentTimeMillis()
        if (RiverSocial.signedIn && now - lastPoll > RiverSocial.REFRESH_INTERVAL_MS) {
            lastPoll = now
            RiverSocial.refresh()
            refreshThread()
        }

        val pad = 20
        val top = 16
        val right = width - pad

        RiverIcons.draw(g, "users", pad, top, 16, ClientUi.ACCENT_B)
        g.drawString(font, "Friends", pad + 22, top + 3, ClientUi.TEXT, true)
        val closeHovered = mouseX in (right - 22)..right && mouseY in top..(top + 22)
        RiverIcons.draw(g, "x", right - 18, top + 3, 12, if (closeHovered) ClientUi.TEXT else ClientUi.DIM)
        hit(right - 22, top - 2, 26, 26) { onClose() }

        if (!RiverSocial.signedIn) {
            drawSignedOut(g, pad, top + 40, right - pad)
            return
        }

        val bodyTop = top + 30
        val bodyBottom = height - 16
        val listW = min(280, ((right - pad) * 0.38f).toInt())

        drawRoster(g, pad, bodyTop, listW, bodyBottom, mouseX, mouseY)
        drawConversation(g, pad + listW + 12, bodyTop, right - (pad + listW + 12), bodyBottom, mouseX, mouseY)

        if (notice.isNotEmpty() && now - noticeAt < 4000) {
            g.drawString(font, notice, pad, height - 10, ClientUi.MUTED, true)
        }
    }

    private fun drawSignedOut(g: GuiGraphics, x: Int, y: Int, w: Int) {
        ClientUi.drawPanel(g, x, y, w, 90)
        val message = when {
            RiverSocial.signingIn -> "Verifying your Minecraft account with Mojang..."
            RiverSocial.error.isNotEmpty() -> RiverSocial.error
            else -> "Connecting to River social..."
        }
        g.drawString(font, message, x + 14, y + 20, ClientUi.TEXT, true)
        g.drawString(font, "River verifies your account with Mojang so nobody can message as you.", x + 14, y + 36, ClientUi.DIM, true)

        if (!RiverSocial.signingIn) {
            val bw = 110
            val bx = x + 14
            val by = y + 56
            ClientUi.drawFlatButton(g, font, bx, by, bw, 20, "Try again", false, true)
            hit(bx, by, bw, 20) { RiverSocial.ensureSession() }
        }
    }

    // ------------------------------------------------------------------ roster

    private fun drawRoster(g: GuiGraphics, x: Int, y: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        ClientUi.drawPanel(g, x, y, w, bottom - y)

        // Add-friend field.
        val fieldY = y + 10
        val fieldW = w - 20 - 54
        val fieldHovered = mouseX in (x + 10)..(x + 10 + fieldW) && mouseY in fieldY..(fieldY + 20)
        ClientUi.drawListRow(g, x + 10, fieldY, fieldW, 20, ClientUi.hover("fr:add", fieldHovered || addFocused), addFocused)
        val placeholder = addInput.isEmpty() && !addFocused
        val caret = if (addFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        g.drawString(
            font,
            if (placeholder) "Add by username" else addInput + caret,
            x + 16, fieldY + 6,
            if (placeholder) ClientUi.DIM else ClientUi.TEXT, true
        )
        hit(x + 10, fieldY, fieldW, 20) { addFocused = true; chatFocused = false }

        val addX = x + 10 + fieldW + 6
        val addHovered = mouseX in addX..(addX + 48) && mouseY in fieldY..(fieldY + 20)
        ClientUi.drawFlatButton(g, font, addX, fieldY, 48, 20, "Add", addHovered, true)
        hit(addX, fieldY, 48, 20) { submitAdd() }

        // Blocked players are reachable from here rather than a separate screen: blocking
        // is easy to do in a moment of annoyance, so undoing it should be equally close.
        val toggleY = fieldY + 26
        val toggleLabel = if (showingBlocked) "Back to friends" else "Blocked (${blocked.size})"
        val toggleW = font.width(toggleLabel) + 16
        val toggleHovered = mouseX in (x + 10)..(x + 10 + toggleW) && mouseY in toggleY..(toggleY + 16)
        ClientUi.drawFlatButton(g, font, x + 10, toggleY, toggleW, 16, toggleLabel, toggleHovered, showingBlocked)
        hit(x + 10, toggleY, toggleW, 16) {
            showingBlocked = !showingBlocked
            if (showingBlocked) refreshBlocked()
        }

        var rowY = toggleY + 22
        val listBottom = bottom - 10
        val rowW = w - 20

        g.enableScissor(x + 6, rowY - 4, x + w - 6, listBottom)

        if (showingBlocked) {
            g.drawString(font, "BLOCKED", x + 10, rowY - listScroll, ClientUi.DIM, true)
            rowY += 12
            if (blocked.isEmpty()) {
                g.drawString(font, "You have not blocked anyone.", x + 10, rowY - listScroll, ClientUi.MUTED, true)
            } else {
                for (entry in blocked) {
                    val ry = rowY - listScroll
                    if (ry + 26 >= y && ry <= listBottom) drawBlockedRow(g, entry, x + 10, ry, rowW, mouseX, mouseY)
                    rowY += 28
                }
            }
            g.disableScissor()
            return
        }

        // Pending requests first: they need an answer, so they sit above the roster.
        if (RiverSocial.requests.isNotEmpty()) {
            g.drawString(font, "PENDING REQUESTS", x + 10, rowY - listScroll, ClientUi.DIM, true)
            rowY += 12
            for (request in RiverSocial.requests) {
                val ry = rowY - listScroll
                if (ry + 30 >= y && ry <= listBottom) drawRequestRow(g, request, x + 10, ry, rowW, mouseX, mouseY)
                rowY += 34
            }
            rowY += 6
        }

        g.drawString(font, "FRIENDS", x + 10, rowY - listScroll, ClientUi.DIM, true)
        rowY += 12

        if (RiverSocial.friends.isEmpty()) {
            g.drawString(font, "No friends yet. Add someone by username.", x + 10, rowY - listScroll, ClientUi.MUTED, true)
            rowY += 14
        } else {
            for (friend in RiverSocial.friends) {
                val ry = rowY - listScroll
                if (ry + 26 >= y && ry <= listBottom) drawFriendRow(g, friend, x + 10, ry, rowW, mouseX, mouseY)
                rowY += 28
            }
        }

        g.disableScissor()

        val contentH = rowY - (fieldY + 28)
        val viewH = listBottom - (fieldY + 28)
        listScroll = listScroll.coerceIn(0, max(0, contentH - viewH))
    }

    private fun drawRequestRow(g: GuiGraphics, request: RiverSocial.Request, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        ClientUi.drawSectionCard(g, x, y, w, 30)
        g.drawString(font, trim(request.name, w - 100), x + 8, y + 5, ClientUi.TEXT, true)
        g.drawString(font, "wants to be friends", x + 8, y + 17, ClientUi.DIM, true)

        val declineW = 22
        val acceptW = 52
        val acceptX = x + w - acceptW - declineW - 12
        val declineX = x + w - declineW - 6

        val acceptHovered = mouseX in acceptX..(acceptX + acceptW) && mouseY in (y + 6)..(y + 24)
        ClientUi.drawFlatButton(g, font, acceptX, y + 6, acceptW, 18, "Accept", acceptHovered, true)
        hit(acceptX, y + 6, acceptW, 18) {
            RiverSocial.acceptRequest(request.from) { ok, message -> say(message); if (ok) RiverSocial.refresh(force = true) }
        }

        val declineHovered = mouseX in declineX..(declineX + declineW) && mouseY in (y + 6)..(y + 24)
        ClientUi.drawFlatButton(g, font, declineX, y + 6, declineW, 18, "✕", declineHovered, false)
        hit(declineX, y + 6, declineW, 18) {
            RiverSocial.declineRequest(request.from) { _, message -> say(message) }
        }
    }

    private fun refreshBlocked() {
        RiverSocial.blockedList { list ->
            if (mcClient.screen === this) blocked = list
        }
    }

    private fun drawBlockedRow(g: GuiGraphics, entry: RiverSocial.Friend, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 26)
        ClientUi.drawListRow(g, x, y, w, 26, ClientUi.hover("blk:${entry.uuid}", hovered), false)
        g.drawString(font, trim(entry.name, w - 80), x + 10, y + 9, ClientUi.MUTED, true)

        val bw = 62
        val bx = x + w - bw - 6
        val bHovered = mouseX in bx..(bx + bw) && mouseY in (y + 4)..(y + 22)
        ClientUi.drawFlatButton(g, font, bx, y + 4, bw, 18, "Unblock", bHovered, false)
        hit(bx, y + 4, bw, 18) {
            RiverSocial.unblock(entry.uuid) { ok, message ->
                say(message)
                if (ok) { refreshBlocked(); RiverSocial.refresh(force = true) }
            }
        }
    }

    private fun drawFriendRow(g: GuiGraphics, friend: RiverSocial.Friend, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX in x..(x + w) && mouseY in y..(y + 26)
        val isSelected = selected?.uuid == friend.uuid
        ClientUi.drawListRow(g, x, y, w, 26, ClientUi.hover("fr:${friend.uuid}", hovered), isSelected)

        // Status dot: the roster is sorted online-first, so this reads at a glance.
        val dot = if (friend.online) ClientUi.POSITIVE else ClientUi.DIM
        g.fill(x + 8, y + 12, x + 12, y + 16, dot)
        g.drawString(font, trim(friend.name, w - 60), x + 18, y + 5, ClientUi.TEXT, true)
        val sub = if (friend.online) (friend.server.ifEmpty { "Online" }) else "Offline"
        g.drawString(font, trim(sub, w - 60), x + 18, y + 15, ClientUi.DIM, true)

        hit(x, y, w, 26) { openThread(friend) }
    }

    // ------------------------------------------------------------------ conversation

    private fun drawConversation(g: GuiGraphics, x: Int, y: Int, w: Int, bottom: Int, mouseX: Int, mouseY: Int) {
        ClientUi.drawPanel(g, x, y, w, bottom - y)
        val friend = selected

        if (friend == null) {
            g.drawString(font, "Pick a friend to start chatting.", x + 14, y + 16, ClientUi.MUTED, true)
            return
        }

        g.drawString(font, friend.name, x + 14, y + 12, ClientUi.TEXT, true)
        val blockX = x + w - 62
        val blockHovered = mouseX in blockX..(blockX + 52) && mouseY in (y + 8)..(y + 26)
        ClientUi.drawFlatButton(g, font, blockX, y + 8, 52, 18, "Block", blockHovered, false)
        hit(blockX, y + 8, 52, 18) {
            RiverSocial.block(friend.uuid) { ok, message ->
                say(message)
                if (ok) { selected = null; messages = emptyList(); refreshBlocked() }
            }
        }
        g.fill(x + 10, y + 30, x + w - 10, y + 31, ClientUi.alpha(ClientUi.BORDER, 0.6f))

        val inputY = bottom - 30
        val listTop = y + 36
        val listBottom = inputY - 8

        if (loadingThread) {
            g.drawString(font, "Loading messages...", x + 14, listTop + 4, ClientUi.DIM, true)
        } else if (messages.isEmpty()) {
            g.drawString(font, "No messages yet. Say hello.", x + 14, listTop + 4, ClientUi.MUTED, true)
        }

        // Messages render bottom-up so the newest is always the one you can see.
        g.enableScissor(x + 8, listTop, x + w - 8, listBottom)
        var lineY = listBottom + chatScroll
        for (message in messages.asReversed()) {
            val mine = message.from == RiverSocial.selfUuid
            val lines = font.split(Component.literal(message.text), w - 40)
            lineY -= lines.size * 10 + 12
            if (lineY > listBottom) continue
            g.drawString(font, if (mine) "You" else message.fromName, x + 14, lineY, if (mine) ClientUi.ACCENT_B else ClientUi.MUTED, true)
            lines.forEachIndexed { index, line ->
                g.drawString(font, line, x + 14, lineY + 10 + index * 10, ClientUi.TEXT, false)
            }
            if (lineY < listTop) break
        }
        g.disableScissor()

        // Composer.
        val sendW = 48
        val fieldW = w - 28 - sendW
        val fieldHovered = mouseX in (x + 14)..(x + 14 + fieldW) && mouseY in inputY..(inputY + 20)
        ClientUi.drawListRow(g, x + 14, inputY, fieldW, 20, ClientUi.hover("fr:chat", fieldHovered || chatFocused), chatFocused)
        val placeholder = chatInput.isEmpty() && !chatFocused
        val caret = if (chatFocused && (System.currentTimeMillis() / 500) % 2 == 0L) "_" else ""
        g.drawString(
            font,
            if (placeholder) "Message ${friend.name}" else trim(chatInput, fieldW - 12) + caret,
            x + 20, inputY + 6,
            if (placeholder) ClientUi.DIM else ClientUi.TEXT, true
        )
        hit(x + 14, inputY, fieldW, 20) { chatFocused = true; addFocused = false }

        val sendX = x + 14 + fieldW + 6
        val sendHovered = mouseX in sendX..(sendX + sendW) && mouseY in inputY..(inputY + 20)
        ClientUi.drawFlatButton(g, font, sendX, inputY, sendW, 20, "Send", sendHovered, true)
        hit(sendX, inputY, sendW, 20) { submitMessage() }
    }

    // ------------------------------------------------------------------ actions

    private fun submitAdd() {
        val name = addInput.trim()
        if (name.isEmpty()) return
        addInput = ""
        addFocused = false
        RiverSocial.addFriend(name) { ok, message -> say(message); if (ok) RiverSocial.refresh(force = true) }
    }

    private fun submitMessage() {
        val friend = selected ?: return
        val text = chatInput.trim()
        if (text.isEmpty()) return
        chatInput = ""
        RiverSocial.send(friend.uuid, text) { ok, message ->
            if (ok) refreshThread() else say(message)
        }
    }

    // ------------------------------------------------------------------ input

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubled)
        val mx = event.x()
        val my = event.y()
        addFocused = false
        chatFocused = false
        hits.asReversed().forEach { region ->
            if (mx >= region.x1 && mx <= region.x2 && my >= region.y1 && my <= region.y2) {
                region.onClick()
                return true
            }
        }
        return super.mouseClicked(event, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        val listW = min(280, ((width - 40) * 0.38f).toInt())
        if (mouseX < 20 + listW) listScroll = (listScroll - (deltaY * 20).toInt()).coerceAtLeast(0)
        else chatScroll = (chatScroll + (deltaY * 20).toInt()).coerceAtLeast(0)
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (addFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { addFocused = false; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { submitAdd(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { addInput = addInput.dropLast(1); return true }
            }
            return true
        }
        if (chatFocused) {
            when (key) {
                GLFW.GLFW_KEY_ESCAPE -> { chatFocused = false; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { submitMessage(); return true }
                GLFW.GLFW_KEY_BACKSPACE -> { chatInput = chatInput.dropLast(1); return true }
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val text = event.codepointAsString()
        if (addFocused) {
            if (addInput.length < 16) addInput += text
            return true
        }
        if (chatFocused) {
            if (chatInput.length < 256) chatInput += text
            return true
        }
        return super.charTyped(event)
    }

    override fun onClose() {
        mcClient.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    private fun trim(text: String, maxWidth: Int): String {
        if (maxWidth <= 0) return ""
        if (font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
