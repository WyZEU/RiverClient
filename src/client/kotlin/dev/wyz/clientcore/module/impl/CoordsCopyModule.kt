package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/** Copies your current XYZ to the clipboard on a keybind, ready to paste in chat. */
class CoordsCopyModule : Module("coords_copy", "Coords Copy", "Copy your position to the clipboard", ModuleCategory.UTILITY, "clipboard", 8, 424, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Copy key"

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active) return true
        val player = client.player ?: return true
        val pos = player.blockPosition()
        val text = "${pos.x} ${pos.y} ${pos.z}"
        client.keyboardHandler.clipboard = text
        client.gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Copied $text").withStyle(ChatFormatting.GRAY))
        )
        return true
    }
}
