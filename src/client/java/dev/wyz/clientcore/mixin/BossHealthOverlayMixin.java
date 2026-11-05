package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.GameHudModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar toggle, ported from Prism's NoRender.
 *
 * Purely declutters the top of the screen: the bar is a readout, not information
 * you act on that is unavailable elsewhere, and hiding it removes nothing the
 * player could otherwise use. The boss fight itself is entirely unaffected.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
//? if >=26.1 {
/*    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
*///?} else {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$hideBossBars(GuiGraphicsExtractor graphics, CallbackInfo ci) {
*///?} else {
    private void clientcore$hideBossBars(GuiGraphics graphics, CallbackInfo ci) {
//?}
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.bossBarsEnabled()) {
            ci.cancel();
            return;
        }
        GameHudModule gameHud = ModuleRegistry.INSTANCE.get("game_hud");
        if (gameHud == null || !gameHud.getActive() || gameHud.bossSize() == 1f) return;
        float scale = gameHud.bossSize();
        float offset = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2f * (1f - scale);
        // Stays centered and against the top of the screen.
//? if >=1.21.6 {
        graphics.pose().pushMatrix();
        graphics.pose().translate(offset, 0f);
        graphics.pose().scale(scale, scale);
//?} else {
/*        graphics.pose().pushPose();
        graphics.pose().translate(offset, 0f, 0f);
        graphics.pose().scale(scale, scale, 1f);
*///?}
        clientcore$scaled = true;
    }

    private boolean clientcore$scaled = false;

//? if >=26.1 {
/*    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void clientcore$bossBarsEnd(GuiGraphicsExtractor graphics, CallbackInfo ci) {
*///?} else {
    @Inject(method = "render", at = @At("RETURN"))
    private void clientcore$bossBarsEnd(GuiGraphics graphics, CallbackInfo ci) {
//?}
        if (!clientcore$scaled) return;
//? if >=1.21.6 {
        graphics.pose().popMatrix();
//?} else {
/*        graphics.pose().popPose();
*///?}
        clientcore$scaled = false;
    }
}
