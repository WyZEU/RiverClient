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
    private val CURTAINS_1 = listOf(CURTAINS[1])
    private val CURTAINS_2 = listOf(CURTAINS[0], CURTAINS[1])
    private fun curtainsFor(count: Int): List<Curtain> = when (count) {
        1 -> CURTAINS_1
        2 -> CURTAINS_2
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

    /** Bottom and top colour of a palette (see SkyEffectsModule.PALETTES) at point [i] along a curtain, into [out]. */
    private fun colors(module: SkyEffectsModule, i: Int, out: IntArray) {
        when (module.palette()) {
            0 -> { out[0] = 0x2EF08A; out[1] = 0x9B4DFF }
            1 -> { out[0] = 0x36D8E8; out[1] = 0x3F6BFF }
            2 -> { out[0] = 0xA855FF; out[1] = 0xFF4FA3 }
            3 -> { out[0] = 0xFF5C9E; out[1] = 0x8A4DFF }
            SkyEffectsModule.SHIFTING -> {
                // Drifts through green, teal and violet, so stretches of the sky differ and change.
                val a = 0.5f + 0.5f * W1.value(i)
                val b = 0.5f + 0.5f * W2.value(i)
                out[0] = mix(mix(0x2EF08A, 0x36D8E8, a), 0xA855FF, b * 0.7f)
                out[1] = mix(mix(0x9B4DFF, 0x3F6BFF, a), 0xFF4FA3, b * 0.6f)
            }
            else -> {
                // The same at both ends of the ribbon, so a rainbow custom colour has no seam.
                val around = W1.sin[i] * 0.15f
                out[0] = dev.wyz.clientcore.ui.RiverColor.resolve(module.customBottom(), around) and 0xFFFFFF
                out[1] = dev.wyz.clientcore.ui.RiverColor.resolve(module.customTop(), around + 0.3f) and 0xFFFFFF
            }
        }
    }

    /**
     * The same picture as before, for a fraction of the work. Every wave along a curtain is
     * sin(k * angle + drift), and sin(a + b) = sin a cos b + cos a sin b: the part that only
     * depends on where along the ribbon a point is (sin and cos of k * angle) is worked out once
     * into tables, and each frame only needs the sine and cosine of each wave's drift, a handful
     * of calls per curtain instead of about ten for every one of its 221 points. The rays'
     * power curve comes from a table too, the fixed palettes' colours are worked out once a frame,
     * and the arrays are kept between frames instead of being made again.
     */
    private class Wave(k: Int) {
        // The point's angle round the ribbon is (i * 2 / SEGMENTS - 1) * PI.
        val sin = FloatArray(SEGMENTS + 1) { i -> sin(k * (i * 2.0 / SEGMENTS - 1.0) * PI).toFloat() }
        val cos = FloatArray(SEGMENTS + 1) { i -> kotlin.math.cos(k * (i * 2.0 / SEGMENTS - 1.0) * PI).toFloat() }
        // This frame's drift, set by [at].
        private var sinPhase = 0f
        private var cosPhase = 1f

        fun at(phase: Double): Wave {
            sinPhase = sin(phase).toFloat()
            cosPhase = kotlin.math.cos(phase).toFloat()
            return this
        }

        /** sin(k * angle + phase) at point [i]. */
        fun value(i: Int): Float = sin[i] * cosPhase + cos[i] * sinPhase
    }

    private val W1 = Wave(1)
    private val W2 = Wave(2)
    private val W3 = Wave(3)
    private val W4 = Wave(4)
    private val W5 = Wave(5)
    private val W7 = Wave(7)
    private val W15 = Wave(15)
    private val W17 = Wave(17)
    private val W26 = Wave(26)

    /** x^0.7 for x in 0..1, in 1024 steps: the rays' curve without a pow per point. */
    private val POW07 = FloatArray(1025) { Math.pow(it / 1024.0, 0.7).toFloat() }

    private val xs = FloatArray(SEGMENTS + 1)
    private val zs = FloatArray(SEGMENTS + 1)
    private val ys = Array(ROW_AT.size) { FloatArray(SEGMENTS + 1) }
    private val cs = Array(ROW_AT.size) { IntArray(SEGMENTS + 1) }
    private val alphas = Array(ROW_AT.size) { FloatArray(SEGMENTS + 1) }
    private val pair = IntArray(2)

    private fun drawAurora(pose: PoseStack.Pose, quads: VertexConsumer, module: SkyEffectsModule, strength: Float, t: Double) {
        val rays = module.rays()
        val rows = ROW_AT.size
        val palette = module.palette()
        val fixed = palette in 0..3
        if (fixed) colors(module, 0, pair)
        // The shifting palette's two waves only drift with time, not per curtain.
        if (palette == SkyEffectsModule.SHIFTING) {
            W1.at(t * 0.05)
            W2.at(-t * 0.037 + 2.0)
        }

        for (curtain in curtainsFor(module.curtains())) {
            val p = curtain.phase.toDouble()
            val fold3 = W3.at(0.11 * t + p)
            val fold7 = W7.at(-0.23 * t + 2 * p)
            val fold17 = W17.at(0.5 * t)
            val sway = W4.at(0.15 * t + p)
            val patch = W5.at(-0.07 * t + p)
            val rayA = W26.at(1.1 * t + p)
            val rayB = W15.at(-0.7 * t + 2 * p)
            for (i in 0..SEGMENTS) {
                // Every wave below repeats a whole number of times around the circle, so the
                // ribbon's two ends meet without a seam.
                val s = i * 2f / SEGMENTS - 1f
                // Folds: a long sway, a tighter ripple and small kinks, all drifting at different speeds.
                val fold = 26f * fold3.value(i) + 10f * fold7.value(i) + 3.5f * fold17.value(i)
                if (curtain.overhead) {
                    xs[i] = s * 300f
                    zs[i] = -curtain.radius + fold
                } else {
                    val out = curtain.radius + fold
                    xs[i] = out * W1.sin[i]
                    zs[i] = -out * W1.cos[i]
                }
                val bottom = curtain.altitude + 7f * sway.value(i)

                // Slow brighter and dimmer stretches along the length, never fully out.
                val patches = 0.45f + 0.55f * (0.5f + 0.5f * patch.value(i))
                // Rays: thin vertical streaks that brighten, stretch and slide sideways.
                val ray = if (rays) {
                    val a = 0.5f + 0.5f * rayA.value(i)
                    val b = 0.5f + 0.5f * rayB.value(i)
                    POW07[((a * b).coerceIn(0f, 1f) * 1024f).toInt()]
                } else 0.5f
                val ends = if (curtain.overhead) smooth(1f, 0.7f, kotlin.math.abs(s)) else 1f
                val bright = strength * curtain.weight * ends * patches * (0.45f + 0.55f * ray) * PEAK_ALPHA
                val tall = curtain.height * (0.78f + 0.4f * ray)

                if (!fixed) colors(module, i, pair)
                val low = pair[0]
                val high = pair[1]
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
