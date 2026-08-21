package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.wyz.clientcore.ui.RiverScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the GUI scale at 2 while a River screen is open, so the River UI looks
 * identical at GUI scale 3, 4, ... as it does at scale 2. Vanilla screens and
 * the in-world HUD keep the user's real GUI scale — the clamp only applies while
 * {@link Minecraft#screen} is one of ours. {@code MinecraftMixin} triggers a
 * {@code resizeDisplay()} when entering/leaving a River screen so this re-runs.
 */
@Mixin(Window.class)
public abstract class WindowGuiScaleMixin {
    private static final int RIVER_MAX_GUI_SCALE = 2;

    @Inject(method = "calculateScale", at = @At("RETURN"), cancellable = true)
    private void clientcore$clampRiverGuiScale(int guiScale, boolean forceUnicode, CallbackInfoReturnable<Integer> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !(mc.screen instanceof RiverScreen)) return;
        if (cir.getReturnValueI() > RIVER_MAX_GUI_SCALE) {
            cir.setReturnValue(RIVER_MAX_GUI_SCALE);
        }
    }
}
