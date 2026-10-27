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
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.texture.AbstractTexture
import dev.wyz.clientcore.compat.McId
import org.joml.Vector2f
//?}
import kotlin.math.min

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

    /** River's setting (Settings > Appearance). On unless turned off. */
    fun enabled(): Boolean = dev.wyz.clientcore.RiverRuntime.configOrNull()?.glassEffect != false

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
            var copy = target
            if (copy == null || copy.width != main.width || copy.height != main.height) {
                copy?.destroyBuffers()
//? if >=26.2 {
/*                copy = TextureTarget("River glass", main.width, main.height, false, source.format)
*///?} else {
                copy = TextureTarget("River glass", main.width, main.height, false)
//?}
                target = copy
                val tex = texture ?: BackdropTexture().also {
                    texture = it
                    client.textureManager.register(ID, it)
                }
                tex.show(copy)
            }
            val dest = copy.colorTexture ?: return
            RenderSystem.getDevice().createCommandEncoder()
                .copyTextureToTexture(source, dest, 0, 0, 0, 0, 0, main.width, main.height)
            val chain = client.shaderManager.getPostChain(BLUR, LevelTargetBundle.MAIN_TARGETS) ?: return
            // Twice: the menu blur alone still shows shapes; glass should only show colour.
            chain.process(copy, pool)
            chain.process(copy, pool)
            pool.endFrame()
            captured = true
        }
    }

    /**
     * Draws the blurred world behind (x, y, w, h), in GUI coordinates of the current pose,
     * with the same corner rounding fillRounded uses. False when there is nothing to draw:
     * the caller then draws its solid surface instead.
     */
    fun backdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int): Boolean {
        if (!enabled() || w <= 0 || h <= 0) return false
        wantedAt = System.currentTimeMillis()
        if (!captured || texture == null) return false
        val client = Minecraft.getInstance()
        val sw = client.window.guiScaledWidth.toFloat()
        val sh = client.window.guiScaledHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return false

        val pose = g.pose()
        val point = Vector2f()
        fun screen(px: Int, py: Int): Vector2f {
            point.set(px.toFloat(), py.toFloat())
            return pose.transformPosition(point)
        }

        val r = min(radius, min(w, h) / 2).coerceAtLeast(0)
        val insets = ClientUi.cornerInsetsFor(h, r)
        var start = 0
        while (start < h) {
            val inset = insets[start]
            var end = start + 1
            while (end < h && insets[end] == inset) end++
            val x0 = x + inset
            val x1 = x + w - inset
            val y0 = y + start
            val y1 = y + end
            val a = screen(x0, y0)
            val u0 = a.x / sw
            val v0 = 1f - a.y / sh
            val b = screen(x1, y1)
            val u1 = b.x / sw
            val v1 = 1f - b.y / sh
            // Render targets are stored bottom-up, so v runs from 1 at the top of the screen.
            g.blit(ID, x0, y0, x1, y1, u0, u1, v0, v1)
            start = end
        }
        return true
    }
//?} else {
/*    @JvmStatic
    fun capture() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun backdrop(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int): Boolean = false
*///?}

    /**
     * A glass surface: the blurred world, a tint of [tint] over it, and a faint light top
     * edge. Falls back to a solid fill of [solid] where glass is not available. True when
     * it drew glass, so the caller can lighten what it puts on top.
     */
    fun surface(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, solid: Int, tint: Int = solid, tintAlpha: Float = 0.58f): Boolean {
        if (!backdrop(g, x, y, w, h, radius)) {
            ClientUi.fillRounded(g, x, y, w, h, radius, solid)
            return false
        }
        ClientUi.fillRounded(g, x, y, w, h, radius, ClientUi.alpha(tint or 0xFF000000.toInt(), tintAlpha))
        // A soft sheen down from the top edge, the light catching the glass.
        val sheen = (h / 3).coerceIn(2, 24)
        g.fillGradient(x + 1, y + 1, x + w - 1, y + 1 + sheen, 0x14FFFFFF, 0x00FFFFFF)
        return true
    }
}
