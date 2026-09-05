package dev.wyz.clientcore.world

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.wyz.clientcore.config.WaypointData
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.WaypointsModule
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
*///?} elif >=1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
*///?}
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderTypes
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Waypoint world markers: a translucent beacon-style beam rising from the spot,
 * plus a floating name + distance label that stays visible through walls
 * (SEE_THROUGH text, same technique as nametags). Far labels get pulled toward
 * the camera with distance-proportional scale so they keep a constant, readable
 * on-screen size. User-placed positions only, nothing is revealed.
 *
 * Render order matters: all beam geometry is emitted first, then all labels.
 * Text rendering ends the previous batch in the shared buffer source, so a
 * geometry consumer must never be reused after text has been drawn.
 *
 * Only `render` is written per game version. 26.1 replaced Fabric's world render events
 * with level render events, stopped handing out a buffer source in favour of a
 * submit-node collector, and deleted Font.drawInBatch; 26.2 then turned the camera
 * accessor from a field into a method. What changed across all of that is how a vertex
 * consumer and a text draw are obtained, not what gets drawn with them - so both are
 * passed into the two shared passes below, which every version runs unchanged.
 */
object WaypointWorldRenderer {

    private const val LABEL_CLAMP_DISTANCE = 24.0
    private const val LABEL_SCALE_PER_BLOCK = 0.0035f
    private const val FULL_BRIGHT = 15728880
    private const val BEAM_HALF_WIDTH = 0.18f
    private const val BEAM_HEIGHT = 48f

    private var registered = false

    /** One label draw, issued against whichever text path the running version has. */
    private fun interface TextSink {
        fun draw(x: Float, y: Float, text: String, color: Int, mode: Font.DisplayMode, background: Int)
    }

    fun initialize() {
        if (registered) return
        registered = true
//? if >=26.1 {
/*        LevelRenderEvents.AFTER_SOLID_FEATURES.register { context -> render(context) }
*///?} else {
        WorldRenderEvents.AFTER_ENTITIES.register { context -> render(context) }
//?}
    }

    private fun shouldRender(client: Minecraft): Boolean {
        val module = ModuleRegistry.get<WaypointsModule>("waypoints") ?: return false
        if (!module.active) return false
        if (client.level == null || client.player == null) return false
        return module.editorWaypointSettings().showWorldMarkers
    }

//? if >=26.2 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        if (!shouldRender(client)) return
        val module = ModuleRegistry.get<WaypointsModule>("waypoints") ?: return
        val waypoints = module.visibleWaypoints(client)
        if (waypoints.isEmpty()) return

        val camera = client.gameRenderer.mainCamera()
        val cameraPos = camera.position()
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()

        // Geometry is handed over through a callback here rather than written into a
        // buffer fetched up front, so the whole beam pass runs inside it.
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads()) { pose, consumer ->
            emitBeams(waypoints, pose, consumer, cameraPos)
        }

        emitLabels(client, poseStack, camera, cameraPos, waypoints) { x, y, text, color, mode, background ->
            collector.submitText(
                poseStack, x, y, FormattedCharSequence.forward(text, Style.EMPTY),
                false, mode, FULL_BRIGHT, color, background, 0
            )
        }
    }
*///?} elif >=26.1 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        if (!shouldRender(client)) return
        val module = ModuleRegistry.get<WaypointsModule>("waypoints") ?: return
        val waypoints = module.visibleWaypoints(client)
        if (waypoints.isEmpty()) return

        val camera = client.gameRenderer.mainCamera
        val cameraPos = camera.position()
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()

        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads()) { pose, consumer ->
            emitBeams(waypoints, pose, consumer, cameraPos)
        }

        emitLabels(client, poseStack, camera, cameraPos, waypoints) { x, y, text, color, mode, background ->
            collector.submitText(
                poseStack, x, y, FormattedCharSequence.forward(text, Style.EMPTY),
                false, mode, FULL_BRIGHT, color, background, 0
            )
        }
    }
*///?} elif >=1.21.11 {
    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        if (!shouldRender(client)) return
        val module = ModuleRegistry.get<WaypointsModule>("waypoints") ?: return
        val waypoints = module.visibleWaypoints(client)
        if (waypoints.isEmpty()) return

        val camera = client.gameRenderer.mainCamera
        val cameraPos = camera.position()
        val poseStack = context.matrices()
        val consumers = context.consumers()

        emitBeams(waypoints, poseStack.last(), consumers.getBuffer(RenderTypes.debugQuads()), cameraPos)

        emitLabels(client, poseStack, camera, cameraPos, waypoints) { x, y, text, color, mode, background ->
            client.font.drawInBatch(
                text, x, y, color, false, poseStack.last().pose(), consumers, mode, background, FULL_BRIGHT
            )
        }
    }
//?} else {
/*    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        if (!shouldRender(client)) return
        val module = ModuleRegistry.get<WaypointsModule>("waypoints") ?: return
        val waypoints = module.visibleWaypoints(client)
        if (waypoints.isEmpty()) return

        val camera = client.gameRenderer.mainCamera
        val cameraPos = camera.getPosition()
        // Both are @Nullable in this Fabric API version's interface even though the
        // event always supplies them; non-null since there's nothing sane to draw without them.
        val poseStack = context.matrixStack()!!
        val consumers = context.consumers()!!

        emitBeams(waypoints, poseStack.last(), consumers.getBuffer(RenderType.debugQuads()), cameraPos)

        emitLabels(client, poseStack, camera, cameraPos, waypoints) { x, y, text, color, mode, background ->
            client.font.drawInBatch(
                text, x, y, color, false, poseStack.last().pose(), consumers, mode, background, FULL_BRIGHT
            )
        }
    }
*///?}

    /** Pass 1: every beam and block highlight, one consumer, no text in between. */
    private fun emitBeams(
        waypoints: List<WaypointData>,
        pose: PoseStack.Pose,
        quads: VertexConsumer,
        cameraPos: Vec3
    ) {
        waypoints.forEach { wp ->
            val color = WaypointsModule.colorOf(wp)
            drawBeam(
                pose, quads,
                (wp.x + 0.5 - cameraPos.x).toFloat(),
                (wp.y - 1.0 - cameraPos.y).toFloat(),
                (wp.z + 0.5 - cameraPos.z).toFloat(),
                color
            )
            // Same base height as the beam, so the highlighted block reads as the thing
            // the beam is standing on rather than floating a block off it.
            drawBlockHighlight(
                pose, quads,
                (wp.x - cameraPos.x).toFloat(),
                (wp.y - 1.0 - cameraPos.y).toFloat(),
                (wp.z - cameraPos.z).toFloat(),
                color
            )
        }
    }

    /** Pass 2: labels. */
    private fun emitLabels(
        client: Minecraft,
        poseStack: PoseStack,
        camera: Camera,
        cameraPos: Vec3,
        waypoints: List<WaypointData>,
        text: TextSink
    ) {
        val font = client.font
        waypoints.forEach { wp ->
            val color = WaypointsModule.colorOf(wp)
            val dx = wp.x + 0.5 - cameraPos.x
            val dy = (wp.y + 1.9) - cameraPos.y
            val dz = wp.z + 0.5 - cameraPos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < 0.75) return@forEach

            val clamp = if (dist > LABEL_CLAMP_DISTANCE) LABEL_CLAMP_DISTANCE / dist else 1.0
            val scale = LABEL_SCALE_PER_BLOCK * (dist * clamp).toFloat().coerceAtLeast(2f)

            poseStack.pushPose()
            poseStack.translate((dx * clamp).toFloat(), (dy * clamp).toFloat(), (dz * clamp).toFloat())
            poseStack.mulPose(camera.rotation())
            poseStack.scale(scale, -scale, scale)

            val name = wp.name
            val distText = "${dist.roundToInt()}m"
            val nameW = font.width(name)
            val totalW = nameW + 5 + font.width(distText)
            val xStart = -totalW / 2f

            // Soft pass visible through terrain, then a crisp pass where in view.
            text.draw(xStart, -10f, name, withAlpha(color, 0x88), Font.DisplayMode.SEE_THROUGH, 0x50000000)
            text.draw(xStart + nameW + 5, -10f, distText, 0x88E6E9F2.toInt(), Font.DisplayMode.SEE_THROUGH, 0x50000000)
            text.draw(xStart, -10f, name, color, Font.DisplayMode.NORMAL, 0)
            text.draw(xStart + nameW + 5, -10f, distText, 0xFFE6E9F2.toInt(), Font.DisplayMode.NORMAL, 0)
            poseStack.popPose()
        }
    }

    /** Four translucent faces rising from the base, fading out toward the top. */
    private fun drawBeam(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        color: Int
    ) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val bottomAlpha = 110
        val topAlpha = 0
        val w = BEAM_HALF_WIDTH
        val top = y + BEAM_HEIGHT

        fun face(x0: Float, z0: Float, x1: Float, z1: Float) {
            consumer.addVertex(pose, x + x0, y, z + z0).setColor(r, g, b, bottomAlpha)
            consumer.addVertex(pose, x + x1, y, z + z1).setColor(r, g, b, bottomAlpha)
            consumer.addVertex(pose, x + x1, top, z + z1).setColor(r, g, b, topAlpha)
            consumer.addVertex(pose, x + x0, top, z + z0).setColor(r, g, b, topAlpha)
        }

        face(-w, -w, w, -w)
        face(w, -w, w, w)
        face(w, w, -w, w)
        face(-w, w, -w, -w)
    }

    /**
     * Translucent shell around the exact block the waypoint sits on, so the spot is
     * identifiable when you are standing next to it - the beam alone tells you roughly
     * where to walk, but not which block.
     *
     * Inflated by a hair because a box drawn flush with the block's own faces z-fights
     * with them and flickers as the camera moves.
     */
    private fun drawBlockHighlight(
        pose: PoseStack.Pose,
        consumer: VertexConsumer,
        x: Float,
        y: Float,
        z: Float,
        color: Int
    ) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val alpha = 70
        val bulge = 0.006f

        val x0 = x - bulge
        val y0 = y - bulge
        val z0 = z - bulge
        val x1 = x + 1f + bulge
        val y1 = y + 1f + bulge
        val z1 = z + 1f + bulge

        fun quad(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float
        ) {
            consumer.addVertex(pose, ax, ay, az).setColor(r, g, b, alpha)
            consumer.addVertex(pose, bx, by, bz).setColor(r, g, b, alpha)
            consumer.addVertex(pose, cx, cy, cz).setColor(r, g, b, alpha)
            consumer.addVertex(pose, dx, dy, dz).setColor(r, g, b, alpha)
        }

        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1) // bottom
        quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0) // top
        quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0) // north
        quad(x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1) // south
        quad(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0) // west
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1) // east
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)
}
