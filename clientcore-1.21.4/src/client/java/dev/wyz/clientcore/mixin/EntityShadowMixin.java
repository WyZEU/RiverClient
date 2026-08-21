package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips the vanilla per-entity blob shadow decal when disabled by forcing the shadow
 * radius to 0 (vanilla only draws a shadow when the radius is > 0). This removes the
 * shadow's geometry build + draw work; it does not touch shader/Iris shadow maps, which
 * River never controls. @Inject at RETURN so it stacks cleanly with other mods.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {
    @Inject(method = "getShadowRadius", at = @At("RETURN"), cancellable = true)
    private void clientcore$maybeHideShadow(EntityRenderState state, CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() <= 0.0F) return;
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module != null && module.getActive() && !module.entityShadows()) {
            cir.setReturnValue(0.0F);
        }
    }
}
