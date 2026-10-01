package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
//? if >=1.21.10 {
import net.minecraft.network.chat.contents.objects.PlayerSprite
import net.minecraft.world.item.component.ResolvableProfile
//?}
import net.minecraft.network.chat.Component
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Chat quality of life: timestamps on every message, a ping sound when your
 * name is mentioned, and a longer scrollback. Nothing is sent or automated.
 */
class ChatTweaksModule : Module("chat_tweaks", "Chat Tweaks", "Timestamps, mention sound, longer history", ModuleCategory.UTILITY, "item:minecraft:writable_book", 8, 388, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var lastMentionAt = 0L
    private var lastSentText = ""
    private var lastSentAt = 0L

    /** Last line that made it to chat, for both repeat-hiding and the copy key. */
    private var lastIncomingText = ""

    override val keybindLabel: String = "Copy last message"

    fun timestamps(): Boolean = active && effectiveChatTweaks().timestamps

    fun longerHistory(): Boolean = active && effectiveChatTweaks().longerHistory

    fun stackRepeats(): Boolean = active && effectiveChatTweaks().stackRepeats
    fun keepChatBetweenServers(): Boolean = active && effectiveChatTweaks().keepBetweenServers

    fun chatHeads(): Boolean = active && effectiveChatTweaks().chatHeads

    /**
     * The sender's face for a chat line, or null.
     *
     * 1.21.10 gave text a way to carry a player head (an object component), so from there
     * the head is part of the line itself - it wraps, scrolls, fades and stacks with the
     * message and needed no rendering code of ours. Before 1.21.10 there is no such thing,
     * and a face is a live skin texture that a font glyph cannot point at, so older
     * versions simply do not get heads.
     */
    fun chatHead(message: Component): Component? {
//? if >=1.21.10 {
        if (!chatHeads()) return null
        val sender = senderOf(message.string) ?: return null
        return Component.`object`(PlayerSprite(ResolvableProfile.createResolved(sender.profile), true))
//?} else {
/*        return null
*///?}
    }

    /**
     * Who a line is from, going by the player list. Big servers send chat as plain system
     * text in their own format ("[MVP+] Name: gg", "Name » gg"), so there is no sender
     * field to read - the name is found in the text instead. The earliest whole-word
     * match in the opening characters wins, and the longest name breaks a tie, so "Ann"
     * does not claim a line from "Anna".
     */
    private fun senderOf(text: String): PlayerInfo? {
        val players = Minecraft.getInstance().connection?.onlinePlayers ?: return null
        val opening = text.take(64)
        var best: PlayerInfo? = null
        var bestAt = Int.MAX_VALUE
        var bestLength = 0
        for (info in players) {
            val name = info.profile.name ?: continue
            if (name.length < 3) continue
            val at = wordIndex(opening, name)
            if (at < 0) continue
            if (at < bestAt || (at == bestAt && name.length > bestLength)) {
                best = info
                bestAt = at
                bestLength = name.length
            }
        }
        return best
    }

    private fun wordIndex(text: String, word: String): Int {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from, ignoreCase = true)
            if (i < 0) return -1
            if (!isNameChar(text.getOrNull(i - 1)) && !isNameChar(text.getOrNull(i + word.length))) return i
            from = i + 1
        }
    }

    private fun isNameChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

    /**
     * A message reduced to what decides "same message again": the timestamp is stripped,
     * because with timestamps on, two identical lines a minute apart are different
     * strings, and stacking the same second would miss the point.
     */
    fun normalize(text: String): String = stripTimestamp(text).trim()

    private fun stripTimestamp(text: String): String =
        text.removePrefix("[").let { rest ->
            val close = rest.indexOf("] ")
            if (close == 5 && rest.getOrNull(2) == ':') rest.substring(close + 2) else text
        }

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active) return true
        if (lastIncomingText.isEmpty()) return true
        client.keyboardHandler.clipboard = lastIncomingText
        client.gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Copied the last message").withStyle(ChatFormatting.GRAY))
        )
        return true
    }

    /** Called from the outgoing-chat mixin so we never ding on our own messages. */
    fun onOutgoingMessage(raw: String) {
        lastSentText = raw.trim()
        lastSentAt = System.currentTimeMillis()
    }

    /** Called from the chat mixin for every incoming message. */
    fun onIncomingMessage(raw: String) {
        normalize(raw).takeIf { it.isNotEmpty() }?.let { lastIncomingText = it }
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
        list.add(BoolSetting("Stack repeated messages", { mutableChatTweaks().stackRepeats }, { mutableChatTweaks().stackRepeats = it }))
        list.add(BoolSetting("Keep chat when changing servers", { mutableChatTweaks().keepBetweenServers }, { mutableChatTweaks().keepBetweenServers = it }))
//? if >=1.21.10 {
        list.add(BoolSetting("Chat heads", { mutableChatTweaks().chatHeads }, { mutableChatTweaks().chatHeads = it }))
//?}
    }
}
