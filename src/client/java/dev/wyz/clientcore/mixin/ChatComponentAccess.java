package dev.wyz.clientcore.mixin;

//? if >=26.1 {
/*import net.minecraft.client.multiplayer.chat.GuiMessage;
*///?} else {
import net.minecraft.client.GuiMessage;
//?}
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Where each chat line is, for the Chat Window module's coloured background. */
@Mixin(ChatComponent.class)
public interface ChatComponentAccess {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> clientcore_lines();

    @Accessor("chatScrollbarPos")
    int clientcore_scroll();

    @Invoker("getScale")
    double clientcore_scale();

    @Invoker("getWidth")
    int clientcore_width();

    @Invoker("getLineHeight")
    int clientcore_lineHeight();
}
