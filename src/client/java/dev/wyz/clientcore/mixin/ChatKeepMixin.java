package dev.wyz.clientcore.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.ChatTweaksModule;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chat Tweaks' "keep chat between servers", after Chat Patches: vanilla wipes the chat and
 * your sent-message history every time you leave a server, which is what makes a quick
 * rejoin lose the conversation. Only the disconnect path is skipped; F3+D still clears.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
*///?} else {
@Mixin(net.minecraft.client.gui.Gui.class)
//?}
public abstract class ChatKeepMixin {
    @WrapOperation(
        method = "onDisconnected",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V")
    )
    private void clientcore$keepChatOnDisconnect(ChatComponent chat, boolean clearSentHistory, Operation<Void> original) {
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module != null && module.keepChatBetweenServers()) return;
        original.call(chat, clearSentHistory);
    }
}
