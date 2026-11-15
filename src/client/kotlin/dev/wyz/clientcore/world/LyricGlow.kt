package dev.wyz.clientcore.world

//? if >=1.21.6 && <26.2 {
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.resource.CrossFrameResourcePool
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import dev.wyz.clientcore.compat.McId
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelTargetBundle
//?}
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource
//?}

/**
 * The real glow behind the 3D lyrics, done on the GPU. While the world is drawn the words are
 * also drawn into a layer of their own, depth-tested against the world so walls still hide
 * them. Once the world is finished, that layer is blurred by the post effect
 * `clientcore:lyric_glow` (two wide box blurs, then a soft falloff kept off the letters) and
 * blended over the screen, before the GUI.
 *
 * 1.21.6 to 26.1. Elsewhere, or if the effect fails to load, [begin] returns null and
 * LyricsWorldRenderer draws its older halo of faint copies instead.
 */
object LyricGlow {
//? if >=1.21.6 && <26.2 {
    private var chain: McId? = null
    private val buffers = MultiBufferSource.immediate(ByteBufferBuilder(1 shl 16))
    private val pool = CrossFrameResourcePool(3)
    private var target: TextureTarget? = null
    private var drawn = false
    private var failed = false

    /**
     * A clear glow layer holding the world's depth, to draw this frame's words into; null when
     * glow cannot run or both [glow] and [outline] (0 to 1) are off. Post effects have fixed
     * settings, so the strengths pick one of the prepared variants (post_effect/lyric_glow_g*_o*).
     */
    fun begin(glow: Float, outline: Float): MultiBufferSource? {
        if (failed) return null
        val g = (glow * 3f + 0.49f).toInt().coerceIn(0, 3)
        val o = if (outline > 0.02f) 1 else 0
        if (g == 0 && o == 0) return null
        chain = McId.fromNamespaceAndPath("clientcore", "lyric_glow_g${g}_o$o")
        return runCatching {
            val main = Minecraft.getInstance().mainRenderTarget
            var layer = target
            if (layer == null || layer.width != main.width || layer.height != main.height) {
                layer?.destroyBuffers()
                layer = TextureTarget("River lyric glow", main.width, main.height, true)
                target = layer
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(layer.colorTexture!!, 0)
            layer.copyDepthFrom(main)
            buffers
        }.onFailure { failed = true }.getOrNull()
    }

    /** Draws what was put in [begin]'s buffers into the glow layer. */
    fun end() {
        val layer = target ?: return
        RenderSystem.outputColorTextureOverride = layer.colorTextureView
        RenderSystem.outputDepthTextureOverride = layer.depthTextureView
        try {
            buffers.endBatch()
            drawn = true
        } catch (e: Exception) {
            failed = true
        } finally {
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
        }
    }

    /** From GlassCaptureMixin, after the world and before the GUI: blur the layer and lay it over the screen. */
    @JvmStatic
    fun composite() {
        if (!drawn) return
        drawn = false
        val layer = target ?: return
        runCatching {
            val client = Minecraft.getInstance()
            val effect = client.shaderManager.getPostChain(chain ?: return, LevelTargetBundle.MAIN_TARGETS)
            if (effect == null) {
                failed = true
                return
            }
            effect.process(layer, pool)
            pool.endFrame()
            layer.blitAndBlendToTexture(client.mainRenderTarget.colorTextureView!!)
        }.onFailure { failed = true }
    }
//?} elif >=26.2 {
/*    fun end() = Unit

    @JvmStatic
    fun composite() = Unit
*///?} else {
/*    fun begin(glow: Float, outline: Float): MultiBufferSource? = null

    fun end() = Unit

    @JvmStatic
    fun composite() = Unit
*///?}
}
