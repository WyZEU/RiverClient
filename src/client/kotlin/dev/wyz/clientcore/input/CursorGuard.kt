package dev.wyz.clientcore.input

import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}
//? if >=26.3 {
/*import dev.wyz.clientcore.compat.GLFW
*///?} else {
import org.lwjgl.glfw.GLFW
//?}

/**
 * Brings the cursor back if a menu is open and the game thinks the mouse is free, but the window
 * still has it hidden, which leaves a player clicking blind. It never touches a grabbed mouse,
 * so playing and mods that grab it themselves are left alone. SDL on 26.3 keeps this in step itself.
 */
object CursorGuard {
    private var ticks = 0

    fun tick(client: Minecraft) {
        if (++ticks % 10 != 0) return
        if (client.screen == null || client.mouseHandler.isMouseGrabbed) return
//? if >=1.21.9 {
        val window = client.window.handle()
//?} else {
/*        val window = client.window.getWindow()
*///?}
        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL)
        }
    }
}
