package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.AutoReconnectModule;
import dev.wyz.clientcore.net.ReconnectRuntime;
import dev.wyz.clientcore.net.ReconnectState;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Reconnect: on the disconnect screen, arm the countdown (driven by
 * ReconnectRuntime) and add Reconnect / Cancel buttons.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {

    @Shadow @Final private Screen parent;

    protected DisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void clientcore$maybeAutoReconnect(CallbackInfo ci) {
        AutoReconnectModule module = ModuleRegistry.INSTANCE.get("auto_reconnect");
        ServerData server = ReconnectState.INSTANCE.getLastServer();
        if (module == null || !module.getActive() || server == null) return;

        int x = this.width / 2 - 100;
        int y = this.height - 40;
        Button reconnect = Button.builder(Component.literal("Reconnecting..."),
                b -> ReconnectRuntime.INSTANCE.reconnectNow(net.minecraft.client.Minecraft.getInstance()))
            .bounds(x, y - 24, 200, 20).build();
        addRenderableWidget(reconnect);
        addRenderableWidget(Button.builder(Component.literal("Cancel reconnect"), b -> {
                ReconnectRuntime.INSTANCE.cancel();
                b.active = false;
                reconnect.setMessage(Component.literal("Auto reconnect cancelled"));
                reconnect.active = false;
            })
            .bounds(x, y, 200, 20).build());

        ReconnectRuntime.INSTANCE.arm(server, module.delaySeconds() * 1000L, this.parent, reconnect);
    }
}
