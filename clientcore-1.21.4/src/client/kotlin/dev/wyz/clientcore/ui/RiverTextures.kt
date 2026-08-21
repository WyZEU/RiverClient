package dev.wyz.clientcore.ui

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

/**
 * Loads UI textures straight from the jar via the classloader and registers them
 * as dynamic textures. This works even when River is agent-injected (not a Fabric
 * mod), where the resource-pack pipeline never sees our assets.
 */
object RiverTextures {

    private val cache = HashMap<String, ResourceLocation?>()

    /** [path] is relative to assets/clientcore/textures/ui/, without extension. */
    fun get(path: String): ResourceLocation? = cache.getOrPut(path) {
        runCatching {
            val stream = RiverTextures::class.java.getResourceAsStream(
                "/assets/clientcore/textures/ui/$path.png"
            ) ?: return@runCatching null
            val image = stream.use { NativeImage.read(it) }
            val id = ResourceLocation.fromNamespaceAndPath("clientcore", "river_ui_" + path.replace('/', '_'))
            Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
            id
        }.getOrNull()
    }
}
