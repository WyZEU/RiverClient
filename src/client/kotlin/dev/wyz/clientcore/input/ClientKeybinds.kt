package dev.wyz.clientcore.input

import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.ScreenshotModule
import dev.wyz.clientcore.module.impl.ZoomModule
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.screen.RiverFriendsScreen
import dev.wyz.clientcore.ui.screen.RiverHudEditorScreen
import dev.wyz.clientcore.ui.screen.RiverMenuScreen
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

object ClientKeybinds {
    private const val menuKey = GLFW.GLFW_KEY_RIGHT_SHIFT
    private const val socialKey = GLFW.GLFW_KEY_TAB
    private const val screenshotKey = GLFW.GLFW_KEY_F9
    private val moduleKeyState = HashMap<String, Boolean>()
    private var menuWasDown = false
    private var menuArmed = false
    private var socialTriggeredWhileMenuHeld = false
    private var socialWasDown = false
    private var screenshotWasDown = false
    private var cinematicWasDown = false

    fun initialize() = Unit

    fun openMenu(client: Minecraft) {
        client.setScreen(RiverMenuScreen(client.screen))
    }

    fun openHudEditor(client: Minecraft) {
        client.setScreen(RiverHudEditorScreen(client.screen))
    }

    fun openFriends(client: Minecraft) {
        client.setScreen(RiverFriendsScreen(client.screen))
    }

    /**
     * Whether Left Shift + Tab is live right now. Split by context because that chord is
     * also sneak + player list: leaving it untouched on servers is a reasonable thing to
     * want, and wanting it in singleplayer where the list is useless is equally reasonable.
     */
    fun friendsKeyEnabled(client: Minecraft): Boolean {
        val config = RiverRuntime.config
        // Treat "no level yet" as singleplayer-ish; the main menu has no player list to clash with.
        val onServer = client.level != null && client.singleplayerServer == null
        return if (onServer) config.friendsKeyOnServers else config.friendsKeyInSingleplayer
    }

    fun tick(client: Minecraft) {
//? if >=1.21.11 {
        val window = client.window.handle()
//?} else {
/*        val window = client.window.getWindow()
*///?}
        // Never react to raw keys while another screen is capturing input (chat, menus...).
        val typing = client.screen != null

        val menuDown = !typing && GLFW.glfwGetKey(window, menuKey) == GLFW.GLFW_PRESS
        if (menuDown && !menuWasDown) {
            menuArmed = true
            socialTriggeredWhileMenuHeld = false
        }
        if (!menuDown && menuWasDown) {
            if (menuArmed && !socialTriggeredWhileMenuHeld) {
                openMenu(client)
            }
            menuArmed = false
            socialTriggeredWhileMenuHeld = false
        }
        menuWasDown = menuDown

        // Left Shift + Tab. Note this is sneak + player list, so on a server it fires
        // during ordinary play - that is what the per-context toggles below exist for.
        val leftShiftDown = !typing && GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
        val friendsKey = RiverRuntime.config.friendsKey.let { if (it >= 0) it else socialKey }
        val socialDown = leftShiftDown && GLFW.glfwGetKey(window, friendsKey) == GLFW.GLFW_PRESS
        if (socialDown && !socialWasDown) {
            // If Right Shift happens to be held too, don't also open the main menu on top
            // of the friends screen when it is released.
            if (menuDown) {
                socialTriggeredWhileMenuHeld = true
                menuArmed = false
            }
            if (friendsKeyEnabled(client)) {
                openFriends(client)
            }
        }
        socialWasDown = socialDown

        val screenshotDown = !typing && GLFW.glfwGetKey(window, screenshotKey) == GLFW.GLFW_PRESS
        if (screenshotDown && !screenshotWasDown) {
            ModuleRegistry.get<ScreenshotModule>("screenshot")?.let { if (it.active) it.capture(client) }
        }
        screenshotWasDown = screenshotDown

        val cinematicKey = RiverRuntime.config.cinematicKey
        val cinematicDown = !typing && cinematicKey >= 0 && GLFW.glfwGetKey(window, cinematicKey) == GLFW.GLFW_PRESS
        if (cinematicDown && !cinematicWasDown) {
            ServerSafety.cinematicMode = !ServerSafety.cinematicMode
            RiverRuntime.saveConfig()
            val state = if (ServerSafety.cinematicMode) "on" else "off"
            client.gui.chat.addMessage(
                Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Cinematic mode $state").withStyle(ChatFormatting.GRAY))
            )
        }
        cinematicWasDown = cinematicDown

        ModuleRegistry.all.forEach { module ->
            val keybind = module.keybind
            if (keybind < 0 || typing) {
                moduleKeyState[module.id] = false
                return@forEach
            }
            val down = GLFW.glfwGetKey(window, keybind) == GLFW.GLFW_PRESS
            val wasDown = moduleKeyState[module.id] == true
            if (down && !wasDown) {
                if (!module.onKeybindPressed(client)) {
                    module.enabled = !module.enabled
                }
            }
            moduleKeyState[module.id] = down
        }

        val zoom = ModuleRegistry.get<ZoomModule>("zoom")
        if (zoom != null && zoom.active) {
            ZoomController.zoomFov = zoom.zoomFov()
            ZoomController.zooming = !typing && GLFW.glfwGetKey(window, zoom.holdKey()) == GLFW.GLFW_PRESS
        } else {
            ZoomController.zooming = false
        }
    }
}
