package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.wyz.clientcore.dev.DevShot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A dev screenshot run (see {@link DevShot}) never appears on anyone's screen.
 *
 * The run used to hide its window once it had started, which was too late: the game had
 * already opened a normal window on the main monitor - full screen, when the dev
 * client's options asked for it - and taken focus, for the seconds it spent loading. On a
 * machine where someone was playing, that knocked their game out of full screen every
 * time. Now the window is created hidden and unfocused, and the switch to full screen
 * never happens; Minecraft itself never shows or focuses its window, so it stays hidden
 * for the whole run. A normal game never reaches either branch.
 *
 * Up to 1.21.11 the window is created in the constructor. From 26.1 it is created by the
 * static createGlfwWindow, right after the graphics backend sets its own hints, so that is
 * hooked as well; each hook simply finds nothing to attach to on the other generation.
 *
 * A dev client started with -Driver.dev.secondScreen=true is meant to be looked at, just not
 * on the main monitor: it is created hidden the same way, then moved to the first monitor
 * that is not the primary one and shown there without taking focus. It skips the startup
 * switch to full screen (that would land on the primary monitor); F11 still works after.
 */
@Mixin(Window.class)
public abstract class DevShotWindowMixin {

    @Inject(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false),
        require = 0
    )
    private void clientcore$createHiddenForDevShot(CallbackInfo ci) {
        clientcore$hideNewWindow();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void clientcore$showOnSecondScreen(CallbackInfo ci) {
        Window window = (Window) (Object) this;
//? if >=1.21.9 {
        clientcore$moveToSecondScreen(window.handle());
//?} else {
/*        clientcore$moveToSecondScreen(window.getWindow());
*///?}
    }

    @Inject(
        method = "createGlfwWindow",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false),
        require = 0
    )
    private static void clientcore$createHiddenForDevShotBackend(CallbackInfoReturnable<Long> cir) {
        clientcore$hideNewWindow();
    }

    @Inject(method = "createGlfwWindow", at = @At("RETURN"), require = 0)
    private static void clientcore$showOnSecondScreenBackend(CallbackInfoReturnable<Long> cir) {
        clientcore$moveToSecondScreen(cir.getReturnValue());
    }

    @Unique
    private static final boolean clientcore$secondScreen = Boolean.getBoolean("river.dev.secondScreen");
    @Unique
    private static boolean clientcore$startupFullscreenSkipped;
    @Unique
    private static boolean clientcore$shown;

    @Unique
    private static void clientcore$moveToSecondScreen(long handle) {
        if (!clientcore$secondScreen || DevShot.INSTANCE.getActive() || handle == 0L || clientcore$shown) return;
        clientcore$shown = true;
        long primary = GLFW.glfwGetPrimaryMonitor();
        org.lwjgl.PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); i++) {
                long monitor = monitors.get(i);
                if (monitor == primary) continue;
                org.lwjgl.glfw.GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                if (mode == null) continue;
                int[] mx = new int[1], my = new int[1], ww = new int[1], wh = new int[1];
                GLFW.glfwGetMonitorPos(monitor, mx, my);
                GLFW.glfwGetWindowSize(handle, ww, wh);
                GLFW.glfwSetWindowPos(handle, mx[0] + Math.max(0, (mode.width() - ww[0]) / 2), my[0] + Math.max(40, (mode.height() - wh[0]) / 2));
                break;
            }
        }
        GLFW.glfwShowWindow(handle);
    }

    @Unique
    private static void clientcore$hideNewWindow() {
        if (!DevShot.INSTANCE.getActive() && !clientcore$secondScreen) return;
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
    }

    @Inject(method = "toggleFullScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$stayWindowedForDevShot(CallbackInfo ci) {
        if (DevShot.INSTANCE.getActive()) ci.cancel();
        else if (clientcore$secondScreen && !clientcore$startupFullscreenSkipped) {
            clientcore$startupFullscreenSkipped = true;
            ci.cancel();
        }
    }
}
