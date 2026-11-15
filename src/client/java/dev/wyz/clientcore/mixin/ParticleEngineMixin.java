package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.ParticlesModule;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.DragonBreathParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two particle gates:
 *  - distance: particles farther than the configured range from the camera are dropped;
 *  - per-tick budget: caps how many particles the engine takes on in a single tick, which
 *    tames explosion chains and AFK-farm bursts.
 *
 * Hazard particles are exempt from both gates: dragon's breath on the ground is the ONLY
 * visual for an area that damages you, so culling it (the budget cap drops it during any
 * particle burst) would be a real safety hole, not just a cosmetic loss. Optimization must
 * never hide something the player takes damage from.
 *
 * Both hook {@link ParticleEngine#add} rather than createParticle. Callers dereference
 * createParticle's return value with no null check - FireworkParticles' Starter casts it
 * to SparkParticle and calls setTrail on it - so returning null there crashes the game
 * ("Ticking Particle" / NPE). Cancelling add() hands the caller a real particle while the
 * engine never ticks or renders it, which is where the cost actually is. Gating
 * registration rather than construction also keeps this compatible with
 * Sodium/ImmediatelyFast, which optimize particle *rendering*.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Unique
    private int clientcore$spawnedThisTick;

    @Inject(method = "tick", at = @At("HEAD"))
    private void clientcore$resetParticleBudget(CallbackInfo ci) {
        clientcore$spawnedThisTick = 0;
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void clientcore$gateParticles(Particle particle, CallbackInfo ci) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module == null || !module.getActive()) return;

        // Never cull a damage zone: dragon's breath is the visual for an area-effect cloud
        // that hurts you. (Lingering potions use tinted spell particles instead, which share
        // a class with harmless ones and so aren't cleanly exemptable here.)
        if (particle instanceof DragonBreathParticle) return;

        int budget = module.particleBudget();
        if (budget > 0 && clientcore$spawnedThisTick >= budget) {
            ci.cancel();
            return;
        }

        int maxDist = module.particleDistance();
        if (maxDist > 0 && particle != null) {
//? if >=26.2 {
/*            Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
*///?} else {
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
//?}
            if (camera.isInitialized()) {
//? if >=1.21.11 {
                Vec3 cam = camera.position();
//?} else {
/*                Vec3 cam = camera.getPosition();
*///?}
                Vec3 pos = particle.getBoundingBox().getCenter();
                double dx = pos.x - cam.x;
                double dy = pos.y - cam.y;
                double dz = pos.z - cam.z;
                if (dx * dx + dy * dy + dz * dz > (double) maxDist * (double) maxDist) {
                    ci.cancel();
                    return;
                }
            }
        }

        clientcore$spawnedThisTick++;
    }

    /*
      Particle Effects (ParticlesModule). createParticle makes a particle and hands it to add(),
      so the options it was made from are noted on the way in and read in add(): that is where
      a switched-off kind is dropped and a crit gets its colour. The note is cleared on the way
      out, so particles added any other way are never mistaken for the last one made.
    */
    @Unique
    private ParticleOptions clientcore$making;

    @Inject(method = "createParticle", at = @At("HEAD"), require = 0)
    private void clientcore$noteParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        clientcore$making = options;
    }

    @Inject(method = "createParticle", at = @At("RETURN"), require = 0)
    private void clientcore$forgetParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        clientcore$making = null;
    }

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void clientcore$particleEffects(Particle particle, CallbackInfo ci) {
        ParticleOptions options = clientcore$making;
        if (options == null || particle == null) return;
        if (!ParticlesModule.allows(options)) {
            ci.cancel();
            return;
        }
        Integer tint = ParticlesModule.critTint(options);
        if (tint != null) clientcore$tint(particle, tint);
    }

    @Unique
    private static void clientcore$tint(Particle particle, int argb) {
        float r = ((argb >> 16) & 255) / 255f;
        float g = ((argb >> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
//? if >=1.21.9 {
        if (particle instanceof net.minecraft.client.particle.SingleQuadParticle quad) quad.setColor(r, g, b);
//?} else {
/*        particle.setColor(r, g, b);
*///?}
    }

    /** More hit particles: the crit and enchanted emitters are started again, once per extra step. */
    @Unique
    private boolean clientcore$repeatingHit;

    @Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V", at = @At("HEAD"), require = 0)
    private void clientcore$moreHitParticles(Entity entity, ParticleOptions options, CallbackInfo ci) {
        if (clientcore$repeatingHit) return;
        int extra = ParticlesModule.extraHits(options);
        if (extra <= 0) return;
        clientcore$repeatingHit = true;
        try {
            for (int i = 0; i < extra; i++) ((ParticleEngine) (Object) this).createTrackingEmitter(entity, options);
        } finally {
            clientcore$repeatingHit = false;
        }
    }
}
