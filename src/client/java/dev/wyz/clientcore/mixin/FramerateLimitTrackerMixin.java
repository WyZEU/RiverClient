package dev.wyz.clientcore.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.wyz.clientcore.module.ModuleRegistry;
import dev.wyz.clientcore.module.impl.PerformanceModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * River's framerate caps, layered on vanilla's limit at RETURN so they only ever
 * LOWER the effective limit, never raise it, and never conflict with other mods:
 *
 *  - unfocused: caps FPS while the window is out of focus (vanilla doesn't throttle
 *    an unfocused-but-visible window at all);
 *  - menus: caps FPS while a screen (inventory, chest, pause...) is open in-world.
 *    A menu doesn't benefit from hundreds of FPS while the world keeps rendering
 *    behind it at full cost - one of the cheapest real power/heat wins. Chat is
 *    exempt so typing over gameplay stays perfectly smooth.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void clientcore$riverFpsCaps(CallbackInfoReturnable<Integer> cir) {
        PerformanceModule module = ModuleRegistry.INSTANCE.get("performance");
        if (module == null || !module.getActive()) return;

        Minecraft client = Minecraft.getInstance();
        int cap = Integer.MAX_VALUE;

        if (module.unfocusedCapEnabled() && !client.isWindowActive()) {
            cap = Math.min(cap, module.unfocusedFps());
        }

        if (module.menuCapEnabled()
            && client.isWindowActive()
            && client.level != null
            && client.screen != null
            && !(client.screen instanceof ChatScreen)) {
            cap = Math.min(cap, module.menuFps());
        }

        if (cap == Integer.MAX_VALUE) return;
        int current = cir.getReturnValueI();
        int limited = (current <= 0) ? cap : Math.min(current, cap);
        if (limited != current) {
            cir.setReturnValue(limited);
        }
    }
}
