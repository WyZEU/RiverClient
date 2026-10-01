package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.config.MacroData
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.screen.RiverMacrosScreen
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Macros: a key sends a chat message or runs a command.
 *
 * Every other client has this (Lunar calls it Auto Text, Feather calls it macros) and
 * River had nothing, so `/gg`, `/party accept` or a shop warp meant opening chat and
 * typing it out mid-fight.
 *
 * Nothing here is automated: a macro only fires from a key the player pressed, only
 * while no screen has input, and only in a world. Multi-line macros are sent one line
 * per quarter second rather than in a burst, because a server that sees four messages
 * in one tick kicks for spam - which would look like River breaking rather than a
 * server rule.
 */
class MacrosModule : Module("macros", "Macros", "Send chat or commands from a key", ModuleCategory.UTILITY, "item:minecraft:command_block", 8, 460) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Manage key"

    /** Lines waiting to go out, paced by [tick]. */
    private val pending = ArrayDeque<String>()
    private var cooldown = 0

    companion object {
        const val MAX_MACROS = 20
        const val MAX_LENGTH = 120
        /** A macro may hold a few lines; more than this is spam, not a macro. */
        const val MAX_LINES = 5
        private const val TICKS_BETWEEN_LINES = 5
    }

    fun macros(): MutableList<MacroData> = ClientCore.config.macrosList()

    private fun announce(): Boolean = flag("announce", false)

    override fun onKeybindPressed(client: Minecraft): Boolean {
        client.setScreen(RiverMacrosScreen(client.screen))
        return true
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        val client = Minecraft.getInstance()
        list.add(SectionSetting("Macros"))
        list.add(BoolSetting("Echo in chat when one fires", { announce() }, { setFlag("announce", it) }))
        list.add(ActionSetting("Manage macros", "Open") {
            client.setScreen(RiverMacrosScreen(client.screen))
        })
    }

    /** Called from ClientKeybinds when a bound key goes down. */
    fun fire(client: Minecraft, macro: MacroData) {
        if (!active || !macro.enabled) return
        if (client.player == null) return
        val lines = splitLines(macro.text)
        if (lines.isEmpty()) return
        // A key held down must not stack up: whatever a previous press left unsent is
        // dropped in favour of this one.
        pending.clear()
        lines.forEach { pending.addLast(it) }
        cooldown = 0
        if (announce()) {
            client.gui.chat.addMessage(
                Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Macro: ${lines.first()}").withStyle(ChatFormatting.GRAY))
            )
        }
    }

    override fun tick(client: Minecraft) {
        if (pending.isEmpty()) return
        if (client.player == null) {
            pending.clear()
            return
        }
        if (cooldown > 0) {
            cooldown--
            return
        }
        val line = pending.removeFirst()
        send(client, line)
        cooldown = TICKS_BETWEEN_LINES
    }

    private fun send(client: Minecraft, line: String) {
        val connection = client.player?.connection ?: return
        if (line.startsWith("/")) {
            val command = line.removePrefix("/").trim()
            if (command.isNotEmpty()) connection.sendCommand(command)
        } else {
            connection.sendChat(line)
        }
    }

    /** "/gg | gg" -> ["/gg", "gg"]. Blank segments are dropped so a stray bar is harmless. */
    fun splitLines(text: String): List<String> = text.split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_LINES)
}
