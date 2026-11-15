package dev.wyz.clientcore.net

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ServerData

/**
 * Remembers the last multiplayer server you were on so Auto Reconnect can rejoin
 * after a disconnect. Captured every tick while connected.
 */
object ReconnectState {
    @Volatile
    var lastServer: ServerData? = null
        private set

    fun capture(client: Minecraft) {
        val server = client.currentServer
        if (server != null && client.level != null) {
            lastServer = server
        }
    }
}
