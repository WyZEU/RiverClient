package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.wyz.clientcore.ui.RiverScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps River's menus the same size relative to the screen at any resolution.
 *
 * River's screens are laid out for a virtual screen of about 960x540 - what GUI scale 2
 * gives at 1080p. The cap used to be a flat 2, which was right at 1080p and half-size
 * at 4K: scale 2 on a 3840x2160 display is a 1920x1080 virtual screen, so every menu
 * shrank to a quarter of the area it was designed for. The cap is now the scale that
 * keeps that virtual size on the actual display - 2 at 1080p (unchanged), 4 at 4K,
 * 5 at 5K - and never below 2, so small screens keep the look they always had. It only
 * ever lowers the scale, so a player who picked a smaller one keeps it.
 *
 * Screens that must match the in-game HUD exactly - the HUD editor - opt out through
 * {@link RiverScreen#capsGuiScale()}. {@code MinecraftMixin} triggers a resize when the
 * cap starts or stops applying so this re-runs.
 */
@Mixin(Window.class)
public abstract class WindowGuiScaleMixin {

    @Unique
    private static final int CLIENTCORE$LAYOUT_WIDTH = 960;
    @Unique
    private static final int CLIENTCORE$LAYOUT_HEIGHT = 540;

    @Inject(method = "calculateScale", at = @At("RETURN"), cancellable = true)
    private void clientcore$clampRiverGuiScale(int guiScale, boolean forceUnicode, CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
//? if >=26.2 {
/*        Object screen = mc.gui.screen();
*///?} else {
        Object screen = mc.screen;
//?}
        if (!(screen instanceof RiverScreen river) || !river.capsGuiScale()) return;

        Window self = (Window) (Object) this;
        int fit = Math.min(self.getWidth() / CLIENTCORE$LAYOUT_WIDTH, self.getHeight() / CLIENTCORE$LAYOUT_HEIGHT);
        int cap = Math.max(2, fit);
        if (cir.getReturnValueI() > cap) {
            cir.setReturnValue(cap);
        }
    }
}
