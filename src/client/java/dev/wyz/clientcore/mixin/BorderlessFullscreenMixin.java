package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
//? if >=26.3 {
/*import dev.wyz.clientcore.compat.GLFW;
*///?} else {
import org.lwjgl.glfw.GLFW;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Full screen on Windows as a borderless window covering the monitor, instead of
 * Minecraft's exclusive full screen.
 *
 * Exclusive full screen hands the display to the game, and window capture cannot see it:
 * sharing the game on Discord showed a frozen picture to everyone watching while the game
 * ran fine for the player. A borderless window looks the same, alt-tabs instantly and can
 * be captured. The window's size and place before going full screen are kept here and put
 * back on the way out, so nothing of vanilla's own bookkeeping is needed; only GLFW and the
 * public monitor lookup are used. Other systems keep vanilla's behaviour.
 *
 * The window is one pixel wider than the monitor. Windows treats a borderless window of exactly
 * the screen's size as true full screen and capture freezes again; Minecraft 26.3's own borderless
 * mode pads by the same pixel for this reason.
 */
@Mixin(Window.class)
public abstract class BorderlessFullscreenMixin {
    @Unique private static final boolean clientcore$windows =
        System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    @Unique private boolean clientcore$borderless;
    @Unique private int clientcore$savedX;
    @Unique private int clientcore$savedY;
    @Unique private int clientcore$savedW;
    @Unique private int clientcore$savedH;

    /** 26.3 has its own borderless mode behind an option; River keeps it on, the same as above. */
    @Inject(method = "useBorderlessFullscreenWindow", at = @At("RETURN"), cancellable = true, require = 0)
    private void clientcore$alwaysBorderless(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        if (clientcore$windows) cir.setReturnValue(true);
    }

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$borderlessFullscreen(CallbackInfo ci) {
        if (!clientcore$windows) return;
        Window window = (Window) (Object) this;
//? if >=1.21.9 {
        long handle = window.handle();
//?} else {
/*        long handle = window.getWindow();
*///?}

//? if >=26.3 {
        /*if (false) {
*///?} else {
        if (window.isFullscreen()) {
//?}
//? if >=26.3 {
            /*// Never reached: 26.3 has its own borderless full screen and no setMode to hook.
            Monitor monitor = null;
*///?} else {
            Monitor monitor = window.findBestMonitor();
//?}
            if (monitor == null) return;
            // 26.2 made Monitor a record.
//? if >=26.2 {
/*            VideoMode mode = monitor.currentMode();
            int monitorX = monitor.x();
            int monitorY = monitor.y();
*///?} else {
            VideoMode mode = monitor.getCurrentMode();
            int monitorX = monitor.getX();
            int monitorY = monitor.getY();
//?}
            if (!clientcore$borderless && GLFW.glfwGetWindowMonitor(handle) == 0L) {
                int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
                GLFW.glfwGetWindowPos(handle, x, y);
                GLFW.glfwGetWindowSize(handle, w, h);
                clientcore$savedX = x[0];
                clientcore$savedY = y[0];
                clientcore$savedW = w[0];
                clientcore$savedH = h[0];
            }
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(handle, 0L, monitorX, monitorY, mode.getWidth() + 1, mode.getHeight(), GLFW.GLFW_DONT_CARE);
            clientcore$borderless = true;
            ci.cancel();
        } else if (clientcore$borderless) {
            clientcore$borderless = false;
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
            if (clientcore$savedW > 0 && clientcore$savedH > 0) {
                GLFW.glfwSetWindowMonitor(handle, 0L, clientcore$savedX, clientcore$savedY, clientcore$savedW, clientcore$savedH, GLFW.GLFW_DONT_CARE);
                ci.cancel();
            }
        }
    }
}
