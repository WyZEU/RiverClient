package dev.wyz.clientcore.ui

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.net.RiverSocial
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Announces friend DMs that arrive while you are playing.
 *
 * The backend hands each notice over exactly once (its inbox is drained by the heartbeat
 * that reports it), so this never has to de-duplicate or track what it has already shown.
 *
 * Uses a chat line rather than a vanilla toast on purpose: toasts sit top-right where most
 * HUD layouts already put something, and a chat line stays readable and scrollable if a
 * few arrive at once. Switched off with the "Message alerts" setting.
 */
object FriendMessageToasts {

    fun tick(client: Minecraft) {
        val notices = RiverSocial.pendingNotices
        if (notices.isEmpty()) return
        RiverSocial.clearNotices()

        // Still drained above when disabled, so turning the setting on later does not
        // dump a backlog of old messages at you.
        if (!RiverRuntime.config.friendsMessageToasts) return
        if (client.player == null) return

        for (notice in notices.asReversed()) {
            client.gui.chat.addMessage(
                Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(notice.fromName).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(notice.text).withStyle(ChatFormatting.GRAY))
            )
        }
    }
}
