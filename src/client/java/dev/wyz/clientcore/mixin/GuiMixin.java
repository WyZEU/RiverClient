package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.RiverRuntime;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.hud.GameHudDraw;
import dev.wyz.clientcore.module.impl.CrosshairModule;
import dev.wyz.clientcore.module.impl.GameHudModule;
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
import org.spongepowered.asm.mixin.Shadow;
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
        GameHudDraw.reset();
        RiverRuntime.INSTANCE.renderHud(client, guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    // ------------------------------------------------------------------ Game HUD: action bar, title, tab list

    @Shadow private Component overlayMessageString;
    @Shadow private int overlayMessageTime;
    @Shadow private Component title;
    @Shadow private Component subtitle;
    @Shadow private int titleTime;
    @Shadow private int titleFadeInTime;
    @Shadow private int titleStayTime;
    @Shadow private int titleFadeOutTime;

    private boolean clientcore$barPushed = false;
    private boolean clientcore$titlePushed = false;
    private boolean clientcore$tabPushed = false;

    /** Scales what is drawn next around one point of the screen, moved down by dy. */
//? if >=26.1 {
/*    private void clientcore$push(GuiGraphicsExtractor g, float px, float py, float scale, float dy) {
*///?} else {
    private void clientcore$push(GuiGraphics g, float px, float py, float scale, float dy) {
//?}
//? if >=1.21.6 {
        g.pose().pushMatrix();
        g.pose().translate(px, py + dy);
        g.pose().scale(scale, scale);
        g.pose().translate(-px, -py);
//?} else {
/*        g.pose().pushPose();
        g.pose().translate(px, py + dy, 0f);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-px, -py, 0f);
*///?}
    }

//? if >=26.1 {
/*    private void clientcore$pop(GuiGraphicsExtractor g) {
*///?} else {
    private void clientcore$pop(GuiGraphics g) {
//?}
//? if >=1.21.6 {
        g.pose().popMatrix();
//?} else {
/*        g.pose().popPose();
*///?}
    }

    //? if >=26.1 {
/*@Inject(method = "extractOverlayMessage", at = @At("HEAD"))
    private void clientcore$barStart(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderOverlayMessage", at = @At("HEAD"))
    private void clientcore$barStart(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        GameHudModule module = ModuleRegistry.INSTANCE.get("game_hud");
        if (module == null || !module.getActive()) return;
        Minecraft client = Minecraft.getInstance();
        float show = module.barShow(overlayMessageString, overlayMessageTime);
        float cx = client.getWindow().getGuiScaledWidth() / 2f;
        float cy = client.getWindow().getGuiScaledHeight() - 68f;
        clientcore$push(g, cx, cy, module.barSize() * (0.92f + 0.08f * show), (1f - show) * 6f);
        clientcore$barPushed = true;
        GameHudDraw.textAlpha = show;
        int background = module.barBackground();
        if (background > 0 && overlayMessageString != null && overlayMessageTime > 0) {
            // Vanilla fades the message over its last second; the background goes with it.
            float fade = Math.min(1f, (overlayMessageTime - deltaTracker.getGameTimeDeltaPartialTick(false)) / 20f) * show;
            int half = client.font.width(overlayMessageString) / 2 + 4;
            int alpha = (int) (background * 2.55f * Math.max(0f, fade));
            g.fill((int) cx - half, (int) cy - 7, (int) cx + half, (int) cy + 8, alpha << 24);
        }
    }

    //? if >=26.1 {
/*@Inject(method = "extractOverlayMessage", at = @At("RETURN"))
    private void clientcore$barEnd(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderOverlayMessage", at = @At("RETURN"))
    private void clientcore$barEnd(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        if (!clientcore$barPushed) return;
        clientcore$pop(g);
        clientcore$barPushed = false;
        GameHudDraw.textAlpha = 1f;
    }

    //? if >=26.1 {
/*@Inject(method = "extractTitle", at = @At("HEAD"))
    private void clientcore$titleStart(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderTitle", at = @At("HEAD"))
    private void clientcore$titleStart(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        GameHudModule module = ModuleRegistry.INSTANCE.get("game_hud");
        if (module == null || !module.getActive()) return;
        Minecraft client = Minecraft.getInstance();
        float show = module.titleShow(title, titleTime);
        float cx = client.getWindow().getGuiScaledWidth() / 2f;
        float cy = client.getWindow().getGuiScaledHeight() / 2f;
        clientcore$push(g, cx, cy, module.titleSize() * (0.88f + 0.12f * show), 0f);
        clientcore$titlePushed = true;
        GameHudDraw.textAlpha = show;
        int background = module.titleBackground();
        if (background > 0 && title != null && titleTime > 0) {
            // Vanilla draws the title 4x at y -10 and the subtitle 2x at y 5, around the center.
            float left = titleTime - deltaTracker.getGameTimeDeltaPartialTick(false);
            float fade = 1f;
            if (left > titleFadeOutTime + titleStayTime && titleFadeInTime > 0) fade = (titleFadeInTime + titleStayTime + titleFadeOutTime - left) / titleFadeInTime;
            else if (left < titleFadeOutTime && titleFadeOutTime > 0) fade = left / titleFadeOutTime;
            fade = Math.max(0f, Math.min(1f, fade)) * show;
            int width = client.font.width(title) * 4;
            if (subtitle != null) width = Math.max(width, client.font.width(subtitle) * 2);
            int half = width / 2 + 8;
            int alpha = (int) (background * 2.55f * fade);
            g.fill((int) cx - half, (int) cy - 44, (int) cx + half, (int) cy + (subtitle != null ? 32 : 0), alpha << 24);
        }
    }

    //? if >=26.1 {
/*@Inject(method = "extractTitle", at = @At("RETURN"))
    private void clientcore$titleEnd(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderTitle", at = @At("RETURN"))
    private void clientcore$titleEnd(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        if (!clientcore$titlePushed) return;
        clientcore$pop(g);
        clientcore$titlePushed = false;
        GameHudDraw.textAlpha = 1f;
    }

    //? if >=26.1 {
/*@Inject(method = "extractTabList", at = @At("HEAD"))
    private void clientcore$tabStart(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderTabList", at = @At("HEAD"))
    private void clientcore$tabStart(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        GameHudModule module = ModuleRegistry.INSTANCE.get("game_hud");
        if (module == null || !module.getActive()) return;
        Minecraft client = Minecraft.getInstance();
        float show = module.tabShow(client.options.keyPlayerList.isDown());
        clientcore$push(g, client.getWindow().getGuiScaledWidth() / 2f, 0f, module.tabSize() * (0.96f + 0.04f * show), (show - 1f) * 10f);
        clientcore$tabPushed = true;
        GameHudDraw.fillAlpha = module.tabBackground() / 100f;
    }

    //? if >=26.1 {
/*@Inject(method = "extractTabList", at = @At("RETURN"))
    private void clientcore$tabEnd(GuiGraphicsExtractor g, DeltaTracker deltaTracker, CallbackInfo ci) {
*///?} else {
@Inject(method = "renderTabList", at = @At("RETURN"))
    private void clientcore$tabEnd(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
//?}
        if (!clientcore$tabPushed) return;
        clientcore$pop(g);
        clientcore$tabPushed = false;
        GameHudDraw.fillAlpha = 1f;
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
        CrosshairModule module = ModuleRegistry.INSTANCE.get("crosshair");
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
        GameHudDraw.fillAlpha = module.backgroundPct() / 100f;
        int scale = module.scalePct();
        if (scale != 100) {
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
        GameHudDraw.fillAlpha = 1f;
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
