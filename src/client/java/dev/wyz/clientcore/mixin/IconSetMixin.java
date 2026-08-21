package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.IconSet;
import dev.wyz.clientcore.branding.IconBrander;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Brands the window icon: wraps each vanilla icon PNG supplier so the River logo is
 * composited into the corner before Window.setIcon hands it to GLFW. Non-mac only
 * (Windows/Linux use getStandardIcons); mac keeps the vanilla .icns.
 */
@Mixin(IconSet.class)
public abstract class IconSetMixin {
    @Inject(method = "getStandardIcons", at = @At("RETURN"), cancellable = true)
    private void clientcore$brandWindowIcon(PackResources pack, CallbackInfoReturnable<List<IoSupplier<InputStream>>> cir) {
        List<IoSupplier<InputStream>> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) return;
        List<IoSupplier<InputStream>> branded = new ArrayList<>(original.size());
        for (IoSupplier<InputStream> supplier : original) {
            branded.add(() -> IconBrander.brand(supplier.get()));
        }
        cir.setReturnValue(branded);
    }
}
