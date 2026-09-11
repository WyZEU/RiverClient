package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
//? if >=1.21.11 {
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}
//?} else {
/**///?}

/** Leaves the current world/server on a keybind, back to the right menu. */
class QuickDisconnectModule : Module("quick_disconnect", "Quick Disconnect", "Leave to the menu on a keybind", ModuleCategory.UTILITY, "power", 8, 460, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Disconnect key"

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active || client.level == null) return true
        val returnScreen = if (client.currentServer != null) JoinMultiplayerScreen(TitleScreen()) else TitleScreen()
//? if >=1.21.11 {
        client.disconnectFromWorld(Component.literal("Disconnected"))
        client.setScreen(returnScreen)
//?} elif >=1.21.6 {
/*        // 1.21.6 through 1.21.8 take the destination screen plus a "keep the
        // server resource pack" flag; the Component-message overload is 1.21.11+.
        client.disconnect(returnScreen, false)
*///?} else {
/*        // 1.21.4's disconnect(Screen) does the disconnect and screen transition in one call;
        // the separate Component-message overload is a later addition.
        client.disconnect(returnScreen)
*///?}
        return true
    }
}
