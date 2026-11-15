package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.module.RowsHudModule
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.settings.hint
import dev.wyz.clientcore.ui.screen.RiverListScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Lines you write yourself, with live values where you put a name in braces:
 * "FPS: {fps}" shows as "FPS: 240".
 */
class CustomTextModule : RowsHudModule("custom_text", "Custom Text", "Your own lines with live values", "edit", 8, 380) {

    private companion object {
        const val MAX_LINES = 12
        const val MAX_LENGTH = 64
        const val VALUES = "{fps} {cps} {ping} {mem} {x} {y} {z} {server} {name} {time}"
        val TOKEN = Regex("\\{(\\w+)\\}")
        val CLOCK: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }

    private fun lines(): MutableList<String> = ClientCore.config.customTextList()

    // The values change at most once a tick, so the lines are filled in that often and not every frame.
    private var filled: List<Pair<String, String>> = emptyList()
    private var filledAt = 0L

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Custom Text"))
        list.add(
            ActionSetting("Lines", "Edit") {
                val client = Minecraft.getInstance()
                client.setScreen(
                    RiverListScreen(
                        client.screen, "Custom Text", "edit", "Add a line, like FPS: {fps}", MAX_LENGTH, MAX_LINES,
                        items = { lines() }, isChecked = null, onToggle = {},
                        onAdd = { lines().add(it); filledAt = 0L }, onRemove = { lines().removeAt(it); filledAt = 0L }
                    )
                )
            }.hint(VALUES)
        )
    }

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        val now = System.nanoTime()
        if (now - filledAt > 50_000_000L) {
            filledAt = now
            filled = lines().map { "" to fill(it) { name -> value(client, name) } }
        }
        return filled
    }

    override fun previewRows(): List<Pair<String, String>> = lines().map { "" to fill(it, ::sample) }

    private fun fill(line: String, value: (String) -> String?): String =
        TOKEN.replace(line) { m -> value(m.groupValues[1].lowercase()) ?: m.value }

    private fun value(client: Minecraft, name: String): String? {
        val player = client.player
        return when (name) {
            "fps" -> client.fps.toString()
            "cps" -> dev.wyz.clientcore.pvp.CpsTracker.left().toString()
            "ping" -> (if (player == null) 0 else client.connection?.getPlayerInfo(player.uuid)?.latency ?: 0).toString()
            "mem" -> Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1024 / 1024 }.toString()
            "x" -> (player?.blockX ?: 0).toString()
            "y" -> (player?.blockY ?: 0).toString()
            "z" -> (player?.blockZ ?: 0).toString()
            "server" -> client.currentServer?.ip?.takeIf { it.isNotBlank() } ?: "Singleplayer"
            "name" -> client.user.name
            "time" -> java.time.LocalTime.now().format(CLOCK)
            else -> null
        }
    }

    private fun sample(name: String): String? = when (name) {
        "fps" -> "241"
        "cps" -> "7"
        "ping" -> "24"
        "mem" -> "1204"
        "x" -> "120"
        "y" -> "64"
        "z" -> "-38"
        "server" -> "play.example.net"
        "name" -> "Steve"
        "time" -> "18:30"
        else -> null
    }
}
