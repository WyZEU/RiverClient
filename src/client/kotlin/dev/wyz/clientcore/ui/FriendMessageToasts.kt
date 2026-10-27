package dev.wyz.clientcore.ui

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.net.RiverSocial
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Announces friend DMs that arrive while you are playing.
 *
 * The backend hands each notice over exactly once (its inbox is drained by the request
 * that reports presence), so this never has to de-duplicate or track what it has already
 * shown.
 *
 * A chat line alone was not enough: it scrolls away behind whatever else is being said,
 * and it is invisible entirely to anyone playing with chat hidden - a message could
 * arrive and simply never be noticed. So a DM now also rings and draws itself on the HUD
 * for a few seconds. The chat line stays, because it is the only one of the three you can
 * scroll back to.
 *
 * Drawn with River's own primitives rather than a vanilla toast. Toasts are the obvious
 * choice until you have to support eleven Minecraft versions whose toast API keeps
 * changing shape; this draws the same on all of them and looks like the rest of River:
 * the sender's face, a square panel, the theme accent down the edge, sliding in from the
 * side the way the friends screen's notices do.
 */
object FriendMessageToasts {

    private const val SHOW_MS = 5_500L
    private const val SLIDE_MS = 220L
    private const val FADE_MS = 600L
    private const val MAX_ON_SCREEN = 3
    /** Clear of the watermark, which owns the very top right. */
    private const val TOP_MARGIN = 34
    private const val WIDTH = 176

    private data class Popup(val uuid: String, val from: String, val text: String, val at: Long)

    private val popups = ArrayDeque<Popup>()

    fun tick(client: Minecraft) {
        val notices = RiverSocial.pendingNotices
        if (notices.isEmpty()) return
        RiverSocial.clearNotices()

        // Still drained above when disabled, so turning the setting on later does not
        // dump a backlog of old messages at you.
        if (!RiverRuntime.config.friendsMessageToasts) return
        if (client.player == null) return

        val now = System.currentTimeMillis()
        for (notice in notices.asReversed()) {
            client.gui.chat.addMessage(
                Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(notice.fromName).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                    .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(notice.text).withStyle(ChatFormatting.WHITE))
            )
            popups.addLast(Popup(notice.from, notice.fromName, notice.text, now))
            while (popups.size > MAX_ON_SCREEN) popups.removeFirst()
        }

        /*
          One ring however many arrived together. Several messages landing in the same
          poll is one event as far as anyone hearing it is concerned, and playing it once
          per message turns a notification into a noise.
        */
        client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 0.7f))
    }

    fun render(client: Minecraft, graphics: GuiGraphics) {
        if (popups.isEmpty()) return
        val now = System.currentTimeMillis()
        while (popups.isNotEmpty() && now - popups.first().at > SHOW_MS) popups.removeFirst()
        if (popups.isEmpty()) return

        val font = client.font
        val screenWidth = client.window.guiScaledWidth
        var y = TOP_MARGIN

        for ((index, popup) in popups.withIndex()) {
            val age = now - popup.at
            if (age < 0) continue
            // Slides in from the right edge, then fades out over its last moments.
            val slide = 1f - (1f - (age.toFloat() / SLIDE_MS).coerceIn(0f, 1f)).pow(3)
            val remaining = SHOW_MS - age
            val alpha = if (remaining >= FADE_MS) 1f else (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue

            val height = 30
            val x = screenWidth - WIDTH - 8 + ((1f - slide) * (WIDTH + 12)).roundToInt()

            // Glass once it has fully slid in; while it moves the plain panel reads better.
            if (alpha < 0.99f || slide < 0.99f ||
                !Glass.surface(graphics, x, y, WIDTH, height, 0, ClientUi.PANEL_SOFT, tintAlpha = 0.6f)
            ) {
                graphics.fill(x, y, x + WIDTH, y + height, ClientUi.alpha(ClientUi.PANEL_SOFT, 0.96f * alpha))
            }
            ClientUi.drawRoundedBorder(graphics, x, y, WIDTH, height, 0, ClientUi.alpha(ClientUi.BORDER, alpha))
            graphics.fill(x, y, x + 2, y + height, ClientUi.alpha(ClientUi.ACCENT_A, alpha))

            PlayerHeads.draw(graphics, popup.uuid, popup.from, x + 8, y + 7, 16, alpha = alpha)

            val textX = x + 30
            val textW = WIDTH - 38
            graphics.drawString(font, trim(font, popup.from, textW - 30), textX, y + 6, ClientUi.alpha(ClientUi.TEXT, alpha), true)
            // Only the newest says how to answer; three hints stacked would be noise. The
            // shortcut opens this conversation directly (see ClientKeybinds.openFriends).
            if (index == popups.size - 1) {
                val hint = shortcutHint()
                graphics.drawString(font, hint, x + WIDTH - 8 - font.width(hint), y + 6, ClientUi.alpha(ClientUi.DIM, alpha), false)
            }
            graphics.drawString(font, trim(font, popup.text, textW), textX, y + 17, ClientUi.alpha(ClientUi.MUTED, alpha), false)

            y += height + 4
        }
    }

    /** Dev screenshots only (see dev.DevShot): queues a popup that starts sliding in after [delayMs]. */
    internal fun devPush(uuid: String, name: String, text: String, delayMs: Long) {
        popups.addLast(Popup(uuid, name, text, System.currentTimeMillis() + delayMs))
    }

    /** Whoever messaged last, while their popup is still up: the shortcut opens their chat. */
    fun recentSender(): String? {
        val last = popups.lastOrNull() ?: return null
        return if (System.currentTimeMillis() - last.at < SHOW_MS) last.uuid else null
    }

    private fun shortcutHint(): String {
        val key = RiverRuntime.config.friendsKey
        if (key < 0 || key == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) return "Shift+Tab"
        val name = runCatching { org.lwjgl.glfw.GLFW.glfwGetKeyName(key, 0) }.getOrNull()?.uppercase() ?: return "Shift+key"
        return "Shift+$name"
    }

    private fun trim(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && font.width("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }
}
