package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.TextOrderCache;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}

/**
 * Plain strings drawn on screen reuse their bidi display order instead of recomputing it
 * every frame (see TextOrderCache). Only the lookup is swapped; the text is drawn by
 * vanilla's own method, so anything else hooking it still runs.
 *
 * From 1.21.6 the String overload converts with Language.getVisualOrder. Before that the
 * overload draws the String directly and this finds nothing to redirect (require = 0).
 */
//? if >=26.1 {
/*@Mixin(GuiGraphicsExtractor.class)
*///?} else {
@Mixin(GuiGraphics.class)
//?}
public abstract class TextOrderCacheMixin {

    @Redirect(
//? if >=26.1 {
        /*method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
*///?} else {
        method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
//?}
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/locale/Language;getVisualOrder(Lnet/minecraft/network/chat/FormattedText;)Lnet/minecraft/util/FormattedCharSequence;"
        ),
        require = 0
    )
    private FormattedCharSequence clientcore$cachedVisualOrder(Language language, FormattedText text,
                                                              Font font, String raw, int x, int y, int color, boolean shadow) {
        // A HUD module drawing in bold or small capitals (see RiverText.withHudStyle).
        if (raw != null && dev.wyz.clientcore.ui.RiverText.hudActive()) return dev.wyz.clientcore.ui.RiverText.hudOrder(raw);
        return TextOrderCache.visualOrder(language, raw, text);
    }
}
