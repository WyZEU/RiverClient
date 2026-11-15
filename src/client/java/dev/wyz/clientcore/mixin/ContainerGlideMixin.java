package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.impl.InventoryGlide;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Tools: items glide between slots. See InventoryGlide. Hooks the method that draws all
 * the slots, because recipe book screens such as the inventory skip this class's render. The pose
 * is already moved to the panel there, so slot positions are used as they are. 1.21 and 1.21.1
 * have no such method and draw from render, where the panel's position has to be added.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerGlideMixin {
    @Shadow protected int leftPos;
    @Shadow protected int topPos;

//? if >=26.1 {
/*    @Inject(method = "extractSlots", at = @At("HEAD"))
    private void clientcore$glideSpot(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        InventoryGlide.beforeDraw((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "extractSlots", at = @At("TAIL"))
    private void clientcore$glideDraw(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
        InventoryGlide.afterDraw(g, 0, 0);
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void clientcore$glideHide(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (InventoryGlide.hides(slot)) ci.cancel();
    }
*///?} elif >=1.21.11 {
    @Inject(method = "renderSlots", at = @At("HEAD"))
    private void clientcore$glideSpot(GuiGraphics g, int mouseX, int mouseY, CallbackInfo ci) {
        InventoryGlide.beforeDraw((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "renderSlots", at = @At("TAIL"))
    private void clientcore$glideDraw(GuiGraphics g, int mouseX, int mouseY, CallbackInfo ci) {
        InventoryGlide.afterDraw(g, 0, 0);
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void clientcore$glideHide(GuiGraphics g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (InventoryGlide.hides(slot)) ci.cancel();
    }
//?} elif >=1.21.2 {
/*    @Inject(method = "renderSlots", at = @At("HEAD"))
    private void clientcore$glideSpot(GuiGraphics g, CallbackInfo ci) {
        InventoryGlide.beforeDraw((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "renderSlots", at = @At("TAIL"))
    private void clientcore$glideDraw(GuiGraphics g, CallbackInfo ci) {
        InventoryGlide.afterDraw(g, 0, 0);
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void clientcore$glideHide(GuiGraphics g, Slot slot, CallbackInfo ci) {
        if (InventoryGlide.hides(slot)) ci.cancel();
    }
*///?} else {
/*    @Inject(method = "render", at = @At("HEAD"))
    private void clientcore$glideSpot(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        InventoryGlide.beforeDraw((AbstractContainerScreen<?>) (Object) this);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void clientcore$glideDraw(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        InventoryGlide.afterDraw(g, this.leftPos, this.topPos);
    }

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void clientcore$glideHide(GuiGraphics g, Slot slot, CallbackInfo ci) {
        if (InventoryGlide.hides(slot)) ci.cancel();
    }
*///?}
}
