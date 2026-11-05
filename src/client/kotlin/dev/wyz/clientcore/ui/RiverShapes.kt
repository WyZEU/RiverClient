package dev.wyz.clientcore.ui

//? if >=1.21.6 {
//? if <26.1 {
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.platform.DepthTestFunction
//?}
//? if <26.2 {
import com.mojang.blaze3d.vertex.VertexFormat
//?}
//? if >=26.3 {
/*import com.mojang.renderpearl.api.pipeline.RenderPipeline
*///?} else {
import com.mojang.blaze3d.pipeline.RenderPipeline
//?}
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.wyz.clientcore.compat.McId
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
//? if >=26.1 {
/*import net.minecraft.client.renderer.state.gui.GuiElementRenderState
*///?} else {
import net.minecraft.client.gui.render.state.GuiElementRenderState
//?}
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f
//?}
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * Smooth GUI shapes drawn by River's own shader (assets/clientcore/shaders/core/gui_shape):
 * rounded rectangles, outlines and soft shadows or glows, each one quad cut to shape per
 * pixel with edges smoothed over a single screen pixel. ClientUi's fillRounded, outline and
 * shadow helpers go through here when [available], so the whole River UI gets round corners
 * that stay crisp at every GUI scale; elsewhere they fall back to their stepped versions.
 *
 * From 1.21.6 a shape is its own element handed to the GUI renderer with River's pipeline;
 * the small differences between those versions (a depth argument up to 1.21.8, the entity
 * vertex format's name and the pipeline setup on 26.x) are marked where they are.
 */
object RiverShapes {
//? if >=1.21.6 {
    val available: Boolean = true

//? if >=26.2 {
/*    // 26.x describes blending and depth as states; the GUI snippet already carries the GUI's.
    private val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
        .withLocation(McId.fromNamespaceAndPath("clientcore", "pipeline/gui_shape"))
        .withVertexShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withFragmentShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withVertexBinding(0, DefaultVertexFormat.ENTITY)
        .build()
*///?} elif >=26.1 {
/*    private val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
        .withLocation(McId.fromNamespaceAndPath("clientcore", "pipeline/gui_shape"))
        .withVertexShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withFragmentShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
        .build()
*///?} else {
    private val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
        .withLocation(McId.fromNamespaceAndPath("clientcore", "pipeline/gui_shape"))
        .withVertexShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withFragmentShader(McId.fromNamespaceAndPath("clientcore", "core/gui_shape"))
        .withBlend(BlendFunction.TRANSLUCENT)
        .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withCull(false)
        .build()
//?}

    /** One shape: the quad (x0..x1, y0..y1, already grown by any shadow) and the box it is cut to. */
    private class Shape(
        private val pose: Matrix3x2f,
        private val x0: Float, private val y0: Float, private val x1: Float, private val y1: Float,
        private val cx: Float, private val cy: Float,
        private val halfW: Int, private val halfH: Int, private val radius: Int, private val border: Int,
        private val softness: Float, private val flow: Float, private val color: Int,
        private val scissor: ScreenRectangle?, private val area: ScreenRectangle?
    ) : GuiElementRenderState {
//? if >=1.21.10 {
        override fun buildVertices(consumer: VertexConsumer) {
            val z = 0f
//?} else {
/*        override fun buildVertices(consumer: VertexConsumer, z: Float) {
*///?}
            corner(consumer, x0, y0, z)
            corner(consumer, x0, y1, z)
            corner(consumer, x1, y1, z)
            corner(consumer, x1, y0, z)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun corner(consumer: VertexConsumer, x: Float, y: Float, z: Float) {
//? if >=1.21.10 {
            consumer.addVertexWith2DPose(pose, x, y)
//?} else {
/*            consumer.addVertexWith2DPose(pose, x, y, z)
*///?}
                .setColor(color)
                .setUv(x - cx, y - cy)
                .setUv1(halfW, halfH)
                .setUv2(radius, border)
                .setNormal(softness / 64f, flowCoarse, flowFine)
        }

        // The light's place round the outline, split so a signed byte carries it smoothly.
        private val flowCoarse = if (flow < 0f) 0f else (Math.floor(flow * 127.0).toFloat() / 127f).coerceAtLeast(1f / 254f)
        private val flowFine = if (flow < 0f) 0f else (flow * 127f - Math.floor(flow * 127.0).toFloat())

        override fun pipeline(): RenderPipeline = PIPELINE
        override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = area
    }

    private fun submit(g: GuiGraphics, x: Float, y: Float, w: Float, h: Float, radius: Float, border: Float, softness: Float, color: Int, flow: Float = -1f) {
        if (w <= 0f || h <= 0f || (color ushr 24) == 0) return
        val r = radius.coerceIn(0f, minOf(w, h) / 2f)
        val grow = softness
        val pose = Matrix3x2f(g.pose())
        val scissor = g.scissorStack.peek()
        val qx = x - grow
        val qy = y - grow
        val qw = w + grow * 2
        val qh = h + grow * 2
        val box = ScreenRectangle(qx.toInt() - 1, qy.toInt() - 1, qw.toInt() + 3, qh.toInt() + 3).transformMaxBounds(pose)
        val area = if (scissor != null) scissor.intersection(box) else box
//? if >=26.1 {
/*        g.guiRenderState.addGuiElement(
*///?} else {
        g.guiRenderState.submitGuiElement(
//?}
            Shape(
                pose, qx, qy, qx + qw, qy + qh, x + w / 2f, y + h / 2f,
                (w * 8f).toInt(), (h * 8f).toInt(), (r * 16f).toInt(), (border * 16f).toInt(), softness, flow, color,
                scissor, area
            )
        )
    }

    fun fill(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) =
        submit(g, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), 0f, 0f, color)

    /** An outline [width] px thick, inside the box. */
    fun outline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, width: Float = 1f) =
        submit(g, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), width, 0f, color)

    /** An outline with a light travelling round it; [phase] (0 to 1) is where the light is. */
    fun flowingOutline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, phase: Float, width: Float = 1f) =
        submit(g, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), width, 0f, color, ((phase % 1f) + 1f) % 1f)

    /** A soft shadow or glow fading out over [softness] px around the box, never under it. */
    fun shadow(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, softness: Float) =
        submit(g, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), 0f, softness.coerceIn(1f, 63f), color)

    /** A circle of [diameter] at (x, y) as its top-left, sub-pixel positions allowed. */
    fun circle(g: GuiGraphics, x: Float, y: Float, diameter: Float, color: Int) =
        submit(g, x, y, diameter, diameter, diameter / 2f, 0f, 0f, color)
//?} else {
/*    val available: Boolean = false

    fun fill(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) = Unit
    fun outline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, width: Float = 1f) = Unit
    fun flowingOutline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, phase: Float, width: Float = 1f) = Unit
    fun shadow(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, softness: Float) = Unit
    fun circle(g: GuiGraphics, x: Float, y: Float, diameter: Float, color: Int) = Unit
*///?}
}
