package dev.wyz.clientcore.ui

import dev.wyz.clientcore.compat.McId
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.resources.ResourceLocation
*///?}

/**
 * Loads UI textures straight from the jar via the classloader and registers them
 * as dynamic textures. This works even when River is agent-injected (not a Fabric
 * mod), where the resource-pack pipeline never sees our assets.
 */
object RiverTextures {

//? if >=1.21.11 {
    private val cache = HashMap<String, Identifier?>()
//?} else {
/*    private val cache = HashMap<String, ResourceLocation?>()
*///?}

    /** [path] is relative to assets/clientcore/textures/ui/, without extension. */
//? if >=1.21.11 {
    fun get(path: String): Identifier? = cache.getOrPut(path) {
//?} else {
/*    fun get(path: String): ResourceLocation? = cache.getOrPut(path) {
*///?}
        runCatching {
            val stream = RiverTextures::class.java.getResourceAsStream(
                "/assets/clientcore/textures/ui/$path.png"
            ) ?: return@runCatching null
            val image = stream.use { NativeImage.read(it) }
//? if >=1.21.5 {
            val id = McId.fromNamespaceAndPath("clientcore", "river_ui_" + path.replace('/', '_'))
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "river-ui-$path" }, image))
//?} else {
/*            val id = ResourceLocation.fromNamespaceAndPath("clientcore", "river_ui_" + path.replace('/', '_'))
            Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
*///?}
            id
        }.getOrNull()
    }
}
