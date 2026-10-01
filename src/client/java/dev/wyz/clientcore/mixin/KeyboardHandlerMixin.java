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
    @Inject(method = "setClipboard", at = @At("HEAD"), cancellable = true)
    private void clientcore$riverChatButtons(String text, CallbackInfo ci) {
        if (ScreenshotShare.handleClipboard(text)) ci.cancel();
    }
}
