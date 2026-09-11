package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.DragonBreathParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
