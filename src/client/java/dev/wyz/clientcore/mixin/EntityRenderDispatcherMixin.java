package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import dev.wyz.clientcore.perf.EntityCuller;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * River's entity render gates on EntityRenderDispatcher.shouldRender:
 *  - missing-renderer guard (HEAD): if an entity has no renderer, report "don't render"
 *    instead of letting vanilla NPE. See {@link #clientcore$guardMissingRenderer}.
 *  - distance + occlusion culls (RETURN): stacked on top of vanilla's frustum test, only
 *    ever hide, never force-show. See {@link #clientcore$riverEntityGates}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Shadow
    public abstract <T extends Entity> EntityRenderer<? super T, ?> getRenderer(T entity);

    /**
     * Vanilla's shouldRender does {@code getRenderer(entity).shouldRender(...)}. Some mods
     * hand back a null renderer for server-spawned entities - e.g. the nameless NPC
     * "players" a practice server puts at spawn - and vanilla then crashes the whole game
     * with an NPE ("Cannot invoke shouldRender because renderer is null"). River's other
     * gate runs at RETURN, which never fires because the method throws first.
     *
     * An entity with no renderer literally cannot be drawn, so the correct answer is "don't
     * render" - return false and skip it, which also stops the pipeline from trying to
     * extract its render state (that path dereferences the same null renderer). This turns a
     * hard crash into one invisible entity.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void clientcore$guardMissingRenderer(E entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (getRenderer(entity) == null) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Distance and occlusion culls, applied on top of vanilla's frustum result:
     *  - distance: skip entities past the configured range. Players and dropped items are
     *    exempt - you want to see other players and your loot from far off.
     *  - occlusion: skip entities {@link EntityCuller} found fully hidden behind blocks. This
     *    applies to everyone, players and items included - if it's behind a wall you can't
     *    see it anyway, so hiding it is free.
     */
    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private <E extends Entity> void clientcore$riverEntityGates(E entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module == null || !module.getActive()) return;

        int maxDist = module.entityDistance();
        boolean distanceExempt = entity instanceof Player || entity instanceof ItemEntity;
        if (maxDist > 0 && !distanceExempt) {
            double dx = entity.getX() - camX;
            double dy = entity.getY() - camY;
            double dz = entity.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > (double) maxDist * (double) maxDist) {
                cir.setReturnValue(false);
                return;
            }
        }

        if (module.entityCullEnabled() && EntityCuller.INSTANCE.isCulled(entity.getId())) {
            cir.setReturnValue(false);
        }
    }
}
