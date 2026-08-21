package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft

/** Wipes your chat history instantly on a keybind. */
class ClearChatModule : Module("clear_chat", "Clear Chat", "Wipe your chat on a keybind", ModuleCategory.UTILITY, "eraser", 8, 442, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Clear key"

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active) return true
        client.gui.chat.clearMessages(false)
        return true
    }
}
