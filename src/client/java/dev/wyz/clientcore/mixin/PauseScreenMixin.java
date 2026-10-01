package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.input.ClientKeybinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void clientcore$addPauseButton(CallbackInfo ci) {
        Component disconnectKey = Component.translatable("menu.disconnect");
        int anchorX = this.width / 2 - 102;
        int anchorY = this.height / 4 + 120;
        int anchorW = 204;
        int anchorH = 20;

        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button && button.getMessage().equals(disconnectKey)) {
                anchorX = button.getX();
                anchorY = button.getY();
                anchorW = button.getWidth();
                anchorH = button.getHeight();
                break;
            }
        }

        Component label = Component.literal("River Client");
        int textW = this.font.width(label);
        int width = Math.max(anchorW, textW + 24);
        int x = anchorX + (anchorW - width) / 2;
        int y = anchorY + anchorH + 4;

        addRenderableWidget(Button.builder(label, b -> {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> ClientKeybinds.INSTANCE.openMenu(client));
            })
            .bounds(x, y, width, anchorH)
            .build());
    }
}
