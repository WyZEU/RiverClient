package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.RiverRuntime;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.CrosshairDotModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import dev.wyz.clientcore.module.impl.PotionHudModule;
import dev.wyz.clientcore.module.impl.ScoreboardModule;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
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

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void clientcore$renderRiverHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        RiverRuntime.INSTANCE.renderHud(client, guiGraphics, deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void clientcore$hideVanillaCrosshairWhenCustomEnabled(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
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
    @Inject(method = "renderTextureOverlay", at = @At("HEAD"), cancellable = true)
    private void clientcore$hidePumpkinOverlay(GuiGraphics guiGraphics, Identifier texture, float alpha, CallbackInfo ci) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module == null || !module.getActive() || module.pumpkinOverlayEnabled()) return;
        if (texture != null && texture.getPath().contains("pumpkin")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void clientcore$hideVanillaEffectIcons(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        PotionHudModule module = ModuleRegistry.INSTANCE.get("potion_hud");
        if (module != null && module.hideVanillaEffects()) {
            ci.cancel();
        }
    }

    private boolean clientcore$scoreboardScaled = false;

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void clientcore$scoreboardHideOrScale(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
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
            guiGraphics.pose().pushMatrix();
            // Keep the sidebar pinned to its right-center anchor while shrinking.
            guiGraphics.pose().translate(sw * (1f - s), (sh / 2f) * (1f - s));
            guiGraphics.pose().scale(s, s);
            clientcore$scoreboardScaled = true;
        }
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("TAIL"))
    private void clientcore$scoreboardScaleEnd(GuiGraphics guiGraphics, Objective objective, CallbackInfo ci) {
        if (clientcore$scoreboardScaled) {
            guiGraphics.pose().popMatrix();
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
        method = "method_55439",
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
