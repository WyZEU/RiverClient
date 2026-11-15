package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.hud.GameHudDraw;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Applies GameHudDraw's alpha to the colors vanilla's HUD parts draw with. */
//? if >=26.1 {
/*@Mixin(GuiGraphicsExtractor.class)
*///?} else {
@Mixin(GuiGraphics.class)
//?}
public abstract class GuiGraphicsFadeMixin {
    @ModifyVariable(method = "fill(IIIII)V", at = @At("HEAD"), ordinal = 4, argsOnly = true)
    private int clientcore$fadeFill(int color) {
        return GameHudDraw.fill(color);
    }

//? if >=26.1 {
/*    @ModifyVariable(method = "textWithBackdrop", at = @At("HEAD"), ordinal = 3, argsOnly = true)
*///?} else {
    @ModifyVariable(method = "drawStringWithBackdrop", at = @At("HEAD"), ordinal = 3, argsOnly = true)
//?}
    private int clientcore$fadeBackdropText(int color) {
        return GameHudDraw.text(color);
    }

//? if >=26.1 {
/*    @ModifyVariable(method = {
        "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
        "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
        "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
    }, at = @At("HEAD"), argsOnly = true)
*///?} elif >=1.21.6 {
    @ModifyVariable(method = {
        "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
        "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
        "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
    }, at = @At("HEAD"), argsOnly = true)
//?} else {
/*    @ModifyVariable(method = {
        "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I",
        "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I",
        "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"
    }, at = @At("HEAD"), argsOnly = true)
*///?}
    private boolean clientcore$lightThemeTextShadow(boolean shadow) {
        return dev.wyz.clientcore.ui.ClientUi.textShadow(shadow);
    }
}
