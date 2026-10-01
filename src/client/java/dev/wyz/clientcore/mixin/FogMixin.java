package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.FogModule;
//? if >=1.21.6 {
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
//?} elif >=1.21.2 {
/*import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?} else {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The Fog module. Each fog generation hands its final distances over in one place, and
 * only those numbers change here - colour, shape and every other fog stays vanilla's.
 * FogModule decides what changes; blindness and darkness are always left alone.
 */
@Mixin(FogRenderer.class)
public abstract class FogMixin {
//? if >=1.21.6 {
    // Args: buffer, offset, colour, environmental start/end, render-distance start/end, sky end, cloud end.
//? if >=26.1 {
    /*@ModifyArgs(method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V",
*///?} else {
    @ModifyArgs(method = "setupFog",
//?}
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void clientcore$fogDistances(Args args) {
        FogModule module = ModuleRegistry.INSTANCE.get("fog");
        if (module == null || !module.getActive()) return;
        float renderDistance = args.get(6);
        float[] environment = module.adjust(args.get(3), args.get(4), renderDistance, true);
        if (environment != null) {
            float oldEnd = args.get(4);
            args.set(3, environment[0]);
            args.set(4, environment[1]);
            // In water and lava vanilla fogs the sky and clouds at the same short distance,
            // which left the whole sky solid red in lava. Stretch them by the same amount.
            if (module.inFluid() && oldEnd > 0f) {
                float scale = environment[1] / oldEnd;
                args.set(7, (float) args.get(7) * scale);
                args.set(8, (float) args.get(8) * scale);
            }
        }
        float[] edge = module.adjust(args.get(5), args.get(6), renderDistance, false);
        if (edge != null) {
            args.set(5, edge[0]);
            args.set(6, edge[1]);
        }
    }
//?} elif >=1.21.2 {
    /*@Inject(method = "setupFog", at = @At("RETURN"), cancellable = true)
    private static void clientcore$fogDistances(Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance, boolean thickFog, float partialTick, CallbackInfoReturnable<FogParameters> cir) {
        FogModule module = ModuleRegistry.INSTANCE.get("fog");
        FogParameters fog = cir.getReturnValue();
        if (module == null || !module.getActive() || fog == null || fog == FogParameters.NO_FOG) return;
        float[] adjusted = module.adjust(fog.start(), fog.end(), renderDistance, true);
        if (adjusted == null) return;
        cir.setReturnValue(new FogParameters(adjusted[0], adjusted[1], fog.shape(), fog.red(), fog.green(), fog.blue(), fog.alpha()));
    }
*///?} else {
    /*@Inject(method = "setupFog", at = @At("TAIL"))
    private static void clientcore$fogDistances(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
        FogModule module = ModuleRegistry.INSTANCE.get("fog");
        if (module == null || !module.getActive()) return;
        float[] adjusted = module.adjust(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), renderDistance, true);
        if (adjusted == null) return;
        RenderSystem.setShaderFogStart(adjusted[0]);
        RenderSystem.setShaderFogEnd(adjusted[1]);
    }
*///?}
}
