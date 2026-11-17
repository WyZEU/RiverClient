package dev.wyz.clientcore.ui

//? if >=1.21.11 {
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
import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.render.state.GuiElementRenderState
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f

/** The backdrop, tint, rounded edge and sheen of one glass panel in a single quad. */
object GlassQuad {
    private val pipeline = RenderPipeline.builder(
//? if >=26.1 {
/*        RenderPipelines.GUI_SNIPPET
*///?} else {
        RenderPipelines.MATRICES_PROJECTION_SNIPPET
//?}
    )
        .withLocation(McId.fromNamespaceAndPath("clientcore", "pipeline/gui_glass"))
        .withVertexShader(McId.fromNamespaceAndPath("clientcore", "core/gui_glass"))
        .withFragmentShader(McId.fromNamespaceAndPath("clientcore", "core/gui_glass"))
//? if >=26.2 {
/*        .withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0)
*///?} else {
        .withSampler("Sampler0")
//?}
//? if >=26.2 {
/*        .withVertexBinding(0, DefaultVertexFormat.ENTITY)
*///?} elif >=26.1 {
/*        .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
*///?} else {
        .withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
        .withBlend(BlendFunction.TRANSLUCENT)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withCull(false)
//?}
        .build()

    private class Panel(val pose: Matrix3x2f, val x: Int, val y: Int, val w: Int, val h: Int,
                        val radius: Int, val tint: Int, val sheen: Boolean,
                        val texture: TextureSetup, val scissor: ScreenRectangle?, val area: ScreenRectangle?) : GuiElementRenderState {
        override fun pipeline(): RenderPipeline = pipeline
        override fun textureSetup(): TextureSetup = texture
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = area
        override fun buildVertices(consumer: VertexConsumer) {
            corner(consumer, x, y)
            corner(consumer, x, y + h)
            corner(consumer, x + w, y + h)
            corner(consumer, x + w, y)
        }
        private fun corner(consumer: VertexConsumer, px: Int, py: Int) {
            consumer.addVertexWith2DPose(pose, px.toFloat(), py.toFloat())
                .setColor(tint).setUv(px - x - w / 2f, py - y - h / 2f)
                .setUv1(w * 8, h * 8).setUv2(radius * 16, if (sheen) 1 else 0)
                .setNormal(0f, 0f, 0f)
        }
    }

    fun draw(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, tint: Int, sheen: Boolean, texture: TextureSetup) {
        val pose = Matrix3x2f(g.pose())
        val scissor = g.scissorStack.peek()
        val box = ScreenRectangle(x, y, w, h).transformMaxBounds(pose)
        val area = if (scissor == null) box else scissor.intersection(box)
        val panel = Panel(pose, x, y, w, h, radius.coerceIn(0, minOf(w, h) / 2), tint, sheen, texture, scissor, area)
//? if >=26.1 {
/*        g.guiRenderState.addGuiElement(panel)
*///?} else {
        g.guiRenderState.submitGuiElement(panel)
//?}
    }
}
//?}
