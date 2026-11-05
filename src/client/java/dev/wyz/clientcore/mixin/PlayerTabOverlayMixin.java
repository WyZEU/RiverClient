package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import dev.wyz.clientcore.module.impl.TiersModule;
import dev.wyz.clientcore.module.impl.TotemCounterModule;
import dev.wyz.clientcore.nametag.RiverBadgeState;
import dev.wyz.clientcore.tab.TabPingCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void clientcore$decorateTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        Component base = cir.getReturnValue();
        if (base == null) return;
//? if >=1.21.10 {
        UUID uuid = info.getProfile().id();
//?} else {
/*        UUID uuid = info.getProfile().getId();
*///?}

        MutableComponent prefix = Component.empty();
        boolean any = false;

        TiersModule tiers = ModuleRegistry.INSTANCE.get("tiers");
        if (tiers != null && tiers.showInTab()) {
            Component tag = tiers.tagFor(uuid);
            if (tag != null) {
                prefix.append(tag).append(Component.literal(" "));
                any = true;
            }
        }

        NameTagModule badge = ModuleRegistry.INSTANCE.get("nametag");
        if (badge != null && badge.getActive() && badge.showRiverBadge() && RiverBadgeState.shouldShow(uuid)) {
            prefix.append(RiverBadgeState.badgeComponent()).append(Component.literal(" "));
            any = true;
        }

        Component suffix = null;
        TotemCounterModule totems = ModuleRegistry.INSTANCE.get("totem_counter");
        if (totems != null && totems.showInTab()) suffix = totems.popSuffix(uuid);

        if (!any && suffix == null) return;
        MutableComponent result = any ? prefix.append(base.copy()) : base.copy();
        if (suffix != null) result.append(suffix);
        cir.setReturnValue(result);
    }

//? if >=26.1 {
/*    @Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
*///?} else {
    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$replacePingIcon(GuiGraphicsExtractor graphics, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
*///?} else {
    private void clientcore$replacePingIcon(GuiGraphics graphics, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
//?}
        dev.wyz.clientcore.module.impl.StatsModule pingModule = ModuleRegistry.INSTANCE.get("stats");
        if (pingModule == null || !pingModule.showInTab()) return;
        // Replace the vanilla connection bars with a right-aligned ping number.
        Font font = this.minecraft.font;
        int latency = info.getLatency();
//? if >=1.21.10 {
        String ping = TabPingCache.pingText(info.getProfile().id(), latency);
//?} else {
/*        String ping = TabPingCache.pingText(info.getProfile().getId(), latency);
*///?}
        int color = latency < 0 ? 0xFF9CA3AF
            : latency < 80 ? 0xFF72F1B8
            : latency < 160 ? 0xFFFFD46B
            : 0xFFFF8080;
        int textWidth = font.width(ping);
        int textX = x + width - textWidth - 1;
//? if >=26.1 {
/*        graphics.text(font, ping, textX, y + 1, color, false);
*///?} else {
        graphics.drawString(font, ping, textX, y + 1, color, false);
//?}
        ci.cancel();
    }
}
