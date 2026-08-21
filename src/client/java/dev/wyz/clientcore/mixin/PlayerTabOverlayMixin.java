package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.NameTagModule;
import dev.wyz.clientcore.nametag.RiverBadgeState;
import dev.wyz.clientcore.tab.TabPingCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
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
        if (!RiverBadgeState.shouldShow(info.getProfile().id())) return;

        cir.setReturnValue(Component.empty()
            .append(RiverBadgeState.badgeComponent())
            .append(Component.literal(" "))
            .append(base.copy()));
    }

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void clientcore$replacePingIcon(GuiGraphics graphics, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
        dev.wyz.clientcore.module.impl.PingModule pingModule = ModuleRegistry.INSTANCE.get("ping");
        if (pingModule == null || !pingModule.showInTab()) return;
        // Replace the vanilla connection bars with a right-aligned ping number.
        Font font = this.minecraft.font;
        int latency = info.getLatency();
        String ping = TabPingCache.pingText(info.getProfile().id(), latency);
        int color = latency < 0 ? 0xFF9CA3AF
            : latency < 80 ? 0xFF72F1B8
            : latency < 160 ? 0xFFFFD46B
            : 0xFFFF8080;
        int textWidth = font.width(ping);
        int textX = x + width - textWidth - 1;
        graphics.drawString(font, ping, textX, y + 1, color, false);
        ci.cancel();
    }
}
