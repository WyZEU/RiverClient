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
    /** Tracks whether the last screen was a River screen so we only rescale on transitions. */
    private boolean clientcore$wasRiverScreen = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private void clientcore$riverTick(CallbackInfo ci) {
        RiverRuntime.INSTANCE.tick((Minecraft) (Object) this);
    }

    // When we enter or leave a River screen, force the GUI scale to be recomputed
    // (WindowGuiScaleMixin clamps it to 2 while a River screen is open). Without this
    // the screen would keep the scale it opened at until the next window resize.
//? if >=26.1 {
/*    @Inject(method = "setScreenAndShow", at = @At("TAIL"))
*///?} else {
    @Inject(method = "setScreen", at = @At("TAIL"))
//?}
    private void clientcore$riverRescaleOnScreenChange(Screen screen, CallbackInfo ci) {
        boolean nowRiver = screen instanceof RiverScreen;
        if (nowRiver != clientcore$wasRiverScreen) {
            clientcore$wasRiverScreen = nowRiver;
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
