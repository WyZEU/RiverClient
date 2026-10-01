package dev.wyz.clientcore.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes the raw option value, bypassing the slider clamp — used by Fullbright to push
 * gamma past 1.0, the same thing editing options.txt by hand does.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    void setRawValue(Object value);
}
