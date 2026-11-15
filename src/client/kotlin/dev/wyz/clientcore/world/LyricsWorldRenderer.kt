package dev.wyz.clientcore.world

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.wyz.clientcore.lyrics.LyricFonts
import dev.wyz.clientcore.lyrics.LyricScene
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.LyricsModule
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
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the words LyricsModule has put in the world. Each word is drawn several times with the
 * game's own font: a soft halo of faint light-coloured copies around it (Glow), a ring of dark
 * copies just behind its edges (Outline), sideways smears while it appears and fades (Motion
 * blur), split cyan and pink copies when it glitches (Flicker), then the word itself on top.
 * It springs up into place when sung and shrinks a little as it fades (Bounce).
 *
 * The version handling is the same as WaypointWorldRenderer's: only `render` is written per game
 * version, because what changed across versions is how a text draw is obtained, not what is
 * drawn. Each passes its way of drawing text into the one shared pass below.
 */
object LyricsWorldRenderer {

    private const val FULL_BRIGHT = 15728880
    /** Text below this alpha is skipped: Font bumps near-zero alpha back up to opaque. */
    private const val MIN_TEXT_ALPHA = 6
    private const val OUTLINE_COLOR = 0x070A14
    private const val GLITCH_CYAN = 0x5CE1FF
    private const val GLITCH_PINK = 0xFF51B8
    private const val KARAOKE_MS = 250f
    private const val GLITCH_BUCKET_MS = 70L
    /** A word sung longer than this counts as a held note. */
    private const val HELD_NOTE_MS = 350L

    /** Halo offsets in font pixels: eight directions close in, eight further out. */
    private val GLOW_OFFSETS: List<FloatArray> = (0 until 8).flatMap { i ->
        val a = i * PI / 4
        listOf(floatArrayOf((cos(a) * 0.6).toFloat(), (sin(a) * 0.6).toFloat()), floatArrayOf((cos(a) * 1.3).toFloat(), (sin(a) * 1.3).toFloat()))
    }
    private val RING: List<FloatArray> = (0 until 8).map { i ->
        val a = i * 2 * PI / 8
        floatArrayOf(cos(a).toFloat(), sin(a).toFloat())
    }

    private var registered = false

    private fun interface TextSink {
        fun draw(text: Component, x: Float, y: Float, color: Int, mode: Font.DisplayMode)
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

    /** The module, with this frame's words placed, or null when there is nothing to draw. */
    private fun module(client: Minecraft, now: Long): LyricsModule? {
        if (client.level == null || client.player == null) return null
        val module = ModuleRegistry.get<LyricsModule>("lyrics") ?: return null
        if (!module.active || !module.showWorld()) return null
        module.scene.frame(client, now, module.sceneConfig())
        return if (module.scene.pieces.isEmpty()) null else module
    }

//? if >=26.2 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val module = module(client, now) ?: return
        val cameraPos = client.gameRenderer.mainCamera().position()
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()
        emit(client, poseStack, cameraPos, module, now) { text, x, y, color, mode ->
            collector.submitText(poseStack, x, y, text.visualOrderText, false, mode, FULL_BRIGHT, color, 0, 0)
        }
    }
*///?} elif >=26.1 {
/*    private fun render(context: LevelRenderContext) {
        val client = Minecraft.getInstance()
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val module = module(client, now) ?: return
        val cameraPos = client.gameRenderer.mainCamera.position()
        val poseStack = context.poseStack()
        val collector = context.submitNodeCollector()
        val glowSource = LyricGlow.begin(module.glow(), module.outline())
        val glow = glowSource?.let { source ->
            TextSink { text, x, y, color, mode -> client.font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), source, mode, 0, FULL_BRIGHT) }
        }
        emit(client, poseStack, cameraPos, module, now, glow) { text, x, y, color, mode ->
            collector.submitText(poseStack, x, y, text.visualOrderText, false, mode, FULL_BRIGHT, color, 0, 0)
        }
        if (glowSource != null) LyricGlow.end()
    }
*///?} elif >=1.21.10 {
    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val module = module(client, now) ?: return
        val cameraPos = client.gameRenderer.mainCamera.position()
        val poseStack = context.matrices()
        val consumers = context.consumers()
        // The GPU glow (LyricGlow): the words also go into a layer of their own that is blurred later.
        val glowSource = LyricGlow.begin(module.glow(), module.outline())
        val glow = glowSource?.let { source ->
            TextSink { text, x, y, color, mode -> client.font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), source, mode, 0, FULL_BRIGHT) }
        }
        emit(client, poseStack, cameraPos, module, now, glow) { text, x, y, color, mode ->
            client.font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), consumers, mode, 0, FULL_BRIGHT)
        }
        if (glowSource != null) LyricGlow.end()
    }
//?} else {
/*    private fun render(context: WorldRenderContext) {
        val client = Minecraft.getInstance()
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val module = module(client, now) ?: return
        val cameraPos = client.gameRenderer.mainCamera.getPosition()
        // Both are @Nullable in this Fabric API version's interface even though the
        // event always supplies them; non-null since there is nothing to draw without them.
        val poseStack = context.matrixStack()!!
        val consumers = context.consumers()!!
        // The GPU glow from 1.21.6 (LyricGlow); before that it returns null and the drawn halo is used.
        val glowSource = LyricGlow.begin(module.glow(), module.outline())
        val glow = glowSource?.let { source ->
            TextSink { text, x, y, color, mode -> client.font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), source, mode, 0, FULL_BRIGHT) }
        }
        emit(client, poseStack, cameraPos, module, now, glow) { text, x, y, color, mode ->
            client.font.drawInBatch(text, x, y, color, false, poseStack.last().pose(), consumers, mode, 0, FULL_BRIGHT)
        }
        if (glowSource != null) LyricGlow.end()
    }
*///?}

    // ------------------------------------------------------------------ the shared pass

    private class Look(
        val face: Int, val stretch: Float, val unit: Float, val opacity: Float, val glow: Float, val outline: Float,
        val blur: Float, val bounce: Float, val flicker: Boolean, val text: Int, val glowColor: Int, val follow: Boolean,
        val walls: Boolean
    )

    private fun emit(client: Minecraft, poseStack: PoseStack, cameraPos: Vec3, module: LyricsModule, now: Long, glow: TextSink? = null, sink: TextSink) {
        val face = module.face()
        // The outline is strongest by day, where white words need it against a bright sky.
        val dark = ((client.level?.skyDarken ?: 0) / 11f).coerceIn(0f, 1f)
        val look = Look(
            face, LyricFonts.stretch(face), module.unit(), module.opacity(), module.glow(), module.outline() * (0.4f + 0.6f * (1f - dark)),
            module.blur(), module.bounce(), module.flicker(), module.textColor(), dev.wyz.clientcore.ui.RiverColor.resolve(module.glowColor()) and 0xFFFFFF, module.follow(), module.throughWalls()
        )
        // Furthest first, so a near word is drawn over a far one where they overlap.
        val ordered = module.scene.pieces.sortedByDescending { module.scene.worldPos(it, look.follow).distanceToSqr(cameraPos) }
        for (piece in ordered) drawPiece(client, poseStack, cameraPos, module.scene, piece, now, look, glow, sink)
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    private fun easeOutBack(t: Float, s: Float): Float {
        val u = t - 1f
        return 1f + (s + 1f) * u * u * u + s * u * u
    }

    private fun drawPiece(client: Minecraft, poseStack: PoseStack, cameraPos: Vec3, scene: LyricScene, piece: LyricScene.Piece, now: Long, look: Look, glow: TextSink?, sink: TextSink) {
        val start = piece.sungAt[0]
        if (now < start) return
        val appear = ((now - start) / LyricScene.APPEAR_MS.toFloat()).coerceIn(0f, 1f)
        val fade = if (now < piece.fadeAt) 0f else ((now - piece.fadeAt) / LyricScene.FADE_MS.toFloat()).coerceIn(0f, 1f)
        val alpha = easeOutCubic(appear) * (1f - fade) * look.opacity
        if (alpha < 0.02f) return

        val blur = look.blur * maxOf(1f - appear, fade)
        val pop = (1f - 0.6f * look.bounce * (1f - easeOutBack(appear, 2f))) * (1f - 0.15f * fade)
        val rise = -0.4 * look.bounce * (1f - easeOutCubic(appear))
        val drift = sin(now / 700.0 + piece.driftSeed) * 0.05 * look.bounce

        val pos = scene.worldPos(piece, look.follow)
        poseStack.pushPose()
        poseStack.translate((pos.x - cameraPos.x).toFloat(), (pos.y - cameraPos.y + rise + drift).toFloat(), (pos.z - cameraPos.z).toFloat())
//? if >=26.3 {
        /*poseStack.rotate(Axis.YP.rotationDegrees(-piece.yaw))
*///?} else {
        poseStack.mulPose(Axis.YP.rotationDegrees(-piece.yaw))
//?}
//? if >=26.3 {
        /*poseStack.rotate(Axis.XP.rotationDegrees(piece.pitch))
*///?} else {
        poseStack.mulPose(Axis.XP.rotationDegrees(piece.pitch))
//?}
//? if >=26.3 {
        /*poseStack.rotate(Axis.ZP.rotationDegrees(piece.roll))
*///?} else {
        poseStack.mulPose(Axis.ZP.rotationDegrees(piece.roll))
//?}
        val s = look.unit * piece.fit * pop
        poseStack.scale(-s * look.stretch, -s, s)

        // Lines light up word by word: the word being sung is bold and full white, the rest dimmer.
        val karaoke = piece.words.size > 1
        var current = -1
        if (karaoke) for (i in piece.words.indices) if (now >= piece.sungAt[i]) current = i
        val texts = Array(piece.words.size) { LyricFonts.styled(piece.words[it], look.face, it == current) }
        val font = client.font
        val space = font.width(LyricFonts.styled(" ", look.face, false)).toFloat()
        var total = -space
        for (t in texts) total += font.width(t) + space
        var x = -total / 2f
        val y = -font.lineHeight / 2f

        for (i in texts.indices) {
            val w = font.width(texts[i]).toFloat()
            var a = alpha
            var pulse = 1f
            // Rainbow text: every word on screen shares the colour of the moment, so they change together.
            val color = dev.wyz.clientcore.ui.RiverColor.resolve(look.text) and 0xFFFFFF
            // Lit while it is being sung (or is the last word sung); the rest of a line waits dimmer.
            val lit = now >= piece.sungAt[i] && (now < piece.heldUntil[i] || i == current)
            if (karaoke && !lit) {
                // Words still to come are faint; words already sung a little brighter.
                a *= if (now < piece.sungAt[i]) 0.45f else 0.7f
            } else if (karaoke) {
                val t = ((now - piece.sungAt[i]) / KARAOKE_MS).coerceIn(0f, 1f)
                if (t < 1f) pulse += 0.12f * look.bounce * sin(t * PI).toFloat()
            }
            // A held note: the word slowly swells and glows brighter until it is let go, then settles.
            val hold = held(piece, i, now)
            pulse *= 1f + 0.1f * look.bounce * hold
            poseStack.pushPose()
            poseStack.translate(x + w / 2f, 0f, 0f)
            poseStack.scale(pulse, pulse, 1f)
            drawWord(texts[i], -w / 2f, y, a, blur, color, glitch(now, piece.driftSeed + i * 1.7f, look.flicker), 1f + 0.8f * hold, look, glow, sink)
            poseStack.popPose()
            x += w + space
        }
        poseStack.popPose()
    }

    /** Glow, blur, glitch and outline copies, then the word on top. */
    /** 0 to 1: how far into a long held note word [i] is, easing back to 0 over 300 ms once it ends. */
    private fun held(piece: LyricScene.Piece, i: Int, now: Long): Float {
        val start = piece.sungAt[i]
        val end = piece.heldUntil[i]
        if (end - start < HELD_NOTE_MS || now < start) return 0f
        if (now < end) {
            val t = (now - start).toFloat() / (end - start)
            return t * t * (3f - 2f * t)
        }
        return (1f - (now - end) / 300f).coerceIn(0f, 1f)
    }

    private fun drawWord(text: Component, x0: Float, y: Float, a0: Float, blur: Float, color: Int, glitch: Float, glowBoost: Float, look: Look, glow: TextSink?, sink: TextSink) {
        var x = x0
        var a = a0
        val under = if (look.walls) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL
        val top = if (look.walls) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.POLYGON_OFFSET
        if (glitch > 0f) {
            x += (glitch - 0.55f) * 4f
            a *= 0.55f + 0.3f * glitch
        }
        if (glow != null) {
            // The shader makes the glow and the outline from this: the word in its glow colour,
            // in a layer of its own. A held note pushes it brighter.
            val ga = argb(a * 0.75f * glowBoost, look.glowColor)
            if (ga != 0) glow.draw(text, x, y, ga, under)
        } else if (look.glow > 0.02f) {
            val ga = argb(a * (0.1f + 0.05f * look.glow) * glowBoost, look.glowColor)
            val spread = (0.5f + 0.5f * look.glow) * (0.75f + 0.25f * glowBoost)
            if (ga != 0) for (o in GLOW_OFFSETS) sink.draw(text, x + o[0] * spread, y + o[1] * spread, ga, under)
        }
        if (blur > 0.01f) {
            for (k in 1..2) {
                val ba = argb(a * 0.3f / k, color)
                if (ba == 0) continue
                val dx = k * 1.6f * blur
                sink.draw(text, x - dx, y, ba, under)
                sink.draw(text, x + dx, y, ba, under)
            }
        }
        if (glitch > 0f) {
            val split = 1f + 2f * glitch
            val cyan = argb(a * 0.6f, GLITCH_CYAN)
            val pink = argb(a * 0.6f, GLITCH_PINK)
            if (cyan != 0) sink.draw(text, x - split, y, cyan, under)
            if (pink != 0) sink.draw(text, x + split, y, pink, under)
        }
        if (glow == null && look.outline > 0.02f) {
            val inner = argb(a * 0.34f * look.outline, OUTLINE_COLOR)
            val outer = argb(a * 0.16f * look.outline, OUTLINE_COLOR)
            for (o in RING) {
                if (inner != 0) sink.draw(text, x + o[0] * 0.55f, y + o[1] * 0.55f, inner, under)
                if (outer != 0) sink.draw(text, x + o[0] * 1.1f, y + o[1] * 1.1f, outer, under)
            }
        }
        val main = argb(a * (1f - 0.55f * blur), color)
        if (main != 0) sink.draw(text, x, y, main, top)
    }

    /** 0, or a strength from 0.15 to 1 in roughly one 70 ms slot in eight: the word glitches then. */
    private fun glitch(now: Long, seed: Float, on: Boolean): Float {
        if (!on) return 0f
        var h = (now / GLITCH_BUCKET_MS) * -0x61c8864680b583ebL xor (java.lang.Float.floatToIntBits(seed).toLong() * -0x3d4d51c2d82b14b1L)
        h = h xor (h ushr 29)
        h *= -0x40a7b892e31b1a47L
        h = h xor (h ushr 32)
        val u = (h and 0xFFFF) / 65536f
        return if (u < 0.12f) 0.15f + u / 0.12f * 0.85f else 0f
    }

    private fun argb(alpha: Float, rgb: Int): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return if (a < MIN_TEXT_ALPHA) 0 else (a shl 24) or (rgb and 0xFFFFFF)
    }
}
