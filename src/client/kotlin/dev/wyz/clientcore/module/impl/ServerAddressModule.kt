package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import net.minecraft.client.Minecraft

/** The address of the server you're on, the way it was typed into the server list. */
class ServerAddressModule : RowsHudModule("server_address", "Server Address", "The server you're playing on", "item:minecraft:name_tag", 8, 476) {

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        if (client.level == null) return emptyList()
        val ip = client.currentServer?.ip
        return listOf("" to if (ip.isNullOrBlank()) "Singleplayer" else ip)
    }

    override fun previewRows() = listOf("" to "play.example.net")
}
