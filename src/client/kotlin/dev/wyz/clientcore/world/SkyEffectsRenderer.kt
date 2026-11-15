package dev.wyz.clientcore.world

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.SkyEffectsModule
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
*///?} elif >=1.21.10 {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
*///?}
import net.minecraft.client.Minecraft
//? if >=1.21.11 {
import net.minecraft.client.renderer.rendertype.RenderTypes
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws SkyEffectsModule's aurora and shooting stars. Everything is a translucent coloured quad
 * placed around the camera at a fixed distance, so it moves with you like a sky and never needs a
 * shader. The aurora is folded curtains that ring the whole sky around you, with a long band
 * arcing overhead, each a ribbon of short segments whose height, brightness and rays drift over time.
 *
 * Only `render` is written per game version, as in WaypointWorldRenderer: what differs between
 * versions is how a vertex consumer is obtained, not what is drawn with it.
 */
object SkyEffectsRenderer {

    /** Pieces each curtain is split into along its length. */
    private const val SEGMENTS = 220
    /** The most alpha an aurora row reaches, before the module's brightness scales it. */
    private const val PEAK_ALPHA = 215f

    private var registered = false

    fun initialize() {
        if (registered) return
        registered = true
//? if >=26.1 {
/*        LevelRenderEvents.AFTER_SOLID_FEATURES.register { context -> render(context) }
*///?} else {
        WorldRenderEvents.AFTER_ENTITIES.register { context -> render(context) }
//?}
    }

    private fun module(client: Minecraft): SkyEffectsModule? {
        if (client.level == null || client.player == null) return null
        val module = ModuleRegistry.get<SkyEffectsModule>("sky_effects") ?: return null
        return if (module.active) module else null
    }

//? if >=26.2 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        val module = module(client) ?: return
        if (module.auroraStrength() <= 0.01f && module.stars().isEmpty()) return
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads()) { pose, consumer ->
            emit(pose, consumer, module, dev.wyz.clientcore.dev.DevClock.millis())
        }
    }
*///?} elif >=26.1 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        val module = module(client) ?: return
        if (module.auroraStrength() <= 0.01f && module.stars().isEmpty()) return
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()
        collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads()) { pose, consumer ->
            emit(pose, consumer, module, dev.wyz.clientcore.dev.DevClock.millis())
        }
    }
*///?} elif >=1.21.11 {
    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        val module = module(client) ?: return
        if (module.auroraStrength() <= 0.01f && module.stars().isEmpty()) return
        val poseStack = context.matrices()
        val consumers = context.consumers()
        emit(poseStack.last(), consumers.getBuffer(RenderTypes.debugQuads()), module, dev.wyz.clientcore.dev.DevClock.millis())
    }
//?} elif >=1.21.10 {
/*    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        val module = module(client) ?: return
        if (module.auroraStrength() <= 0.01f && module.stars().isEmpty()) return
        val poseStack = context.matrices()
        val consumers = context.consumers()
        emit(poseStack.last(), consumers.getBuffer(RenderType.debugQuads()), module, dev.wyz.clientcore.dev.DevClock.millis())
    }
*///?} else {
/*    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        val module = module(client) ?: return
        if (module.auroraStrength() <= 0.01f && module.stars().isEmpty()) return
        // Both are @Nullable in this Fabric API version's interface even though the event
        // always supplies them; non-null since there is nothing to draw without them.
        val poseStack = context.matrixStack()!!
        val consumers = context.consumers()!!
        emit(poseStack.last(), consumers.getBuffer(RenderType.debugQuads()), module, dev.wyz.clientcore.dev.DevClock.millis())
    }
*///?}

    private fun emit(pose: PoseStack.Pose, quads: VertexConsumer, module: SkyEffectsModule, nowMs: Long) {
        val strength = module.auroraStrength()
        if (strength > 0.01f) drawAurora(pose, quads, module, strength, nowMs / 1000.0 * module.speed())
        for (star in module.stars()) drawStar(pose, quads, star, nowMs, dev.wyz.clientcore.ui.RiverColor.resolve(module.starColor(), (star.bornMs % 1000L) / 1000f) and 0xFFFFFF, module.trail())
    }

    // ------------------------------------------------------------------ aurora

    /**
     * One curtain of light: a closed ribbon all the way around the camera, [radius] blocks out,
     * its lower edge [altitude] blocks up and [height] tall. An [overhead] one is instead a long
     * straight band through the sky above, [radius] blocks off centre, fading out at both ends: a
     * small ring up there looked like a flower with a hole in it. Further curtains come first so
     * the nearer ones are drawn over them.
     */
    private class Curtain(val radius: Float, val altitude: Float, val height: Float, val weight: Float, val phase: Float, val overhead: Boolean = false)

    private val CURTAINS = listOf(
        Curtain(165f, 62f, 70f, 0.75f, 2.1f),
        Curtain(95f, 82f, 95f, 1.0f, 0f),
        Curtain(14f, 105f, 85f, 0.8f, 4.3f, overhead = true)
    )

    /** Which curtains each Size setting shows: Small is the middle one only. */
    private fun curtainsFor(count: Int): List<Curtain> = when (count) {
        1 -> listOf(CURTAINS[1])
        2 -> listOf(CURTAINS[0], CURTAINS[1])
        else -> CURTAINS
    }

    /**
     * Rows up a curtain, as a fraction of its height, with how bright each is and how far it has
     * turned from the bottom colour to the top colour. A thin bright lower edge, a strong body just
     * above it, and a long fade into the top colour, the way real aurora looks.
     */
    private val ROW_AT = floatArrayOf(-0.03f, 0f, 0.07f, 0.3f, 0.62f, 1f)
    private val ROW_ALPHA = floatArrayOf(0f, 0.9f, 1f, 0.55f, 0.2f, 0f)
    private val ROW_TOP = floatArrayOf(0f, 0f, 0.05f, 0.3f, 0.75f, 1f)
    private val ROW_EDGE = floatArrayOf(0.6f, 0.55f, 0.1f, 0f, 0f, 0f)

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val r = (((a shr 16) and 0xFF) * (1 - u) + ((b shr 16) and 0xFF) * u).toInt()
        val g = (((a shr 8) and 0xFF) * (1 - u) + ((b shr 8) and 0xFF) * u).toInt()
        val bl = ((a and 0xFF) * (1 - u) + (b and 0xFF) * u).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** Bottom and top colour of a palette (see SkyEffectsModule.PALETTES) at point [s] along a curtain. */
    private fun colors(module: SkyEffectsModule, s: Float, t: Double): Pair<Int, Int> = when (module.palette()) {
        0 -> 0x2EF08A to 0x9B4DFF
        1 -> 0x36D8E8 to 0x3F6BFF
        2 -> 0xA855FF to 0xFF4FA3
        3 -> 0xFF5C9E to 0x8A4DFF
        SkyEffectsModule.SHIFTING -> {
            // Drifts through green, teal and violet, so stretches of the sky differ and change.
            val a = (0.5 + 0.5 * sin(s * PI + t * 0.05)).toFloat()
            val b = (0.5 + 0.5 * sin(2.0 * s * PI - t * 0.037 + 2.0)).toFloat()
            mix(mix(0x2EF08A, 0x36D8E8, a), 0xA855FF, b * 0.7f) to mix(mix(0x9B4DFF, 0x3F6BFF, a), 0xFF4FA3, b * 0.6f)
        }
        else -> {
            // The same at both ends of the ribbon, so a rainbow custom colour has no seam.
            val around = sin(s * PI).toFloat() * 0.15f
            (dev.wyz.clientcore.ui.RiverColor.resolve(module.customBottom(), around) and 0xFFFFFF) to (dev.wyz.clientcore.ui.RiverColor.resolve(module.customTop(), around + 0.3f) and 0xFFFFFF)
        }
    }

    private fun drawAurora(pose: PoseStack.Pose, quads: VertexConsumer, module: SkyEffectsModule, strength: Float, t: Double) {
        val rays = module.rays()
        val rows = ROW_AT.size
        val xs = FloatArray(SEGMENTS + 1)
        val zs = FloatArray(SEGMENTS + 1)
        val ys = Array(rows) { FloatArray(SEGMENTS + 1) }
        val cs = Array(rows) { IntArray(SEGMENTS + 1) }
        val alphas = Array(rows) { FloatArray(SEGMENTS + 1) }

        for (curtain in curtainsFor(module.curtains())) {
            val p = curtain.phase.toDouble()
            for (i in 0..SEGMENTS) {
                // Every wave below repeats a whole number of times around the circle, so the
                // ribbon's two ends meet without a seam.
                val s = i * 2f / SEGMENTS - 1f
                val sd = s.toDouble() * PI
                // Folds: a long sway, a tighter ripple and small kinks, all drifting at different speeds.
                val fold = 26.0 * sin(3.0 * sd + 0.11 * t + p) + 10.0 * sin(7.0 * sd - 0.23 * t + 2 * p) + 3.5 * sin(17.0 * sd + 0.5 * t)
                if (curtain.overhead) {
                    xs[i] = s * 300f
                    zs[i] = -curtain.radius + fold.toFloat()
                } else {
                    val out = curtain.radius + fold
                    xs[i] = (out * sin(sd)).toFloat()
                    zs[i] = (-out * kotlin.math.cos(sd)).toFloat()
                }
                val bottom = curtain.altitude + (7.0 * sin(4.0 * sd + 0.15 * t + p)).toFloat()

                // Slow brighter and dimmer stretches along the length, never fully out.
                val patches = (0.45 + 0.55 * (0.5 + 0.5 * sin(5.0 * sd - 0.07 * t + p))).toFloat()
                // Rays: thin vertical streaks that brighten, stretch and slide sideways.
                val ray = if (rays) {
                    val a = 0.5 + 0.5 * sin(26.0 * sd + 1.1 * t + p)
                    val b = 0.5 + 0.5 * sin(15.0 * sd - 0.7 * t + 2 * p)
                    Math.pow(a * b, 0.7).toFloat()
                } else 0.5f
                val ends = if (curtain.overhead) smooth(1f, 0.7f, kotlin.math.abs(s)) else 1f
                val bright = strength * curtain.weight * ends * patches * (0.45f + 0.55f * ray) * PEAK_ALPHA
                val tall = curtain.height * (0.78f + 0.4f * ray)

                val (low, high) = colors(module, s, t)
                val edge = mix(low, 0xFFFFFF, 0.55f)
                for (r in 0 until rows) {
                    ys[r][i] = bottom + ROW_AT[r] * tall
                    alphas[r][i] = bright * ROW_ALPHA[r]
                    cs[r][i] = mix(mix(low, high, ROW_TOP[r]), edge, ROW_EDGE[r])
                }
            }
            for (i in 0 until SEGMENTS) {
                val j = i + 1
                if (alphas[2][i] < 1f && alphas[2][j] < 1f) continue
                for (r in 0 until rows - 1) {
                    strip(pose, quads,
                        xs[i], zs[i], ys[r][i], cs[r][i], alphas[r][i], xs[j], zs[j], ys[r][j], cs[r][j], alphas[r][j],
                        ys[r + 1][i], cs[r + 1][i], alphas[r + 1][i], ys[r + 1][j], cs[r + 1][j], alphas[r + 1][j])
                }
            }
        }
    }

    /** One quad between two rows on columns i and j (each row has its own height, colour and alpha). */
    private fun strip(
        pose: PoseStack.Pose, quads: VertexConsumer,
        xi: Float, zi: Float, yBottomI: Float, colorBottomI: Int, alphaBottomI: Float,
        xj: Float, zj: Float, yBottomJ: Float, colorBottomJ: Int, alphaBottomJ: Float,
        yTopI: Float, colorTopI: Int, alphaTopI: Float,
        yTopJ: Float, colorTopJ: Int, alphaTopJ: Float
    ) {
        vertex(pose, quads, xi, yBottomI, zi, colorBottomI, alphaBottomI)
        vertex(pose, quads, xj, yBottomJ, zj, colorBottomJ, alphaBottomJ)
        vertex(pose, quads, xj, yTopJ, zj, colorTopJ, alphaTopJ)
        vertex(pose, quads, xi, yTopI, zi, colorTopI, alphaTopI)
    }

    private fun vertex(pose: PoseStack.Pose, quads: VertexConsumer, x: Float, y: Float, z: Float, color: Int, alpha: Float) {
        quads.addVertex(pose, x, y, z).setColor((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, alpha.toInt().coerceIn(0, 255))
    }

    // ------------------------------------------------------------------ shooting stars

    private fun drawStar(pose: PoseStack.Pose, quads: VertexConsumer, star: SkyEffectsModule.Star, nowMs: Long, color: Int, trail: Float) {
        val progress = ((nowMs - star.bornMs) / star.lifeMs.toFloat()).coerceIn(0f, 1f)
        // Fades in quickly, out slowly: the streak is brightest early and trails away.
        val fade = sin(progress * PI.toFloat()).coerceAtLeast(0f)
        if (fade < 0.02f) return
        val travel = 46f * trail
        val head = progress * travel
        val tail = (progress - 0.4f).coerceAtLeast(0f) * travel
        val hx = star.startX + star.dirX * head
        val hy = star.startY + star.dirY * head
        val hz = star.startZ + star.dirZ * head
        val tx = star.startX + star.dirX * tail
        val ty = star.startY + star.dirY * tail
        val tz = star.startZ + star.dirZ * tail

        // A ribbon across the line of travel, turned to face the camera (the camera is the origin).
        var sx = star.dirY * hz - star.dirZ * hy
        var sy = star.dirZ * hx - star.dirX * hz
        var sz = star.dirX * hy - star.dirY * hx
        val length = sqrt(sx * sx + sy * sy + sz * sz)
        if (length < 1e-4f) return
        val width = 0.45f / length
        sx *= width; sy *= width; sz *= width

        val bright = (fade * 235f)
        // The glow behind the streak, wider and fainter, then the thin bright core on top of it.
        ribbon(pose, quads, tx, ty, tz, hx, hy, hz, sx * 2.2f, sy * 2.2f, sz * 2.2f, mix(color, 0x7FB8FF, 0.5f), bright * 0.35f)
        ribbon(pose, quads, tx, ty, tz, hx, hy, hz, sx, sy, sz, color, bright)
    }

    private fun ribbon(
        pose: PoseStack.Pose, quads: VertexConsumer,
        tx: Float, ty: Float, tz: Float, hx: Float, hy: Float, hz: Float,
        sx: Float, sy: Float, sz: Float, color: Int, headAlpha: Float
    ) {
        vertex(pose, quads, tx - sx, ty - sy, tz - sz, color, 0f)
        vertex(pose, quads, tx + sx, ty + sy, tz + sz, color, 0f)
        vertex(pose, quads, hx + sx, hy + sy, hz + sz, color, headAlpha)
        vertex(pose, quads, hx - sx, hy - sy, hz - sz, color, headAlpha)
    }
}
