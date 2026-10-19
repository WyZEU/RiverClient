package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.ChatTweaksModule;
import net.minecraft.ChatFormatting;
//? if >=26.1 {
/*import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
*///?} else {
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
//?}
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    private static final DateTimeFormatter CLIENTCORE$TIME = DateTimeFormatter.ofPattern("HH:mm");

    // Newest first in both: vanilla inserts every entry, and every wrapped line of it,
    // at index 0. One entry's lines sit together at the head of trimmedMessages, with
    // endOfEntry set on the line at index 0.
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;

//? if >=26.1 {
/*    // 26.x made the four-argument addMessage private; a shadow is the way to call it.
    @Shadow
    private void addMessage(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag) {
        throw new AssertionError();
    }
*///?}

    /*
      Repeat stacking: "gg" twice becomes one "gg [2x]" rather than two lines.

      The previous copy is taken out of chat and the message re-added with a count. The
      re-add goes back through addMessage, so [clientcore$restacking] makes that pass
      skip everything here - otherwise it would be stamped with a second timestamp and
      counted as a repeat of itself.
    */
    @Unique private boolean clientcore$restacking = false;
    @Unique private String clientcore$lastRaw = "";
    /** The last message as shown (timestamp included), without any count. */
    @Unique private Component clientcore$lastShown = null;
    /** The entry that message produced, so we only ever remove the one we added. */
    @Unique private GuiMessage clientcore$lastEntry = null;
    @Unique private int clientcore$repeatCount = 1;

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
        if (clientcore$restacking) return message;
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        // [Copy] [Share] on "Saved screenshot as ..."; every other line passes through.
        Component shown = dev.wyz.clientcore.net.ScreenshotShare.decorate(message);
        if (module != null) {
            // Timestamp outermost, then the face, then the line: "[12:04] (face) Name: gg".
            Component head = module.chatHead(message);
            if (head != null) {
                shown = Component.empty().append(head).append(Component.literal(" ")).append(shown);
            }
            if (module.timestamps()) {
                shown = Component.empty()
                    .append(Component.literal("[" + LocalTime.now().format(CLIENTCORE$TIME) + "] ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                    .append(shown);
            }
        }
        clientcore$lastShown = shown;
        return shown;
    }

    @Inject(
//? if >=26.1 {
/*        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
*///?} else {
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
//?}
        at = @At("HEAD"),
        cancellable = true
    )
//? if >=26.1 {
/*    private void clientcore$onAddMessage(Component message, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
*///?} else {
    private void clientcore$onAddMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
//?}
        if (clientcore$restacking) return;
        dev.wyz.clientcore.pvp.TotemPopTracker.onChatMessage(message.getString());
        ChatTweaksModule module = ModuleRegistry.INSTANCE.get("chat_tweaks");
        if (module == null) return;
        String raw = module.normalize(message.getString());

        if (module.stackRepeats() && !raw.isEmpty() && raw.equals(clientcore$lastRaw) && clientcore$removeNewestEntry()) {
            clientcore$repeatCount++;
            // Whichever of the two HEAD handlers ran first, clientcore$lastShown holds this
            // same text as it was displayed, so the stacked line looks like the original.
            Component base = clientcore$lastShown != null ? clientcore$lastShown : message;
            Component stacked = Component.empty()
                .append(base)
                .append(Component.literal(" [" + clientcore$repeatCount + "x]").withStyle(ChatFormatting.GRAY));
            ci.cancel();
            clientcore$restacking = true;
            try {
//? if >=26.1 {
/*                this.addMessage(stacked, signature, source, tag);
*///?} else {
                ((ChatComponent) (Object) this).addMessage(stacked, signature, tag);
//?}
            } finally {
                clientcore$restacking = false;
            }
            // A stacked repeat never dings: the mention, if there was one, already did.
            return;
        }

        clientcore$lastRaw = raw;
        clientcore$repeatCount = 1;
        module.onIncomingMessage(message.getString());
    }

    @Inject(
//? if >=26.1 {
/*        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
*///?} else {
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
//?}
        at = @At("RETURN")
    )
    private void clientcore$recordEntry(CallbackInfo ci) {
        clientcore$lastEntry = allMessages.isEmpty() ? null : allMessages.get(0);
    }

    /**
     * Takes the newest entry and its wrapped lines out of chat - but only if the newest
     * entry is still the one this mixin recorded. Anything else in between (another mod,
     * a message deleted by the server) and it refuses, so the message is simply added the
     * normal way instead of River removing a line it did not put there.
     */
    @Unique
    private boolean clientcore$removeNewestEntry() {
        if (clientcore$lastEntry == null || allMessages.isEmpty() || allMessages.get(0) != clientcore$lastEntry) return false;
        if (trimmedMessages.isEmpty() || !trimmedMessages.get(0).endOfEntry()) return false;
        allMessages.remove(0);
        trimmedMessages.remove(0);
        while (!trimmedMessages.isEmpty() && !trimmedMessages.get(0).endOfEntry()) {
            trimmedMessages.remove(0);
        }
        return true;
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
