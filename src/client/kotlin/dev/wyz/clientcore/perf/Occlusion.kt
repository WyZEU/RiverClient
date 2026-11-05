package dev.wyz.clientcore.perf

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared voxel-ray occlusion test used by the entity and block-entity cullers.
 * Runs on the render thread (safe world reads), no allocation in the inner loop.
 */
object Occlusion {
    private const val MAX_STEPS = 160

    /** True if a full opaque block sits between the origin and the target point. */
    fun rayBlocked(
        level: ClientLevel,
        ox: Double, oy: Double, oz: Double,
        tx: Double, ty: Double, tz: Double,
        pos: BlockPos.MutableBlockPos
    ): Boolean {
        val dx = tx - ox
        val dy = ty - oy
        val dz = tz - oz
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1.0) return false
        val steps = min((dist * 2.0).toInt(), MAX_STEPS)
        if (steps <= 1) return false
        val inv = 1.0 / steps
        val targetBx = floor(tx).toInt()
        val targetBy = floor(ty).toInt()
        val targetBz = floor(tz).toInt()
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        var lastZ = Int.MIN_VALUE
        var i = 1
        while (i < steps) {
            val t = i * inv
            val bx = floor(ox + dx * t).toInt()
            val by = floor(oy + dy * t).toInt()
            val bz = floor(oz + dz * t).toInt()
            i++
            if (bx == lastX && by == lastY && bz == lastZ) continue
            lastX = bx; lastY = by; lastZ = bz
            if (bx == targetBx && by == targetBy && bz == targetBz) continue
            pos.set(bx, by, bz)
//? if >=1.21.2 {
            if (level.getBlockState(pos).isSolidRender) return true
//?} else {
/*            if (level.getBlockState(pos).isSolidRender(level, pos)) return true
*///?}
        }
        return false
    }
}
