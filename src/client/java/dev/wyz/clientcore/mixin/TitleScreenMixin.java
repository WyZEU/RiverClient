package dev.wyz.clientcore.mixin;

import dev.wyz.clientcore.ui.menu.RiverMainMenu;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?} else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
/**///?}
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

    private static final boolean clientcore$essentialLoaded =
        net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("essential")
            || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("essential-container");

    @Inject(method = "init", at = @At("TAIL"))
    private void clientcore$rollSplash(CallbackInfo ci) {
        RiverMainMenu.INSTANCE.pickSplash();
    }

//? if >=26.1 {
/*    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
*///?} else {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
//?}
//? if >=26.1 {
/*    private void clientcore$riverMenu(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
*///?} else {
    private void clientcore$riverMenu(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
//?}
        if (!RiverMainMenu.INSTANCE.getEnabled()) return;
        // Neuter vanilla / other-mod widgets so nothing but River's menu draws or clicks.
        // Essential's stay: it reads a hidden one as "another mod removed this button" and drops its own.
        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget widget && !(clientcore$essentialLoaded && widget.getClass().getName().startsWith("gg.essential."))) {
                widget.visible = false;
                widget.active = false;
            }
        }
        Minecraft client = Minecraft.getInstance();
        RiverMainMenu.INSTANCE.renderBackground(client, g);
        RiverMainMenu.INSTANCE.render((TitleScreen) (Object) this, client, g, mouseX, mouseY);
        // Essential draws its main menu from the base screen render, so that has to run after River's.
        if (clientcore$essentialLoaded) {
//? if >=26.1 {
/*            super.extractRenderState(g, mouseX, mouseY, partialTick);
*///?} else {
            super.render(g, mouseX, mouseY, partialTick);
//?}
        }
        ci.cancel();
    }

    @Override
//? if >=1.21.9 {
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (RiverMainMenu.INSTANCE.getEnabled() && RiverMainMenu.INSTANCE.mouseClicked((TitleScreen) (Object) this, event.x(), event.y())) {
//?} else {
/*    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (RiverMainMenu.INSTANCE.getEnabled() && RiverMainMenu.INSTANCE.mouseClicked((TitleScreen) (Object) this, mouseX, mouseY)) {
*///?}
            return true;
        }
//? if >=1.21.9 {
        return super.mouseClicked(event, doubled);
//?} else {
/*        return super.mouseClicked(mouseX, mouseY, button);
*///?}
    }
}
