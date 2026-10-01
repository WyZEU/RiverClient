package dev.wyz.clientcore.compat

/*
  Two waves of the same rework, and River spans both.

  26.1 took the public ChatComponent.addMessage away: it is private now and split by
  where the message came from. 26.2 then moved screen ownership off Minecraft onto Gui,
  and pushed the chat component a level deeper again onto Gui.hud - so 26.1 still reads
  Minecraft.screen and Gui.chat exactly as every earlier version did.

  Each is re-exposed under the name the rest of the mod already uses, for the reason
  given in GuiGraphicsCompat.
*/

//? if >=26.2 {
/*import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.Screen

val Minecraft.screen: Screen? get() = gui.screen()

fun Minecraft.setScreen(screen: Screen?) { gui.setScreen(screen) }

val Gui.chat: ChatComponent get() = hud.getChat()
*///?}

// Everything River posts to chat is client-side, so that is the one it maps onto.
//? if >=26.1 {
/*fun net.minecraft.client.gui.components.ChatComponent.addMessage(
    message: net.minecraft.network.chat.Component
) = addClientSystemMessage(message)
*///?}
