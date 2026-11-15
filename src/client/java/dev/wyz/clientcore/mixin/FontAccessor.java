package dev.wyz.clientcore.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads where the game's font gets its glyphs, so River's screens can build a second
 * Font from the same source that draws its default text in River's interface typeface
 * (see dev.wyz.clientcore.ui.UiFont).
 */
@Mixin(Font.class)
public interface FontAccessor {
//? if >=1.21.9 {
    @Accessor("provider")
    Font.Provider clientcore$provider();
//?} else {
    /*@Accessor("fonts")
    java.util.function.Function<net.minecraft.resources.ResourceLocation, net.minecraft.client.gui.font.FontSet> clientcore$fonts();

    @Accessor("filterFishyGlyphs")
    boolean clientcore$filterFishyGlyphs();
*///?}
}
