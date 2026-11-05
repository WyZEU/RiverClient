package dev.wyz.clientcore.lyrics

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The words of the song placed in the world. Every word has the moment it is sung and how long it
 * is held: the real timing when the lyrics have it (NetEase), otherwise a guess spread over the line by
 * syllables. In Words mode a word appears
 * on its own at that moment, scattered around the crosshair so consecutive words land in
 * different spots, tilted a little toward you, and fades a second or two later. In Lines mode the
 * whole line appears at once and the word being sung lights up.
 *
 * Pieces are fixed in the world where they were put (or carried along with you when Follow is
 * on); LyricsWorldRenderer draws them. Everything here runs on the render thread.
 */
class LyricScene {

    /** What the scene needs from the module's settings, read fresh each time. */
    class Config(
        val lines: Boolean,
        val center: Boolean,
        val uppercase: Boolean,
        val near: Float,
        val far: Float,
        val follow: Boolean,
        val face: Int,
        /** World size of one font pixel, before Wide's sideways stretch. */
        val unit: Float
    )

    /** One word (or line) in the world, with the wall-clock time each word is sung and held until. */
    class Piece(
        val words: Array<String>,
        val sungAt: LongArray,
        val heldUntil: LongArray,
        val x: Double, val y: Double, val z: Double,
        val anchor: Vec3,
        val yaw: Float, val pitch: Float, val roll: Float,
        val fit: Float,
        val halfWidth: Double, val halfHeight: Double,
        val driftSeed: Float
    ) {
        var fadeAt = Long.MAX_VALUE
    }

    private class Pending(val word: String, val sungAt: Long, val heldUntil: Long, val fadeAt: Long, val index: Int)

    companion object {
        const val APPEAR_MS = 280L
        const val FADE_MS = 550L
        private const val MAX_PIECES = 32
        private const val WORD_LEAD_MS = 80L
        private const val MAX_SYLLABLE_MS = 450.0
        private const val MIN_WORD_LIFE_MS = 1200L
        private const val MAX_WORD_LIFE_MS = 2400L
        /** How long a word with real timing stays after it stops being sung. */
        private const val HOLD_TAIL_MS = 700L
        private const val GLYPH_HEIGHT = 9f
        private const val VOWELS = "aeiouyáéíóúýěůäöüåøæàèìòùâêîôûãõ"

        /** A guess at when each word of a line is sung, for lyrics without word timing: the line's time shared out by syllables, each at most 450 ms. */
        fun wordTimes(words: Array<String>, start: Long, durationMs: Long): LongArray {
            val syl = IntArray(words.size) { syllables(words[it]) }
            val per = min(durationMs * 0.85 / max(1, syl.sum()), MAX_SYLLABLE_MS)
            var before = 0
            return LongArray(words.size) { i ->
                val t = start - WORD_LEAD_MS + Math.round(before * per)
                before += syl[i]
                t
            }
        }

        /** A rough syllable count: groups of vowels, a silent final e dropped, from 1 to 5. */
        fun syllables(word: String): Int {
            val lower = word.lowercase()
            var count = 0
            var letters = 0
            var inVowel = false
            for (c in lower) {
                if (c.isLetter()) letters++
                val vowel = VOWELS.indexOf(c) >= 0
                if (vowel && !inVowel) count++
                inVowel = vowel
            }
            if (count > 1 && lower.endsWith("e") && !lower.endsWith("le")) count--
            if (count == 0) count = max(1, letters / 3)
            return min(count, 5)
        }
    }

    private val rng = java.util.Random()
    val pieces = ArrayList<Piece>()
    private val pending = ArrayList<Pending>()
    private var followPos: Vec3? = null
    private var lastFollowNs = 0L

    fun clear() {
        pieces.clear()
        pending.clear()
    }

    /** Lets everything up there fade out (a new line, a pause, the song changed). */
    fun fadeAll(now: Long) {
        for (piece in pieces) piece.fadeAt = min(piece.fadeAt, now)
        pending.clear()
    }

    /** Where a piece is drawn this frame: where it was put, or carried along with the player. */
    fun worldPos(piece: Piece, follow: Boolean): Vec3 {
        val base = Vec3(piece.x, piece.y, piece.z)
        val f = followPos
        return if (follow && f != null) base.add(f.subtract(piece.anchor)) else base
    }

    // ------------------------------------------------------------------ a new line

    /**
     * The song moved on to a line. [lineStartMs] and [lineEndMs] are song times, [positionMs]
     * where the song is now, so the words can be put on the wall clock.
     */
    fun setLine(client: Minecraft, line: LyricLine, lineEndMs: Long, positionMs: Long, now: Long, config: Config) {
        // Scattered words fade on their own clock (a held word can run past the next line);
        // a whole line or a row of words gives way to the next one.
        if (config.lines || config.center) {
            fadeAll(now)
        } else {
            pending.clear()
            // A whole line or row left from before the mode changed has no clock of its own.
            for (piece in pieces) if (piece.fadeAt == Long.MAX_VALUE) piece.fadeAt = now
        }
        val player = client.player ?: return
        // Wall-clock time of the start of the song.
        val songStart = now - positionMs
        val timed = line.words
        val raw: List<String>
        val times: LongArray
        val ends: LongArray
        if (timed != null) {
            // Real timing: when each word is sung and how long it is held.
            raw = timed.map { it.text }
            times = LongArray(timed.size) { songStart + timed[it].startMs }
            ends = LongArray(timed.size) { times[it] + max(150L, timed[it].durationMs) }
        } else {
            raw = line.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            times = wordTimes(raw.toTypedArray(), songStart + line.timeMs, max(400L, lineEndMs - line.timeMs))
            ends = LongArray(raw.size) { if (it + 1 < raw.size) times[it + 1] else songStart + lineEndMs }
        }
        if (raw.isEmpty()) return
        val words = raw.map { if (config.uppercase) it.uppercase() else it }.toTypedArray()
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        val yaw = yawOf(look)
        val pitch = pitchOf(look)
        if (followPos == null || followPos!!.distanceToSqr(player.position()) > 1024.0) followPos = player.position()

        when {
            config.lines -> {
                val target = if (config.center) centerPos(eye, pitch, yaw, config) else scatter(client, eye, pitch, yaw, words, 0, times[0], 4000L, config, lineBase = true)
                addPiece(words, times, ends, inFront(client, eye, target), eye, yaw, tilt = !config.center, config = config, fitLimit = 1f)
            }
            !config.center -> {
                // Words scattered round the crosshair: each waits until it is sung (see frame).
                // Each stays up while it is held and a moment after, at least 1.2 s.
                val gap = if (words.size > 1) (times.last() - times.first()) / (words.size - 1) else 300L
                val life = (4 * gap).coerceIn(MIN_WORD_LIFE_MS, MAX_WORD_LIFE_MS)
                for (i in words.indices) {
                    val fadeAt = if (timed != null) max(times[i] + MIN_WORD_LIFE_MS, ends[i] + HOLD_TAIL_MS) else times[i] + life
                    if (fadeAt < now) continue
                    pending += Pending(words[i], times[i], ends[i], fadeAt, i)
                }
            }
            else -> {
                // Words in a row ahead, each appearing as it is sung, the whole row fitted to the view.
                val font = client.font
                val stretch = LyricFonts.stretch(config.face)
                val space = font.width(LyricFonts.styled(" ", config.face, false)).toFloat()
                val widths = words.map { font.width(LyricFonts.styled(it, config.face, false)).toFloat() }
                val total = widths.sum() + space * (words.size - 1)
                val center = inFront(client, eye, centerPos(eye, pitch, yaw, config))
                val right = Vec3.directionFromRotation(0f, yaw + 90f)
                var unit = config.unit * stretch
                val fit = min(1.0, 2.0 * center.distanceTo(eye) * tan(Math.toRadians(horizontalFov(client) * 0.38)) / max(total * unit.toDouble(), 0.001)).toFloat()
                unit *= fit
                var x = -total / 2f
                for (i in words.indices) {
                    val pos = center.add(right.scale(((x + widths[i] / 2f) * unit).toDouble()))
                    addPiece(arrayOf(words[i]), longArrayOf(times[i]), longArrayOf(ends[i]), pos, eye, yaw, tilt = false, config = config, fitLimit = fit)
                    x += widths[i] + space
                }
            }
        }
        while (pieces.size > MAX_PIECES) pieces.removeAt(0)
    }

    // ------------------------------------------------------------------ every frame

    /** Places words whose time has come, carries the follow point along and drops what has faded. */
    fun frame(client: Minecraft, now: Long, config: Config) {
        val player = client.player ?: return
        updateFollow(player.position())
        if (pending.isNotEmpty()) {
            val eye = player.getEyePosition(1f)
            val look = player.getViewVector(1f)
            val yaw = yawOf(look)
            val pitch = pitchOf(look)
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (now < p.sungAt - 60) continue
                it.remove()
                if (now > p.fadeAt) continue
                val target = scatter(client, eye, pitch, yaw, arrayOf(p.word), p.index, p.sungAt, p.fadeAt - p.sungAt, config, lineBase = false)
                val placed = inFront(client, eye, target)
                // Pulled in front of a wall: shrink it so it looks the size it would have been.
                val fit = min(1.0, placed.distanceTo(eye) / max(0.01, target.distanceTo(eye)) * 1.1).toFloat()
                addPiece(arrayOf(p.word), longArrayOf(p.sungAt), longArrayOf(p.heldUntil), placed, eye, yaw, tilt = true, config = config, fitLimit = fit).fadeAt = p.fadeAt
            }
            while (pieces.size > MAX_PIECES) pieces.removeAt(0)
        }
        val playerPos = player.position()
        pieces.removeAll { now - it.fadeAt > FADE_MS || worldPos(it, config.follow).distanceToSqr(playerPos) > 4096.0 }
    }

    private fun updateFollow(target: Vec3) {
        val nowNs = dev.wyz.clientcore.dev.DevClock.nanos()
        val f = followPos
        if (f == null || f.distanceToSqr(target) > 1024.0) {
            followPos = target
        } else {
            val dt = ((nowNs - lastFollowNs) / 1e9).coerceIn(0.0, 0.1)
            val k = 1.0 - Math.exp(-dt * 8.0)
            followPos = f.add(target.subtract(f).scale(k))
        }
        lastFollowNs = nowNs
    }

    // ------------------------------------------------------------------ placing

    private fun addPiece(words: Array<String>, times: LongArray, ends: LongArray, pos: Vec3, eye: Vec3, camYaw: Float, tilt: Boolean, config: Config, fitLimit: Float): Piece {
        val client = Minecraft.getInstance()
        val dist = pos.distanceTo(eye)
        val width = textWidth(client, words, config.face) * config.unit * LyricFonts.stretch(config.face).toDouble()
        val fit = min(fitLimit.toDouble(), 2.0 * dist * tan(Math.toRadians(horizontalFov(client) * 0.38)) / max(width, 0.001)).toFloat()
        val halfWidth = Math.toDegrees(atan(width * fit / (2.0 * dist))) + 2.0
        val halfHeight = Math.toDegrees(atan(GLYPH_HEIGHT * config.unit * fit / (2.0 * dist))) + 2.0

        val yaw: Float
        val pitch: Float
        val roll: Float
        if (tilt) {
            // Turned to face you; a single word is also knocked a little off square so it does not
            // look pasted on. A whole line stays square, or its far end smears away from you.
            val k = if (words.size > 1) 0f else 1f
            val off = pos.subtract(eye)
            yaw = Math.toDegrees(atan2(off.z, off.x)).toFloat() - 90f + (rng.nextFloat() - 0.5f) * 50f * k
            pitch = (-Math.toDegrees(atan2(off.y, hypot(off.x, off.z)))).toFloat()
            roll = (rng.nextFloat() - 0.5f) * 16f * k
        } else {
            yaw = camYaw
            pitch = 0f
            roll = 0f
        }
        val anchor = followPos ?: client.player?.position() ?: eye
        val piece = Piece(words, times, ends, pos.x, pos.y, pos.z, anchor, yaw, pitch, roll, fit, halfWidth, halfHeight, rng.nextFloat() * 100f)
        pieces += piece
        return piece
    }

    /**
     * A spot near the crosshair for word [index]: four columns across the view, alternately a bit
     * above and below centre, each tried at 28 jittered spots and kept where it overlaps the
     * other words least while staying close to its column.
     */
    private fun scatter(client: Minecraft, eye: Vec3, pitch: Float, yaw: Float, words: Array<String>, index: Int, sungAt: Long, life: Long, config: Config, lineBase: Boolean): Vec3 {
        val hFov = horizontalFov(client)
        val vFov = client.options.fov().get().toDouble()
        val spanH = min(hFov * 0.21, 18.0)
        val spanV = min(vFov * 0.17, 10.0)
        val baseH = if (lineBase) 0.0 else -spanH + (index % 4) * (2 * spanH / 3)
        val baseV = (if (lineBase) -0.2 else if (index % 2 == 0) 0.5 else -0.3) * spanV
        val near = config.near
        val far = max(near + 0.5f, config.far)
        val width = textWidth(client, words, config.face) * config.unit * LyricFonts.stretch(config.face).toDouble()

        var best: Vec3? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in 0 until 28) {
            val spread = 3.0 + i * 0.6
            val dh = (baseH + rng.nextGaussian() * spread).coerceIn(-spanH * 1.25, spanH * 1.25)
            val dv = (baseV + rng.nextGaussian() * spread * 0.6).coerceIn(-spanV * 1.25, spanV * 1.25)
            val dist = near + rng.nextDouble() * (far - near)
            val fit = min(1.0, 2.0 * dist * tan(Math.toRadians(hFov * 0.38)) / max(width, 0.001))
            val halfW = Math.toDegrees(atan(width * fit / (2.0 * dist))) + 2.0
            val halfH = Math.toDegrees(atan(GLYPH_HEIGHT * config.unit * fit / (2.0 * dist))) + 2.0
            val pos = aboveFloor(client, eye.add(Vec3.directionFromRotation((pitch + dv).toFloat(), (yaw + dh).toFloat()).scale(dist)))
            val gap = nearestGap(eye, pos, halfW, halfH, sungAt, sungAt + life, config.follow)
            val score = min(gap, 6.0) * 10.0 - hypot(dh - baseH, dv - baseV) * 0.3
            if (score > bestScore) {
                bestScore = score
                best = pos
            }
        }
        return best ?: eye.add(Vec3.directionFromRotation(pitch, yaw).scale(((near + far) / 2).toDouble()))
    }

    /** Degrees of clear sky between a spot and the nearest word on screen at the same time (6 when none). */
    private fun nearestGap(eye: Vec3, pos: Vec3, halfW: Double, halfH: Double, from: Long, until: Long, follow: Boolean): Double {
        val dir = pos.subtract(eye)
        val yawA = Math.toDegrees(atan2(dir.z, dir.x))
        val pitchA = Math.toDegrees(atan2(dir.y, hypot(dir.x, dir.z)))
        var gap = 6.0
        for (piece in pieces) {
            if (piece.fadeAt + FADE_MS < from || piece.sungAt[0] > until) continue
            val other = worldPos(piece, follow).subtract(eye)
            var dYaw = abs(Math.toDegrees(atan2(other.z, other.x)) - yawA)
            if (dYaw > 180) dYaw = 360 - dYaw
            val dPitch = abs(Math.toDegrees(atan2(other.y, hypot(other.x, other.z))) - pitchA)
            gap = min(gap, max(dYaw - (halfW + piece.halfWidth), dPitch - (halfH + piece.halfHeight)))
        }
        return gap
    }

    /** Straight ahead, a touch below the crosshair, halfway between the near and far distance. */
    private fun centerPos(eye: Vec3, pitch: Float, yaw: Float, config: Config): Vec3 =
        eye.add(Vec3.directionFromRotation((pitch + 4f).coerceIn(-40f, 40f), yaw).scale(((config.near + config.far) / 2).toDouble()))

    /** Pulls a spot in front of the first wall between the eye and it, so a word never sits inside a block. */
    private fun inFront(client: Minecraft, eye: Vec3, pos: Vec3): Vec3 {
        val level = client.level ?: return pos
        val delta = pos.subtract(eye)
        val length = delta.length()
        if (length < 0.01) return pos
        val step = delta.scale(0.2 / length)
        var at = eye
        var travelled = 0.0
        while (travelled < length) {
            at = at.add(step)
            travelled += 0.2
            val block = BlockPos.containing(at.x, at.y, at.z)
            if (!level.getBlockState(block).getCollisionShape(level, block).isEmpty) {
                return eye.add(delta.scale(max(1.0, travelled - 0.4) / length))
            }
        }
        return pos
    }

    /** Lifts a spot out of the ground it would be buried in. */
    private fun aboveFloor(client: Minecraft, pos: Vec3): Vec3 {
        val level = client.level ?: return pos
        var out = pos
        repeat(3) {
            val block = BlockPos.containing(out.x, out.y, out.z)
            if (level.getBlockState(block).getCollisionShape(level, block).isEmpty) return out
            out = out.add(0.0, 1.0, 0.0)
        }
        return out
    }

    private fun textWidth(client: Minecraft, words: Array<String>, face: Int): Int {
        val font = client.font
        val space = font.width(LyricFonts.styled(" ", face, false))
        return words.sumOf { font.width(LyricFonts.styled(it, face, false)) } + space * (words.size - 1)
    }

    private fun horizontalFov(client: Minecraft): Double {
        val v = Math.toRadians(client.options.fov().get().toDouble())
        val aspect = client.window.guiScaledWidth.toDouble() / max(1, client.window.guiScaledHeight)
        return Math.toDegrees(2.0 * atan(tan(v / 2.0) * aspect))
    }

    private fun yawOf(look: Vec3): Float = Math.toDegrees(atan2(-look.x, look.z)).toFloat()
    private fun pitchOf(look: Vec3): Float = Math.toDegrees(-asin(look.y.coerceIn(-1.0, 1.0))).toFloat()

}
