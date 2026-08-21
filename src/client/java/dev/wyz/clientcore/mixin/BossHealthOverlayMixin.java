package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.gui.GuiGraphics;
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
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void clientcore$hideBossBars(GuiGraphics graphics, CallbackInfo ci) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.bossBarsEnabled()) {
            ci.cancel();
        }
    }
}
