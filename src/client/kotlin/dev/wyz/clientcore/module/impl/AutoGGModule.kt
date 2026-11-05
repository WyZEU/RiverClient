package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.settings.hint
import net.minecraft.client.Minecraft

/**
 * Auto GG: says gg in chat when a game ends on a server. It reads the end-of-game lines the
 * big minigame servers print (Hypixel's "Reward Summary", "1st Killer", "WINNER!" and the
 * like), waits the delay you set, and sends once per game. Never in singleplayer.
 */
class AutoGGModule : Module("autogg", "Auto GG", "Says gg when a game ends", ModuleCategory.UTILITY, "chat", 8, 520, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun message(): String = MESSAGES.getOrElse(scalar("message", 0)) { MESSAGES[0] }
    fun delaySeconds(): Int = scalar("delay", 1).coerceIn(0, 5)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Message"))
        list.add(ChoiceSetting("Say", MESSAGES, { message() }, { setScalar("message", MESSAGES.indexOf(it).coerceAtLeast(0)) }))
        list.add(IntSetting("Delay", 0, 5, { delaySeconds() }, { setScalar("delay", it) }, "s")
            .hint("Only on servers, once per game."))
    }

    /** When the pending gg goes out (System.currentTimeMillis), or 0 when none is waiting. */
    private var sendAt = 0L

    override fun tick(client: Minecraft) {
        if (sendAt == 0L || System.currentTimeMillis() < sendAt) return
        sendAt = 0L
        client.player?.connection?.sendChat(message())
    }

    companion object {
        val MESSAGES = listOf("gg", "GG", "Good game", "gg wp")

        /** Exact pieces of the lines servers print when a game ends. Case matters on purpose. */
        private val TRIGGERS = listOf(
            "Reward Summary", "1st Killer - ", "WINNER!", "VICTORY!", "Winner: ", "won the game!", "GAME OVER!"
        )
        private const val COOLDOWN_MS = 20_000L
        private var lastTrigger = 0L

        /** Called with every chat line as it arrives (ChatComponentMixin). */
        @JvmStatic
        fun onChatMessage(text: String) {
            val module = ModuleRegistry.get<AutoGGModule>("autogg") ?: return
            if (!module.active) return
            val client = Minecraft.getInstance()
            if (client.currentServer == null || client.isLocalServer) return
            if (TRIGGERS.none { text.contains(it) }) return
            // Player chat shows up as "<name> message" or "name: message"; a player typing
            // "winner" must not set it off, so anything that looks like a chat line is skipped.
            if (text.trimStart().startsWith("<")) return
            val now = System.currentTimeMillis()
            if (now - lastTrigger < COOLDOWN_MS) return
            lastTrigger = now
            module.sendAt = now + module.delaySeconds() * 1000L
        }
    }
}
