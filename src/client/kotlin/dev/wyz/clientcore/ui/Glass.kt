package dev.wyz.clientcore.ui

import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if >=1.21.11 {
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.resource.CrossFrameResourcePool
import com.mojang.blaze3d.systems.RenderSystem
//? if >=26.3 {
/*import com.mojang.renderpearl.api.textures.FilterMode
*///?} else {
import com.mojang.blaze3d.textures.FilterMode
//?}
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.texture.AbstractTexture
import dev.wyz.clientcore.compat.McId
import net.minecraft.client.gui.render.TextureSetup
//?}

/**
 * Frosted glass behind River's panels: the world, blurred, seen through the panel.
 *
 * A panel cannot blur what is behind it on its own - by the time the GUI draws, the
 * world is already one flat image. So once a frame, after the world is drawn and before
 * any GUI, that image is copied aside and run through the game's own blur shader
 * (capture). A glass panel then draws the matching part of the blurred copy inside its
 * own shape (backdrop) and lays a tint and a light edge over it.
 *
 * Nothing is copied or blurred unless something drew glass in the last second, so with
 * no glass on screen this costs nothing. Without a world behind (the title screen) or on
 * a version this is not built for, backdrop() returns false and the caller draws its
 * usual solid surface.
 */
object Glass {

    @Volatile private var wantedAt = 0L
    private var captured = false
    /** When the blurred copy was last made (System.nanoTime), 0 when there is none to reuse. */
    private var blurredAt = 0L
    /** The blurred copy is remade this often at most. */
    private const val REFRESH_NS = 16_000_000L

    /** River's setting (Settings > Appearance). On unless turned off. */
    fun enabled(): Boolean = dev.wyz.clientcore.RiverRuntime.configOrNull()?.glassEffect != false && !ClientUi.minecraftUi

//? if >=1.21.11 {
    private val ID: McId = McId.fromNamespaceAndPath("clientcore", "glass/backdrop")
    private val BLUR: McId = McId.withDefaultNamespace("blur")
    private var target: RenderTarget? = null
    private var texture: BackdropTexture? = null
    private val pool = CrossFrameResourcePool(3)

    /** The blurred copy, as a texture the GUI can draw. Points at whichever target is current. */
    private class BackdropTexture : AbstractTexture() {
        fun show(source: RenderTarget) {
            texture = source.colorTexture
            textureView = source.colorTextureView
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        }

        // The target owns the GPU texture; it is released with the target, not here.
        override fun close() = Unit
    }

    /** From GlassCaptureMixin: the world is drawn, the GUI is not yet. */
    @JvmStatic
    fun capture() {
        captured = false
        val client = Minecraft.getInstance()
        if (!enabled() || client.level == null) return
        if (System.currentTimeMillis() - wantedAt > 1000L) return
        runCatching {
//? if >=26.2 {
/*            val main = client.gameRenderer.mainRenderTarget()
*///?} else {
            val main = client.mainRenderTarget
//?}
            val source = main.colorTexture ?: return
            /*
              The blur works on a half-size copy, once. It used to copy the full screen and
              blur it twice at full size every frame any glass was showing, which on its own
              cost a noticeable slice of FPS; a frosted backdrop shows only colour anyway, so
              the half-size copy (blurred, then stretched back over the panel) looks the same.
            */
//? if >=26.2 {
/*            val w = main.width
            val h = main.height
*///?} else {
            val w = (main.width / 2).coerceAtLeast(1)
            val h = (main.height / 2).coerceAtLeast(1)
//?}
            var copy = target
            /*
              The copy and the blur are the whole cost of glass, and at a high frame rate
              they ran several times for every frame a screen shows. A frosted backdrop one
              frame old looks the same, so the copy is remade about 60 times a second and
              the frames in between draw the one already there.
            */
            val now = System.nanoTime()
            if (copy != null && copy.width == w && copy.height == h && blurredAt != 0L && now - blurredAt < REFRESH_NS) {
                captured = true
                return
            }
            blurredAt = 0L
            if (copy == null || copy.width != w || copy.height != h) {
                copy?.destroyBuffers()
//? if >=26.3 {
/*copy = TextureTarget("River glass", w, h, source.format, null)
*///?} elif >=26.2 {
/*                copy = TextureTarget("River glass", w, h, false, source.format)
*///?} else {
                copy = TextureTarget("River glass", w, h, false)
//?}
                target = copy
                val tex = texture ?: BackdropTexture().also {
                    texture = it
                    client.textureManager.register(ID, it)
                }
                tex.show(copy)
            }
            val dest = copy.colorTexture ?: return
//? if >=26.2 {
/*            RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(source, dest, 0, 0, 0, 0, 0, main.width, main.height)
*///?} else {
            // Opaque first: the scaling blit writes colour only, and the GUI draws this copy's alpha.
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(dest, 0xFF000000.toInt())
            main.blitAndBlendToTexture(copy.colorTextureView!!)
//?}
            val chain = client.shaderManager.getPostChain(BLUR, LevelTargetBundle.MAIN_TARGETS) ?: return
            // Once is enough at half size: each blur step reaches twice as far across the picture.
            chain.process(copy, pool)
            pool.endFrame()
            captured = true
            blurredAt = now
        }
    }

    /**
     * Draws the blurred world behind (x, y, w, h), in GUI coordinates of the current pose,
     * with the same corner rounding fillRounded uses. False when there is nothing to draw:
     * the caller then draws its solid surface instead.
     */
    fun backdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int): Boolean =
        drawBackdrop(g, x, y, w, h, radius, 0, false)

    private fun drawBackdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, tint: Int, sheen: Boolean): Boolean {
        if (!enabled() || w <= 0 || h <= 0) return false
        wantedAt = System.currentTimeMillis()
        if (!captured) return false
        val view = target?.colorTextureView ?: return false
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        GlassQuad.draw(g, x, y, w, h, radius, tint, sheen, TextureSetup.singleTexture(view, sampler))
        return true
    }
//?} else {
/*    @JvmStatic
    fun capture() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun backdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int): Boolean = false

    private fun drawBackdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, tint: Int, sheen: Boolean): Boolean = false
*///?}

    /**
     * A glass surface: the blurred world and a tint of [tint] over it. Falls back to a solid
     * fill of [solid] where glass is not available. True when it drew glass, so the caller can
     * lighten what it puts on top. [sheen] is accepted and ignored: the light band along the top
     * edge is gone from every panel.
     */
    @Suppress("UNUSED_PARAMETER")
    fun surface(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, solid: Int, tint: Int = solid, tintAlpha: Float = 0.58f, sheen: Boolean = true): Boolean {
        if (drawBackdrop(g, x, y, w, h, radius, ClientUi.alpha(tint or 0xFF000000.toInt(), tintAlpha), false)) return true
        ClientUi.fillRounded(g, x, y, w, h, radius, solid)
        return false
    }
}
