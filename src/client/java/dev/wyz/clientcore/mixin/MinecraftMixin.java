package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.RiverRuntime;
import dev.wyz.clientcore.ui.RiverScreen;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** Whether the GUI scale cap applied to the last screen, so we only rescale on changes. */
    private boolean clientcore$wasScaleCapped = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private void clientcore$riverTick(CallbackInfo ci) {
        RiverRuntime.INSTANCE.tick((Minecraft) (Object) this);
    }

    // When River's GUI scale cap starts or stops applying, force the scale to be
    // recomputed (see WindowGuiScaleMixin). Without this a screen would keep the scale
    // it opened at until the next window resize.
//? if >=26.1 {
/*    @Inject(method = "setScreenAndShow", at = @At("TAIL"))
*///?} else {
    @Inject(method = "setScreen", at = @At("TAIL"))
//?}
    private void clientcore$riverRescaleOnScreenChange(Screen screen, CallbackInfo ci) {
        boolean nowCapped = screen instanceof RiverScreen river && river.capsGuiScale();
        if (nowCapped != clientcore$wasScaleCapped) {
            clientcore$wasScaleCapped = nowCapped;
//? if >=26.1 {
/*            ((Minecraft) (Object) this).resizeGui();
*///?} else {
            ((Minecraft) (Object) this).resizeDisplay();
//?}
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void clientcore$riverShutdown(CallbackInfo ci) {
        RiverRuntime.INSTANCE.shutdown();
    }

    // Minecraft rebuilds its own window title ("Minecraft* <version>") on screen changes,
    // overriding any one-off setTitle. Overriding it at the source keeps the OS window
    // titled "River Client <version>" for good.
    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void clientcore$riverWindowTitle(CallbackInfoReturnable<String> cir) {
//? if >=1.21.6 {
        cir.setReturnValue("River Client " + SharedConstants.getCurrentVersion().name());
//?} else {
/*        cir.setReturnValue("River Client " + SharedConstants.getCurrentVersion().getName());
*///?}
    }
}
