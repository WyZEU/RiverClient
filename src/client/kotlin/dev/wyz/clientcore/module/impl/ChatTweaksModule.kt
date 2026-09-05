package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * Chat quality of life: timestamps on every message, a ping sound when your
 * name is mentioned, and a longer scrollback. Nothing is sent or automated.
 */
class ChatTweaksModule : Module("chat_tweaks", "Chat Tweaks", "Timestamps, mention sound, longer history", ModuleCategory.UTILITY, "chat", 8, 388, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var lastMentionAt = 0L
    private var lastSentText = ""
    private var lastSentAt = 0L

    fun timestamps(): Boolean = active && effectiveChatTweaks().timestamps

    fun longerHistory(): Boolean = active && effectiveChatTweaks().longerHistory

    /** Called from the outgoing-chat mixin so we never ding on our own messages. */
    fun onOutgoingMessage(raw: String) {
        lastSentText = raw.trim()
        lastSentAt = System.currentTimeMillis()
    }

    /** Called from the chat mixin for every incoming message. */
    fun onIncomingMessage(raw: String) {
        if (!active || !effectiveChatTweaks().mentionSound) return
        val client = Minecraft.getInstance()
        val name = client.user?.name ?: return
        if (!raw.contains(name, ignoreCase = true)) return

        val now = System.currentTimeMillis()
        // Suppress our own message echoing back: it carries our name as the sender.
        if (lastSentText.isNotEmpty() && now - lastSentAt < 3000 && raw.contains(lastSentText, ignoreCase = true)) {
            return
        }
        // Also skip the classic "<Name> ..." / "Name: ..." self-sender prefixes.
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("<$name>", ignoreCase = true) || trimmed.startsWith("$name:", ignoreCase = true)) {
            return
        }
        if (now - lastMentionAt < 800) return
        lastMentionAt = now
        client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.4f))
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Chat"))
        list.add(BoolSetting("Timestamps", { mutableChatTweaks().timestamps }, { mutableChatTweaks().timestamps = it }))
        list.add(BoolSetting("Mention sound", { mutableChatTweaks().mentionSound }, { mutableChatTweaks().mentionSound = it }))
        list.add(BoolSetting("Longer history", { mutableChatTweaks().longerHistory }, { mutableChatTweaks().longerHistory = it }))
    }
}
