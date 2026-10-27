package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.screen.RiverServerBrowserScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Browse servers" in the top right of the multiplayer screen, opening River's server browser. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void clientcore$addBrowseServers(CallbackInfo ci) {
        Component label = Component.literal("Browse servers");
        int w = this.font.width(label) + 20;
        Screen self = this;
        addRenderableWidget(Button.builder(label, b -> RiverServerBrowserScreen.open(self))
            .bounds(this.width - w - 6, 6, w, 20)
            .build());
    }
}
