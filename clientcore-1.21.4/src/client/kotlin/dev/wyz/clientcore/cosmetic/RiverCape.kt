package dev.wyz.clientcore.cosmetic

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.nametag.EffectRoster
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import java.util.UUID

/**
 * The River cape cosmetics. Several static pixel-art capes; the chosen one is patched
 * into a player's render state (see AvatarCapeMixin) and vanilla's CapeLayer renders it
 * with the free cloth sway. You pick your style in the Cape module; other River users
 * see your exact style through the presence roster.
 */
object RiverCape {

    /** Style ids, aligned by index with [LABELS]. Ids must be lowercase a-z only
     *  (the presence worker strips anything else). */
    val STYLES = listOf("river", "sakura", "moonrise")

    /** Display names for the wardrobe UI, aligned by index with [STYLES]. */
    val LABELS = listOf("River", "Sakura Valley", "Moonrise")

    private val loaded = HashSet<String>()
    private val sizes = HashMap<String, IntArray>()

    private fun textureId(style: String) = ResourceLocation.fromNamespaceAndPath("clientcore", "textures/cape/$style.png")

    private fun ensureTexture(style: String) {
        if (!loaded.add(style)) return
        runCatching {
            val stream = RiverCape::class.java.getResourceAsStream("/assets/clientcore/textures/cape/$style.png") ?: return
            val image = stream.use { NativeImage.read(it) }
            sizes[style] = intArrayOf(image.width, image.height)
            Minecraft.getInstance().textureManager.register(textureId(style), DynamicTexture(image))
        }
    }

    /** Texture id for UI previews (registers the texture on first use). */
    @JvmStatic
    fun previewTexture(style: String): ResourceLocation {
        ensureTexture(style)
        return textureId(style)
    }

    /** Actual pixel size of a cape texture, `[width, height]`. Logical layout is always 64x32. */
    @JvmStatic
    fun textureSize(style: String): IntArray {
        ensureTexture(style)
        return sizes[style] ?: intArrayOf(64, 32)
    }

    /** The cape style this player should wear, or null for none. Self reads the module;
     *  remote players come from the presence roster. */
    @JvmStatic
    fun capeStyleFor(uuid: UUID): String? {
        val self = Minecraft.getInstance().user.profileId
        if (uuid == self) {
            val module = ModuleRegistry.get<CapeModule>("cape") ?: return null
            return if (module.active) module.selectedStyle() else null
        }
        return EffectRoster.remoteCapeStyle(uuid)?.takeIf { it in STYLES }
    }

    /** Returns a copy of the skin with the given cape style patched in. 1.21.4's PlayerSkin is
     *  a plain record (no patch system yet), so this just rebuilds it with a new cape texture. */
    @JvmStatic
    fun applyCape(skin: PlayerSkin, style: String): PlayerSkin {
        ensureTexture(style)
        return PlayerSkin(skin.texture(), skin.textureUrl(), textureId(style), skin.elytraTexture(), skin.model(), skin.secure())
    }
}
