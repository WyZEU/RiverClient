package dev.wyz.clientcore.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads which slot the cursor is over.
 *
 * The screen already works this out every frame and keeps it in a protected field. Doing
 * the same sum again from the mouse position would mean also reaching for leftPos and
 * topPos, which are protected as well, and would drift from vanilla the moment a screen
 * lays its slots out differently.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot getHoveredSlot();
}
