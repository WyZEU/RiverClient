package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.ChatTweaksModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records outgoing chat so the Chat Tweaks mention sound never fires on your own
 * message when the server echoes it back (your name is the sender there).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerChatMixin {

    @Inject(method = "sendChat", at = @At("HEAD"))
    private void clientcore$recordOutgoingChat(String content, CallbackInfo ci) {
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module != null) {
            module.onOutgoingMessage(content);
        }
    }
}
