package dev.wyz.clientcore.mixin;

import net.minecraft.client.renderer.GameRenderer;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Saturation turns the world's colour up or down with a post effect, loaded through the
 * same private method vanilla uses for the creeper and spider views.
 */
@Mixin(GameRenderer.class)
public interface GameRendererPostInvoker {
//? if >=1.21.11 {
    @Invoker("setPostEffect")
    void clientcore$setPostEffect(Identifier id);
//?} elif >=1.21.2 {
    /*@Invoker("setPostEffect")
    void clientcore$setPostEffect(ResourceLocation id);
*///?} else {
    /*@Invoker("loadEffect")
    void clientcore$setPostEffect(ResourceLocation id);
*///?}
}
