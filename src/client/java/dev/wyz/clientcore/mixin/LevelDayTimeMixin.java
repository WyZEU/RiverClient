package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.TimeChangerModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-only visual time override. Guarded to {@link ClientLevel} so the
 * integrated server's day/night logic in singleplayer is never affected.
 */
@Mixin(Level.class)
public abstract class LevelDayTimeMixin {

    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void clientcore$overrideVisualTime(CallbackInfoReturnable<Long> cir) {
        if (!((Object) this instanceof ClientLevel)) return;
        TimeChangerModule module = ModuleRegistry.INSTANCE.get("time_changer");
        if (module == null) return;
        Long override = module.overrideDayTime();
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
