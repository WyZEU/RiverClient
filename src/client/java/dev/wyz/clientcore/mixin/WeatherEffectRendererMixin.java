package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
//? if >=1.21.2 {
import net.minecraft.client.renderer.WeatherEffectRenderer;
//?} else {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Rain & snow" toggle. Vanilla draws weather as textured columns across a radius
 * around the camera every frame - during a storm that's a serious fill-rate cost
 * (whole-screen translucent overdraw), and none of the bundled perf mods touch it.
 * Turning it off skips both the column rendering and the splash-particle ticking
 * (which also silences the rain hiss, consistently: no visuals, no audio).
 *
 * Purely visual: the world is still raining as far as gameplay is concerned
 * (fishing, mob spawns, trident riptide all unaffected - those are server-side).
 *
 * WeatherEffectRenderer only exists from 1.21.2. Before that both halves live on
 * LevelRenderer as renderSnowAndRain and tickRain.
 */
//? if >=1.21.2 {
@Mixin(WeatherEffectRenderer.class)
//?} else {
/*@Mixin(LevelRenderer.class)
*///?}
public abstract class WeatherEffectRendererMixin {

//? if >=1.21.2 {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//?} else {
/*    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
*///?}
    private void clientcore$skipWeatherRender(CallbackInfo ci) {
        if (clientcore$weatherDisabled()) ci.cancel();
    }

//? if >=26.2 {
/*    @Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true, require = 0)
*///?} elif >=1.21.2 {
    @Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true)
//?} else {
/*    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true)
*///?}
    private void clientcore$skipRainParticles(CallbackInfo ci) {
        if (clientcore$weatherDisabled()) ci.cancel();
    }

    private static boolean clientcore$weatherDisabled() {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        return module != null && module.getActive() && !module.weatherRenderEnabled();
    }
}
