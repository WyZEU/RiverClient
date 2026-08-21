package dev.wyz.clientcore.net

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

/**
 * Drives the Auto Reconnect countdown. The DisconnectedScreen mixin arms this on
 * open; [tick] (called every client tick from RiverRuntime) updates the button
 * label and fires the reconnect when the timer runs out. Ticking from the runtime
 * avoids injecting into the screen's inherited render method.
 */
object ReconnectRuntime {
    private var deadline = 0L
    private var server: ServerData? = null
    private var parent: Screen? = null
    private var button: Button? = null

    fun arm(server: ServerData, delayMs: Long, parent: Screen?, button: Button) {
        this.server = server
        this.parent = parent
        this.button = button
        this.deadline = System.currentTimeMillis() + delayMs
    }

    fun cancel() {
        deadline = 0L
        button = null
    }

    /** Reconnect immediately (the "Reconnect now" button). */
    fun reconnectNow(client: Minecraft) {
        if (server != null && parent != null) reconnect(client)
    }

    fun tick(client: Minecraft) {
        if (deadline <= 0L || server == null) return
        if (client.screen !is DisconnectedScreen) {
            // Left the screen some other way; drop the countdown.
            cancel()
            return
        }
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) {
            reconnect(client)
            return
        }
        button?.message = Component.literal("Reconnecting in ${remaining / 1000L + 1L}s")
    }

    private fun reconnect(client: Minecraft) {
        val target = server ?: return
        val back = parent ?: return
        deadline = 0L
        button = null
        ConnectScreen.startConnecting(back, client, ServerAddress.parseString(target.ip), target, false, null)
    }
}
