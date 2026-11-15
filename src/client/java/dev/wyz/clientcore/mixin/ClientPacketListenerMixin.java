package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.pvp.TotemPopTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void clientcore$trackTotemPops(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (packet.getEventId() != 35) return;
        Entity entity = packet.getEntity(minecraft.level);
        if (entity instanceof Player player) {
            TotemPopTracker.record(player);
        }
    }

    /*
      The server sends the time once a second of its own ticking (every 20 ticks), so the
      gap between two of these is how fast it is really running. TAIL, because the first
      call arrives on the network thread and is handed to the game thread; only that second
      run reaches the end. require = 0: if a version
      renames this, the TPS module simply falls back to showing nothing on servers.
    */
    @Inject(method = "handleSetTime", at = @At("TAIL"), require = 0)
    private void clientcore$timeTick(CallbackInfo ci) {
        dev.wyz.clientcore.module.impl.TpsModule.Companion.onTimeUpdate();
    }
}
