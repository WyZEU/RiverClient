package dev.wyz.clientcore.cosmetic

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.nametag.EffectRoster
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
//? if >=1.21.11 {
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import java.util.Optional
//?} else {
/*import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
*///?}
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
    val STYLES = listOf("river", "sakura", "moonrise", "axie")

    /** Display names for the wardrobe UI, aligned by index with [STYLES]. */
    val LABELS = listOf("River", "Sakura Valley", "Moonrise", "Axie")

//? if >=1.21.11 {
    private val assets = HashMap<String, ClientAsset.ResourceTexture>()
//?} else {
/**///?}
    private val loaded = HashSet<String>()
    private val sizes = HashMap<String, IntArray>()

//? if >=1.21.11 {
    private fun textureId(style: String) = Identifier.fromNamespaceAndPath("clientcore", "textures/cape/$style.png")

    private fun assetFor(style: String): ClientAsset.ResourceTexture =
        assets.getOrPut(style) { val id = textureId(style); ClientAsset.ResourceTexture(id, id) }
//?} else {
/*    private fun textureId(style: String) = ResourceLocation.fromNamespaceAndPath("clientcore", "textures/cape/$style.png")
*///?}

    private fun ensureTexture(style: String) {
        if (!loaded.add(style)) return
        runCatching {
            val stream = RiverCape::class.java.getResourceAsStream("/assets/clientcore/textures/cape/$style.png") ?: return
            val image = stream.use { NativeImage.read(it) }
            sizes[style] = intArrayOf(image.width, image.height)
//? if >=1.21.5 {
            Minecraft.getInstance().textureManager.register(textureId(style), DynamicTexture({ "river-cape-$style" }, image))
//?} else {
/*            Minecraft.getInstance().textureManager.register(textureId(style), DynamicTexture(image))
*///?}
        }
    }

    /** Texture id for UI previews (registers the texture on first use). */
    @JvmStatic
//? if >=1.21.11 {
    fun previewTexture(style: String): Identifier {
//?} else {
/*    fun previewTexture(style: String): ResourceLocation {
*///?}
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

    /** Returns a copy of the skin with the given cape style patched in. */
    @JvmStatic
    fun applyCape(skin: PlayerSkin, style: String): PlayerSkin {
        ensureTexture(style)
//? if >=1.21.11 {
        val patch = PlayerSkin.Patch.create(Optional.empty(), Optional.of(assetFor(style)), Optional.empty(), Optional.empty())
        return skin.with(patch)
//?} else {
/*        return PlayerSkin(skin.texture(), skin.textureUrl(), textureId(style), skin.elytraTexture(), skin.model(), skin.secure())
*///?}
    }
}
