package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.tooltip.ContainerTooltips;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops vanilla's list of container contents while River is drawing the grid.
 *
 * Vanilla names the first few stacks as text. With the grid above it that is the same
 * information twice, in a tooltip that has just grown three rows - and the text is the
 * half that is harder to read. It comes back untouched the moment the preview is not
 * showing, which includes every version below 1.21.5: the method this cancels does not
 * exist there, so those keep the vanilla text and lose nothing else.
 */
@Mixin(targets = "net.minecraft.world.item.component.ItemContainerContents")
public abstract class ContainerContentsTooltipMixin {
    @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void clientcore$hideWhenPreviewing(CallbackInfo ci) {
        if (ContainerTooltips.suppressesVanillaText()) ci.cancel();
    }
}
