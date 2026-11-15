package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.net.ScreenshotShare;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches River's own chat buttons. A chat copy-click in every supported version ends in
 * setClipboard, so the [Copy] and [Share] buttons on the screenshot line are copy clicks
 * carrying a one-time token; ScreenshotShare runs the action and the token never reaches
 * the clipboard. Any other text is copied exactly as vanilla would.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    /*
      Command keys fire on the key press itself. They used to be checked once per game
      tick, so a quick tap that went down and up between two ticks - easy while you are
      also moving and clicking - was never seen. The press event arrives for every key no
      matter what else is held. Nothing is cancelled: the key still does whatever else
      it is bound to.
    */
//? if >=1.21.9 {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void clientcore$commandKeys(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo ci) {
        int key = event.key();
//?} else {
    /*@Inject(method = "keyPress", at = @At("HEAD"))
    private void clientcore$commandKeys(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
*///?}
        if (action != 1) return; // GLFW_PRESS; repeats (2) would spam the command
        dev.wyz.clientcore.module.impl.MacrosModule.onKeyPressed(key);
    }

    @Inject(method = "setClipboard", at = @At("HEAD"), cancellable = true)
    private void clientcore$riverChatButtons(String text, CallbackInfo ci) {
        if (ScreenshotShare.handleClipboard(text)) ci.cancel();
    }
}
