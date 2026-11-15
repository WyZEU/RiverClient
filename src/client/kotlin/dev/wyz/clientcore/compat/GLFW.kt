package dev.wyz.clientcore.compat

//? if >=26.3 {
/*import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import org.lwjgl.PointerBuffer
import org.lwjgl.sdl.SDLVideo

// 26.3 moved from GLFW to SDL. River keeps calling these by their GLFW names; on this version the
// key numbers are SDL scancodes, because those are what the game's key events and options hold.
object GLFW {
    const val GLFW_PRESS = 1
    const val GLFW_TRUE = 1
    const val GLFW_FALSE = 0
    const val GLFW_DONT_CARE = -1
    const val GLFW_MOD_CONTROL = InputConstants.MOD_CONTROL
    const val GLFW_MOUSE_BUTTON_LEFT = InputConstants.MOUSE_BUTTON_LEFT
    const val GLFW_MOUSE_BUTTON_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT

    const val GLFW_KEY_A = 4
    const val GLFW_KEY_F = 9
    const val GLFW_KEY_V = 25
    const val GLFW_KEY_ENTER = 40
    const val GLFW_KEY_ESCAPE = 41
    const val GLFW_KEY_BACKSPACE = 42
    const val GLFW_KEY_TAB = 43
    const val GLFW_KEY_SPACE = 44
    const val GLFW_KEY_CAPS_LOCK = 57
    const val GLFW_KEY_F1 = 58
    const val GLFW_KEY_PRINT_SCREEN = 70
    const val GLFW_KEY_SCROLL_LOCK = 71
    const val GLFW_KEY_PAUSE = 72
    const val GLFW_KEY_INSERT = 73
    const val GLFW_KEY_HOME = 74
    const val GLFW_KEY_PAGE_UP = 75
    const val GLFW_KEY_DELETE = 76
    const val GLFW_KEY_END = 77
    const val GLFW_KEY_PAGE_DOWN = 78
    const val GLFW_KEY_RIGHT = 79
    const val GLFW_KEY_LEFT = 80
    const val GLFW_KEY_DOWN = 81
    const val GLFW_KEY_UP = 82
    const val GLFW_KEY_NUM_LOCK = 83
    const val GLFW_KEY_KP_ENTER = 88
    const val GLFW_KEY_MENU = 118
    const val GLFW_KEY_LEFT_CONTROL = 224
    const val GLFW_KEY_LEFT_SHIFT = 225
    const val GLFW_KEY_LEFT_ALT = 226
    const val GLFW_KEY_RIGHT_CONTROL = 228
    const val GLFW_KEY_RIGHT_SHIFT = 229
    const val GLFW_KEY_RIGHT_ALT = 230

    // Window attributes only River's older window code asks for; nothing reads them on SDL.
    const val GLFW_CURSOR = 0
    const val GLFW_CURSOR_NORMAL = 0
    const val GLFW_DECORATED = 0
    const val GLFW_VISIBLE = 0
    const val GLFW_FOCUSED = 0
    const val GLFW_FOCUS_ON_SHOW = 0
    const val GLFW_MOUSE_PASSTHROUGH = 0

    class VidMode(private val w: Int, private val h: Int) {
        fun width() = w
        fun height() = h
    }

    @JvmStatic fun glfwGetKey(window: Long, key: Int): Int = if (InputConstants.isKeyDown(key)) 1 else 0

    @JvmStatic fun glfwGetMouseButton(window: Long, button: Int): Int {
        val mouse = Minecraft.getInstance().mouseHandler
        val down = when (button) {
            InputConstants.MOUSE_BUTTON_LEFT -> mouse.isLeftPressed
            InputConstants.MOUSE_BUTTON_RIGHT -> mouse.isRightPressed
            InputConstants.MOUSE_BUTTON_MIDDLE -> mouse.isMiddlePressed
            else -> false
        }
        return if (down) 1 else 0
    }

    @JvmStatic fun glfwGetKeyName(key: Int, scancode: Int): String? =
        InputConstants.Type.KEYBOARD.getOrCreate(key).displayName.string.ifBlank { null }

    @JvmStatic fun glfwSetWindowPos(window: Long, x: Int, y: Int) { SDLVideo.SDL_SetWindowPosition(window, x, y) }
    @JvmStatic fun glfwSetWindowSize(window: Long, w: Int, h: Int) { SDLVideo.SDL_SetWindowSize(window, w, h) }
    @JvmStatic fun glfwShowWindow(window: Long) { SDLVideo.SDL_ShowWindow(window) }
    @JvmStatic fun glfwHideWindow(window: Long) { SDLVideo.SDL_HideWindow(window) }

    // Unused on SDL: the window is created hidden through a flag (DevShotWindowMixin), the game
    // has its own borderless full screen, and the second-screen dev option does not move it.
    @JvmStatic fun glfwGetInputMode(window: Long, mode: Int): Int = GLFW_CURSOR_NORMAL
    @JvmStatic fun glfwSetInputMode(window: Long, mode: Int, value: Int) {}
    @JvmStatic fun glfwWindowHint(hint: Int, value: Int) {}
    @JvmStatic fun glfwSetWindowAttrib(window: Long, attribute: Int, value: Int) {}
    @JvmStatic fun glfwSetWindowMonitor(window: Long, monitor: Long, x: Int, y: Int, w: Int, h: Int, refresh: Int) {}
    @JvmStatic fun glfwGetWindowMonitor(window: Long): Long = 0L
    @JvmStatic fun glfwGetPrimaryMonitor(): Long = 0L
    @JvmStatic fun glfwGetMonitors(): PointerBuffer? = null
    @JvmStatic fun glfwGetVideoMode(monitor: Long): VidMode? = null
    @JvmStatic fun glfwGetMonitorPos(monitor: Long, x: IntArray, y: IntArray) {}
    @JvmStatic fun glfwGetWindowPos(window: Long, x: IntArray, y: IntArray) {}
    @JvmStatic fun glfwGetWindowSize(window: Long, w: IntArray, h: IntArray) {}
}
*///?}
