package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
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

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void clientcore$addRiverBadgeToTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        NameTagModule module = ModuleRegistry.INSTANCE.get("nametag");
        if (module == null || !module.getActive() || !module.showRiverBadge()) return;

        Component base = cir.getReturnValue();
        if (base == null) return;
//? if >=1.21.11 {
        if (!RiverBadgeState.shouldShow(info.getProfile().id())) return;
//?} else {
/*        if (!RiverBadgeState.shouldShow(info.getProfile().getId())) return;
*///?}

        cir.setReturnValue(Component.empty()
            .append(RiverBadgeState.badgeComponent())
            .append(Component.literal(" "))
            .append(base.copy()));
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
//? if >=1.21.11 {
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
