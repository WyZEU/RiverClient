package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.menu.RiverMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * River's clean main menu. Takes over the whole TitleScreen render: cancels vanilla
 * rendering entirely (panorama, Mojang logo, splash, version/copyright strings, and
 * any other-mod menu overlays) and draws River's own menu instead. Vanilla widgets
 * are neutered so only River's immediate-mode buttons handle input.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void clientcore$rollSplash(CallbackInfo ci) {
        RiverMainMenu.INSTANCE.pickSplash();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void clientcore$riverMenu(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        // Neuter vanilla / other-mod widgets so nothing but River's menu draws or clicks.
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget) {
                widget.visible = false;
                widget.active = false;
            }
        }
        Minecraft client = Minecraft.getInstance();
        RiverMainMenu.INSTANCE.renderBackground(client, g);
        RiverMainMenu.INSTANCE.render((TitleScreen) (Object) this, client, g, mouseX, mouseY);
        ci.cancel();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (RiverMainMenu.INSTANCE.mouseClicked((TitleScreen) (Object) this, event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }
}
