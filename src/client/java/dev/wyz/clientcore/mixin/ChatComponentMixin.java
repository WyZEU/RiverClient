package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.ChatTweaksModule;
import net.minecraft.ChatFormatting;
//? if >=26.1 {
/*import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
*///?} else {
import net.minecraft.client.GuiMessageTag;
//?}
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    private static final DateTimeFormatter CLIENTCORE$TIME = DateTimeFormatter.ofPattern("HH:mm");

    @ModifyVariable(
//? if >=26.1 {
/*        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
*///?} else {
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
//?}
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0,
        require = 0
    )
    private Component clientcore$prependTimestamp(Component message) {
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module == null || !module.timestamps()) return message;
        return Component.empty()
            .append(Component.literal("[" + LocalTime.now().format(CLIENTCORE$TIME) + "] ")
                .withStyle(ChatFormatting.DARK_GRAY))
            .append(message);
    }

    @Inject(
//? if >=26.1 {
/*        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
*///?} else {
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
//?}
        at = @At("HEAD")
    )
//? if >=26.1 {
/*    private void clientcore$mentionSound(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
*///?} else {
    private void clientcore$mentionSound(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
//?}
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module != null) {
            module.onIncomingMessage(message.getString());
        }
    }

    /** Vanilla keeps 100 messages; Chat Tweaks bumps every 100-cap in here to 500. */
    @ModifyConstant(method = "*", constant = @Constant(intValue = 100), require = 0)
    private int clientcore$longerHistory(int original) {
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module != null && module.longerHistory()) {
            return 500;
        }
        return original;
    }
}
