package dev.wyz.clientcore.cosmetic

import dev.wyz.clientcore.nametag.EffectRoster
import net.minecraft.client.Minecraft
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cloth capes, a River Plus perk: the cape is a small grid of points that hangs under
 * gravity, streams back when you run, lags when you turn and lifts when you fall, instead of
 * vanilla's single rigid plate.
 *
 * This is only the physics. It works in the cape's own space, the way vanilla's cape model is
 * laid out: pixels, x across the back, y down from the shoulders, z away from the back. The
 * top row is pinned where the vanilla cape hangs from. CapeClothMixin draws the result.
 * One simulation per entity, dropped once it has not been drawn for a few seconds.
 */
object ClothCape {

    const val COLS = 7
    const val ROWS = 9
    const val WIDTH = 10f
    const val HEIGHT = 16f

    /** Mid-plane of the cape at the shoulders: vanilla's cape plate spans z 2..3. */
    private const val BACK = 2.5f
    private const val STEP = 1f / 120f
    private const val MAX_STEPS = 12
    private const val GRAVITY = 140f // px/s^2
    private const val DRAG = 2.2f // how hard the air you move through pushes the cloth, 1/s
    private const val DAMPING = 0.985f
    private const val ITERATIONS = 6
    private const val CROUCH_TILT = 0.5f // the body's lean when crouching, as in HumanoidModel
    private const val TURN_RADIUS = 8f // px, how far from the spine the cape swings when you turn
    private const val STALE_NANOS = 5_000_000_000L

    private val COL_GAP = WIDTH / (COLS - 1)
    private val ROW_GAP = HEIGHT / (ROWS - 1)
    private val DIAG = sqrt(COL_GAP * COL_GAP + ROW_GAP * ROW_GAP)

    class Sim {
        val x = FloatArray(COLS * ROWS)
        val y = FloatArray(COLS * ROWS)
        val z = FloatArray(COLS * ROWS)
        internal val px = FloatArray(COLS * ROWS)
        internal val py = FloatArray(COLS * ROWS)
        internal val pz = FloatArray(COLS * ROWS)
        internal var started = false
        internal var lastNanos = 0L
        internal var lastX = 0.0
        internal var lastY = 0.0
        internal var lastZ = 0.0
        internal var lastYaw = 0f
        internal var carry = 0f
        internal var clock = 0f
    }

    private val sims = HashMap<Int, Sim>()
    private var sweepAt = 0L

    /** Whether this entity's cape should be cloth: River Plus players who have it switched on. */
    @JvmStatic
    fun isCloth(entityId: Int): Boolean {
        val mc = Minecraft.getInstance()
        val entity = mc.level?.getEntity(entityId) ?: return false
        if (entity.uuid == mc.player?.uuid) {
            val cape = dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.impl.CapeModule>("cape")
            return RiverPlus.active() && cape?.cloth() != false
        }
        return EffectRoster.hasRemotePlus(entity.uuid) && EffectRoster.hasRemoteCloth(entity.uuid)
    }

    /**
     * Advances this entity's cloth to now and returns it. [worldX]/[worldY]/[worldZ] is where
     * the entity is drawn this frame, [bodyYaw] its body rotation in degrees.
     */
    @JvmStatic
    fun update(entityId: Int, worldX: Double, worldY: Double, worldZ: Double, bodyYaw: Float, crouching: Boolean): Sim {
        val now = System.nanoTime()
        if (now - sweepAt > STALE_NANOS) {
            sweepAt = now
            sims.values.removeIf { now - it.lastNanos > STALE_NANOS }
        }
        val sim = sims.getOrPut(entityId) { Sim() }
        if (!sim.started || now - sim.lastNanos > 500_000_000L) {
            reset(sim)
            sim.started = true
            sim.lastNanos = now
            sim.lastX = worldX; sim.lastY = worldY; sim.lastZ = worldZ
            sim.lastYaw = bodyYaw
            return sim
        }

        val dt = ((now - sim.lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        sim.lastNanos = now
        if (dt <= 0f) return sim

        // How the body is moving, in the cape's frame, in px/s. A jump of several blocks in one
        // frame is a teleport, not speed.
        var vx = 0f
        var vy = 0f
        var vz = 0f
        val dx = worldX - sim.lastX
        val dy = worldY - sim.lastY
        val dz = worldZ - sim.lastZ
        if (dx * dx + dy * dy + dz * dz < 16.0) {
            val yaw = Math.toRadians(bodyYaw.toDouble())
            val left = (dx * cos(yaw) + dz * sin(yaw)) / dt
            val forward = (-dx * sin(yaw) + dz * cos(yaw)) / dt
            vx = (left * 16).toFloat()
            vy = (-dy / dt * 16).toFloat().coerceIn(-60f, 60f)
            vz = (-forward * 16).toFloat()
            val turn = Math.toRadians(wrapDegrees(bodyYaw - sim.lastYaw).toDouble()).toFloat() / dt
            vx += turn * TURN_RADIUS
        }
        sim.lastX = worldX; sim.lastY = worldY; sim.lastZ = worldZ
        sim.lastYaw = bodyYaw
        val speed = min(sqrt(vx * vx + vz * vz), 160f)
        val scale = if (speed > 0f) min(1f, 160f / sqrt(vx * vx + vz * vz)) else 1f
        vx *= scale; vz *= scale

        // Gravity in the body's frame: crouching leans the body forward, so "down" tips toward it.
        val gy = if (crouching) GRAVITY * cos(CROUCH_TILT) else GRAVITY
        val gz = if (crouching) -GRAVITY * sin(CROUCH_TILT) else 0f

        sim.carry += dt
        var steps = 0
        while (sim.carry >= STEP && steps < MAX_STEPS) {
            sim.carry -= STEP
            steps++
            sim.clock += STEP
            step(sim, -DRAG * vx, gy - DRAG * vy, gz - DRAG * vz, speed)
        }
        if (steps == MAX_STEPS) sim.carry = 0f
        return sim
    }

    private fun step(sim: Sim, ax: Float, ay: Float, az: Float, speed: Float) {
        val h2 = STEP * STEP
        // A little flutter while moving, more at the free bottom edge than at the shoulders.
        val flutter = speed * 0.9f
        for (r in 1 until ROWS) {
            val free = r.toFloat() / (ROWS - 1)
            for (c in 0 until COLS) {
                val i = r * COLS + c
                val wave = sin(sim.clock * 11f + r * 0.9f + c * 0.45f) * flutter * free
                val nx = sim.x[i] + (sim.x[i] - sim.px[i]) * DAMPING + (ax + wave * 0.35f) * h2
                val ny = sim.y[i] + (sim.y[i] - sim.py[i]) * DAMPING + ay * h2
                val nz = sim.z[i] + (sim.z[i] - sim.pz[i]) * DAMPING + (az + wave) * h2
                sim.px[i] = sim.x[i]; sim.py[i] = sim.y[i]; sim.pz[i] = sim.z[i]
                sim.x[i] = nx; sim.y[i] = ny; sim.z[i] = nz
            }
        }
        repeat(ITERATIONS) {
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val i = r * COLS + c
                    if (c + 1 < COLS) link(sim, i, i + 1, COL_GAP)
                    if (r + 1 < ROWS) link(sim, i, i + COLS, ROW_GAP)
                    if (c + 1 < COLS && r + 1 < ROWS) {
                        link(sim, i, i + COLS + 1, DIAG)
                        link(sim, i + 1, i + COLS, DIAG)
                    }
                    if (r + 2 < ROWS) link(sim, i, i + 2 * COLS, ROW_GAP * 2) // keeps it from crumpling
                }
            }
            for (c in 0 until COLS) pin(sim, c)
            // The back and legs: the cloth stays behind them, and never folds up over the head.
            for (i in COLS until COLS * ROWS) {
                if (sim.z[i] < BACK) sim.z[i] = BACK
                if (sim.y[i] < -2f) sim.y[i] = -2f
            }
        }
    }

    private fun link(sim: Sim, a: Int, b: Int, rest: Float) {
        val dx = sim.x[b] - sim.x[a]
        val dy = sim.y[b] - sim.y[a]
        val dz = sim.z[b] - sim.z[a]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-5f) return
        val diff = (len - rest) / len
        val pinnedA = a < COLS
        val pinnedB = b < COLS
        if (pinnedA && pinnedB) return
        val wa = if (pinnedA) 0f else if (pinnedB) 1f else 0.5f
        val wb = if (pinnedB) 0f else if (pinnedA) 1f else 0.5f
        sim.x[a] += dx * diff * wa; sim.y[a] += dy * diff * wa; sim.z[a] += dz * diff * wa
        sim.x[b] -= dx * diff * wb; sim.y[b] -= dy * diff * wb; sim.z[b] -= dz * diff * wb
    }

    private fun pin(sim: Sim, c: Int) {
        sim.x[c] = -WIDTH / 2 + c * COL_GAP
        sim.y[c] = 0f
        sim.z[c] = BACK
    }

    private fun reset(sim: Sim) {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val i = r * COLS + c
                sim.x[i] = -WIDTH / 2 + c * COL_GAP
                sim.y[i] = r * ROW_GAP
                sim.z[i] = BACK + r * ROW_GAP * 0.1f // vanilla's resting 6 degree tilt
                sim.px[i] = sim.x[i]; sim.py[i] = sim.y[i]; sim.pz[i] = sim.z[i]
            }
        }
        sim.carry = 0f
    }

    private fun wrapDegrees(d: Float): Float {
        var v = d % 360f
        if (v >= 180f) v -= 360f
        if (v < -180f) v += 360f
        return if (abs(v) > 90f) 0f else v // a snap, not a turn
    }
}
