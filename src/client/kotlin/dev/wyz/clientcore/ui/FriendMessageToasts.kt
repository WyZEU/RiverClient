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
 * changing shape; this draws the same on all of them and looks like the rest of River.
 */
object FriendMessageToasts {

    private const val SHOW_MS = 5_000L
    private const val FADE_MS = 600L
    private const val MAX_ON_SCREEN = 3
    /** Clear of the watermark, which owns the very top right. */
    private const val TOP_MARGIN = 34
    private const val WIDTH = 168

    private data class Popup(val from: String, val text: String, val at: Long)

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
            popups.addLast(Popup(notice.fromName, notice.text, now))
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
        val x = screenWidth - WIDTH - 8
        var y = TOP_MARGIN

        for (popup in popups) {
            val age = now - popup.at
            // Fades out over its last moments rather than blinking off.
            val remaining = SHOW_MS - age
            val alpha = if (remaining >= FADE_MS) 1f else (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            if (a <= 0) continue

            val body = trim(font, popup.text, WIDTH - 12)
            val height = font.lineHeight * 2 + 13

            ClientUi.fillRounded(graphics, x, y, WIDTH, height, 6, (((a * 0.86f).toInt()) shl 24) or 0x000D1624)
            ClientUi.drawRoundedBorder(graphics, x - 1, y - 1, WIDTH + 2, height + 2, 7, (((a * 0.7f).toInt()) shl 24) or 0x0088A2FF)

            graphics.drawString(font, popup.from, x + 6, y + 5, (a shl 24) or 0x00FFFFFF, true)
            graphics.drawString(font, body, x + 6, y + 6 + font.lineHeight, (a shl 24) or 0x00B8C0CC, true)

            y += height + 4
        }
    }

    private fun trim(font: net.minecraft.client.gui.Font, text: String, maxWidth: Int): String {
        if (font.width(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && font.width("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }
}
