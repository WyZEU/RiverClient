package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.RiverRuntime;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CrosshairDotModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import dev.wyz.clientcore.module.impl.PotionHudModule;
import dev.wyz.clientcore.module.impl.ScoreboardModule;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
//? if >=26.2 {
/*import net.minecraft.client.gui.Hud;
*///?} else {
import net.minecraft.client.gui.Gui;
//?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.2 {
/*@Mixin(Hud.class)
*///?} else {
@Mixin(Gui.class)
//?}
public abstract class GuiMixin {
    //? if >=26.1 {
/*@Inject(method = "extractRenderState", at = @At("TAIL"))
*///?} else {
@Inject(method = "render", at = @At("TAIL"))
//?}
//? if >=26.1 {
/*    private void clientcore$renderRiverHud(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
    private void clientcore$renderRiverHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        Minecraft client = Minecraft.getInstance();
        RiverRuntime.INSTANCE.renderHud(client, guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    //? if >=26.1 {
/*@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
*///?} else {
@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$hideVanillaCrosshairWhenCustomEnabled(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
    private void clientcore$hideVanillaCrosshairWhenCustomEnabled(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        CrosshairDotModule module = ModuleRegistry.INSTANCE.get("crosshair_dot");
        if (module == null || !module.getActive()) return;
        if (module.editorCrosshair().getHideVanillaCrosshair()) {
            ci.cancel();
        }
    }

    /**
     * Pumpkin overlay toggle, ported from Prism's NoRender.
     *
     * 1.21.11 has no dedicated pumpkin render method or texture constant - the
     * overlay goes through the shared renderTextureOverlay - so this matches on the
     * texture path. Powder snow uses its own path and is left alone.
     */
    //? if >=26.1 {
/*@Inject(method = "extractTextureOverlay", at = @At("HEAD"), cancellable = true)
*///?} else {
@Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$hidePumpkinOverlay(GuiGraphicsExtractor guiGraphics, Identifier texture, float alpha, CallbackInfo ci) {
*///?} elif >=1.21.11 {
    private void clientcore$hidePumpkinOverlay(GuiGraphics guiGraphics, Identifier texture, float alpha, CallbackInfo ci) {
//?} else {
/*    private void clientcore$hidePumpkinOverlay(GuiGraphics guiGraphics, ResourceLocation texture, float alpha, CallbackInfo ci) {
*///?}
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module == null || !module.getActive() || module.pumpkinOverlayEnabled()) return;
        if (texture != null && texture.getPath().contains("pumpkin")) {
            ci.cancel();
        }
    }

    //? if >=26.1 {
/*@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
*///?} else {
@Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$hideVanillaEffectIcons(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
    private void clientcore$hideVanillaEffectIcons(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        PotionHudModule module = ModuleRegistry.INSTANCE.get("potion_hud");
        if (module != null && module.hideVanillaEffects()) {
            ci.cancel();
        }
    }

    private boolean clientcore$scoreboardScaled = false;

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
//? if >=26.1 {
/*    private void clientcore$scoreboardHideOrScale(GuiGraphicsExtractor guiGraphics, Objective objective, CallbackInfo ci) {
*///?} else {
    private void clientcore$scoreboardHideOrScale(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
//?}
        ScoreboardModule module = ModuleRegistry.INSTANCE.get("scoreboard");
        if (module == null) return;
        if (module.hideAll()) {
            ci.cancel();
            return;
        }
        int scale = module.scalePct();
        if (scale < 100) {
            float s = scale / 100f;
            Minecraft client = Minecraft.getInstance();
            int sw = client.getWindow().getGuiScaledWidth();
            int sh = client.getWindow().getGuiScaledHeight();
//? if >=1.21.6 {
            guiGraphics.pose().pushMatrix();
//?} else {
/*            guiGraphics.pose().pushPose();
*///?}
            // Keep the sidebar pinned to its right-center anchor while shrinking.
//? if >=1.21.6 {
            guiGraphics.pose().translate(sw * (1f - s), (sh / 2f) * (1f - s));
            guiGraphics.pose().scale(s, s);
//?} else {
/*            guiGraphics.pose().translate(sw * (1f - s), (sh / 2f) * (1f - s), 0f);
            guiGraphics.pose().scale(s, s, 1f);
*///?}
            clientcore$scoreboardScaled = true;
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("TAIL"))
//? if >=26.1 {
/*    private void clientcore$scoreboardScaleEnd(GuiGraphicsExtractor guiGraphics, Objective objective, CallbackInfo ci) {
*///?} else {
    private void clientcore$scoreboardScaleEnd(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
//?}
        if (clientcore$scoreboardScaled) {
//? if >=1.21.6 {
            guiGraphics.pose().popMatrix();
//?} else {
/*            guiGraphics.pose().popPose();
*///?}
            clientcore$scoreboardScaled = false;
        }
    }

    // method_55439 is a compiler-generated helper (it builds the Gui$1DisplayEntry local
    // class), so Mojang's mappings leave the name untouched - verified byte-for-byte
    // identical in both 1.21.11 and 1.21.4, same signature, same formatValue call. It is
    // therefore a real, stable target on both. require=0 is kept so a future Minecraft
    // release that renames it degrades to "numbers stay visible" instead of a hard crash
    // on a cosmetic feature.
    @Redirect(
//? if >=26.1 {
/*        method = "lambda$displayScoreboardSidebar$1",
*///?} else {
        method = "method_55439",
//?}
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/PlayerScoreEntry;formatValue(Lnet/minecraft/network/chat/numbers/NumberFormat;)Lnet/minecraft/network/chat/MutableComponent;"),
        require = 0
    )
    private MutableComponent clientcore$hideScoreboardNumbers(PlayerScoreEntry entry, NumberFormat format) {
        ScoreboardModule module = ModuleRegistry.INSTANCE.get("scoreboard");
        if (module != null && module.hideNumbers()) {
            return Component.empty();
        }
        return entry.formatValue(format);
    }
}
