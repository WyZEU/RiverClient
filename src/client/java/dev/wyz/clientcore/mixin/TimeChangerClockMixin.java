package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.TimeChangerModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Time Changer at the source the client reads its time from. Up to 1.21.11 that is the client
 * level's own data, which the sky reads without going through Level.getDayTime. 26.1 and 26.2 keep
 * time in clocks that the client's clock manager answers for; 26.3 moved the answer onto each
 * clock instance. All of these only exist on the client, so the singleplayer server keeps its real
 * time.
 */
//? if >=26.3 {
/*@Mixin(targets = "net.minecraft.client.ClientClockManager$ClientClockInstance")
*///?} elif >=26.1 {
/*@Mixin(net.minecraft.client.ClientClockManager.class)
*///?} else {
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$ClientLevelData")
//?}
public abstract class TimeChangerClockMixin {
//? if >=26.3 {
/*    @Inject(method = "totalTicks", at = @At("HEAD"), cancellable = true)
    private void clientcore$timeChanger(CallbackInfoReturnable<Long> cir) {
*///?} elif >=26.1 {
/*    @Inject(method = "getTotalTicks", at = @At("HEAD"), cancellable = true)
    private void clientcore$timeChanger(net.minecraft.core.Holder<?> clock, CallbackInfoReturnable<Long> cir) {
*///?} else {
    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void clientcore$timeChanger(CallbackInfoReturnable<Long> cir) {
//?}
        TimeChangerModule module = ModuleRegistry.INSTANCE.get("time_changer");
        if (module == null) return;
        Long override = module.overrideDayTime();
        if (override != null) cir.setReturnValue(override);
    }
}
