package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.tooltip.ContainerTooltips;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Gives shulker boxes and ender chests the tooltip image vanilla only gives bundles.
 *
 * Vanilla decides what a tooltip draws through getTooltipImage, so supplying the data here
 * means the preview goes through the same path as a bundle's - correctly placed, correctly
 * ordered against the name and lore, and kept on screen by code that already exists. The
 * alternative was drawing a floating panel and re-solving all of that badly.
 */
@Mixin(Item.class)
public abstract class ItemTooltipImageMixin {
    @Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
    private void clientcore$containerPreview(ItemStack stack, CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
        TooltipComponent preview = ContainerTooltips.previewFor(stack);
        if (preview != null) cir.setReturnValue(Optional.of(preview));
    }
}
