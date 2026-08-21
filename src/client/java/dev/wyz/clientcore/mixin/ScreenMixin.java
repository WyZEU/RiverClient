package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.RiverRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void clientcore$riverRenderWatermark(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        RiverRuntime.INSTANCE.renderScreen(client, (Screen) (Object) this, guiGraphics, mouseX, mouseY, partialTick);
    }
}
