package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.pvp.CooldownTracker;
import net.minecraft.world.item.ItemCooldowns;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} elif >=1.21.2 {
/*import net.minecraft.resources.ResourceLocation;
*///?} else {
/*import net.minecraft.world.item.Item;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hears when a cooldown starts and ends, for the Cooldowns HUD. The vanilla record that
 * holds the length is private, and the percent vanilla exposes cannot say how many
 * seconds are left, so the length is taken here as it is set. Read-only: both hooks are
 * empty in vanilla and nothing is cancelled. CooldownTracker ignores every instance but
 * the local player's, so the integrated server's copies in singleplayer are skipped.
 */
@Mixin(ItemCooldowns.class)
public abstract class ItemCooldownsMixin {
    @Inject(method = "onCooldownStarted", at = @At("HEAD"))
//? if >=1.21.11 {
    private void clientcore$cooldownStarted(Identifier group, int ticks, CallbackInfo ci) {
//?} elif >=1.21.2 {
    /*private void clientcore$cooldownStarted(ResourceLocation group, int ticks, CallbackInfo ci) {
*///?} else {
    /*private void clientcore$cooldownStarted(Item group, int ticks, CallbackInfo ci) {
*///?}
        CooldownTracker.started((ItemCooldowns) (Object) this, group, ticks);
    }

    @Inject(method = "onCooldownEnded", at = @At("HEAD"))
//? if >=1.21.11 {
    private void clientcore$cooldownEnded(Identifier group, CallbackInfo ci) {
//?} elif >=1.21.2 {
    /*private void clientcore$cooldownEnded(ResourceLocation group, CallbackInfo ci) {
*///?} else {
    /*private void clientcore$cooldownEnded(Item group, CallbackInfo ci) {
*///?}
        CooldownTracker.ended((ItemCooldowns) (Object) this, group);
    }
}
