package dev.wyz.clientcore.cosmetic

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.nametag.EffectRoster
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
/*
  Two different boundaries meet here, so they are two guards rather than one.

  PlayerSkin moved package and grew a Patch API in 1.21.9. The McId to
  Identifier rename did not happen until 1.21.11. Wrapping both in one condition meant
  1.21.9 had to pick a side and was wrong either way.
*/
//? if >=1.21.9 {
import net.minecraft.core.ClientAsset
import net.minecraft.world.entity.player.PlayerSkin
import java.util.Optional
//?} else {
/*import net.minecraft.client.resources.PlayerSkin
*///?}
import dev.wyz.clientcore.compat.McId
import java.util.UUID

/**
 * The River cape cosmetics. Several static pixel-art capes; the chosen one is patched
 * into a player's render state (see AvatarCapeMixin) and vanilla's CapeLayer renders it
 * with the free cloth sway. You pick your style in the Cape module; other River users
 * see your exact style through the presence roster.
 */
object RiverCape {

    /** The built-in styles, in the order released builds saved them (casaeullo is index 6). Only ever append. Ids must be lowercase a-z only (the presence worker strips anything else). */
    private val BUILTIN_STYLES = listOf(
        "river", "axie", "hardcore", "eclipse", "eightball", "halloween", "casaeullo", "plus", "gilded",
        "ember", "forest", "sunset", "storm", "galaxy",
        "royal", "prism", "custom"
    )

    private val BUILTIN_LABELS = listOf(
        "River", "Axie", "Hardcore", "Eclipse", "8-Ball", "Halloween", "Casaeullo", "Plus", "Gilded",
        "Ember", "Forest", "Sunset", "Storm", "Galaxy",
        "Royal", "Prism", "My Cape"
    )

    /**
     * Every style, aligned by index with [LABELS]: the built-in ones, then the server capes
     * this PC has redeemed. Only appended to, so a saved index keeps pointing at its cape.
     */
    val STYLES: List<String>
        get() = BUILTIN_STYLES + ServerCapes.styleIds()

    /** Display names for the wardrobe UI, aligned by index with [STYLES]. */
    val LABELS: List<String>
        get() = BUILTIN_LABELS + ServerCapes.labels()

    /** River Plus capes: wearable while Plus is on (the server keeps the same list). */
    val PLUS = setOf("plus", "gilded", "royal", "prism")

    /**
     * The Plus capes are animated: `<id>_0.png` to `<id>_59.png`, played in a loop. A frame's
     * texture is only loaded once something shows it, so only capes being worn cost memory.
     * The wardrobe cards keep the still `<id>.png`.
     */
    private const val FRAMES = 60
    private const val FRAME_MS = 33L // 30 frames a second: a two-second loop

    /** The texture to show for this style right now: the current frame for an animated cape. */
    @JvmStatic
    fun frame(style: String): String =
        if (style in PLUS) "${style}_${(System.currentTimeMillis() / FRAME_MS) % FRAMES}" else style

    /** The player's own picture. It stays on this PC: other players see the River cape. */
    const val CUSTOM = "custom"
    private const val CUSTOM_FILE = "custom_cape.png"
    private var customStamp = -1L
    private var customCheckedAt = 0L

    private fun customPath() = dev.wyz.clientcore.config.ConfigService.riverDirectory().resolve(CUSTOM_FILE)

    /** Starts the player's cape from the River one if there is none yet, and shows them the file. */
    fun openCustomCape() {
        runCatching {
            val path = customPath()
            if (!java.nio.file.Files.exists(path)) {
                RiverCape::class.java.getResourceAsStream("/assets/clientcore/textures/cape/river.png")?.use { java.nio.file.Files.copy(it, path) }
            }
            val os = System.getProperty("os.name", "").lowercase()
            val opener = if ("win" in os) "explorer" else if ("mac" in os) "open" else "xdg-open"
            ProcessBuilder(opener, path.parent.toString()).start()
        }
    }

    /** Loads the player's picture again when the file changes, so edits show while the game runs. */
    private fun ensureCustomTexture() {
        val now = System.currentTimeMillis()
        if (now - customCheckedAt < 1000L) return
        customCheckedAt = now
        runCatching {
            val path = customPath()
            val stamp = if (java.nio.file.Files.exists(path)) java.nio.file.Files.getLastModifiedTime(path).toMillis() else 0L
            if (stamp == customStamp) return
            customStamp = stamp
            var image = if (stamp > 0L) java.nio.file.Files.newInputStream(path).use { NativeImage.read(it) } else null
            // A cape picture is twice as wide as it is tall. Anything else would come out scrambled.
            if (image != null && (image.width != image.height * 2 || image.width > 4096)) {
                image.close()
                image = null
            }
            val shown = image ?: RiverCape::class.java.getResourceAsStream("/assets/clientcore/textures/cape/river.png")!!.use { NativeImage.read(it) }
            sizes[CUSTOM] = intArrayOf(shown.width, shown.height)
//? if >=1.21.5 {
            Minecraft.getInstance().textureManager.register(textureId(CUSTOM), DynamicTexture({ "river-cape-custom" }, shown))
//?} else {
/*            Minecraft.getInstance().textureManager.register(textureId(CUSTOM), DynamicTexture(shown))
*///?}
        }
    }

    /** Capes that only exist during their season (see Seasons): style id to season id. */
    private val SEASONAL = mapOf("halloween" to "halloween")

    /**
     * False for a seasonal cape outside its season, unless this player earned it for good
     * (SeasonEvent). It leaves the wardrobe, and someone still wearing it is shown the River
     * cape instead until it is back.
     */
    @JvmStatic
    fun available(style: String): Boolean {
        val season = SEASONAL[style] ?: return true
        return dev.wyz.clientcore.ui.Seasons.unlocked(season)
    }

//? if >=1.21.9 {
    private val assets = HashMap<String, ClientAsset.ResourceTexture>()
//?} else {
/**///?}
    private val loaded = HashSet<String>()
    private val sizes = HashMap<String, IntArray>()

    private fun textureId(style: String) = McId.fromNamespaceAndPath("clientcore", "textures/cape/$style.png")

/*
  ClientAsset, and the PlayerSkin.Patch API that consumes it, both arrived in 1.21.9,
  so this follows that boundary rather than the rename one.
*/
//? if >=1.21.9 {
    private fun assetFor(style: String): ClientAsset.ResourceTexture =
        assets.getOrPut(style) { val id = textureId(style); ClientAsset.ResourceTexture(id, id) }
//?} else {
/**///?}

    /** Drops what was remembered about a style, so its art is loaded again on the next use. */
    fun forget(style: String) {
        loaded.remove(style)
        sizes.remove(style)
    }

    private fun ensureTexture(style: String) {
        if (style == CUSTOM) return ensureCustomTexture()
        if (!loaded.add(style)) return
        runCatching {
            val stream = if (style.startsWith(ServerCapes.PREFIX)) {
                ServerCapes.open(style) ?: run { loaded.remove(style); return }
            } else {
                RiverCape::class.java.getResourceAsStream("/assets/clientcore/textures/cape/$style.png") ?: return
            }
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
    fun previewTexture(style: String): McId {
//?} else {
/*    fun previewTexture(style: String): McId {
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
            return if (module.active) module.selectedStyle().takeIf { available(it) } ?: STYLES[0] else null
        }
        // Not checked against available(): that is about this player's own seasons. Whether
        // somebody else may wear a seasonal cape is decided by the server, which drops it from
        // the roster for anyone who did not earn it.
        if (!dev.wyz.clientcore.module.impl.CosmeticsModule.showOthersCapes()) return null
        return EffectRoster.remoteCapeStyle(uuid)?.takeIf { it in STYLES }
    }

    /** Returns a copy of the skin with the given cape style patched in. */
    @JvmStatic
    fun applyCape(skin: PlayerSkin, style: String): PlayerSkin {
        val frame = frame(style)
        ensureTexture(frame)
        // The elytra get their own texture: the cape's art cut into the wing shape. Vanilla
        // would otherwise stretch the cape over the wings.
        // Server capes have no wing version yet, so they keep vanilla's wings.
        val wings = if (style.startsWith(ServerCapes.PREFIX)) null else "elytra_$style"
        if (wings != null) ensureTexture(wings)
//? if >=1.21.9 {
        val wingAsset = if (wings != null) Optional.of(assetFor(wings)) else Optional.empty()
        val patch = PlayerSkin.Patch.create(Optional.empty(), Optional.of(assetFor(frame)), wingAsset, Optional.empty())
        return skin.with(patch)
//?} else {
/*        return PlayerSkin(skin.texture(), skin.textureUrl(), textureId(frame), skin.elytraTexture(), skin.model(), skin.secure())
*///?}
    }
}
