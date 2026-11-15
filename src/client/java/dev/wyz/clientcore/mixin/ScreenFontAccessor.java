package dev.wyz.clientcore.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a River screen draw with River's interface font (see dev.wyz.clientcore.ui.UiFont). */
@Mixin(Screen.class)
public interface ScreenFontAccessor {
    @Accessor("font")
    Font clientcore$font();

    @Mutable
    @Accessor("font")
    void clientcore$setFont(Font font);
}
