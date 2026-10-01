package dev.wyz.clientcore.perf

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.PerformanceModule
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/**
 * River-native block-entity occlusion culling: chests, signs, banners, skulls etc.
 * hidden behind blocks are skipped. Unlike entities there's no cheap way to enumerate
 * all block entities each tick, so this is evaluated lazily from the render gate
 * ([dev.wyz.clientcore.mixin.BlockEntityRenderDispatcherMixin]) and cached per block
 * position with a short TTL, so each visible block entity is only raycast a few times
 * a second. Runs on the render thread (safe world reads).
 */
object BlockEntityCuller {

    private const val MIN_DISTANCE_SQ = 6.0 * 6.0
    private const val TTL_MS = 300L
    private const val MAX_CACHE = 8192

    private class Entry(var culled: Boolean, var expires: Long)

    private val cache = HashMap<Long, Entry>()
    private val pos = BlockPos.MutableBlockPos()
    private var lastLevelHash = 0

    /** True if this block entity is currently occluded or beyond the render distance cap. */
    fun shouldCull(client: Minecraft, blockPos: BlockPos): Boolean {
        val perf = ModuleRegistry.get<PerformanceModule>("performance")
        if (perf == null || !perf.active) return false
        val level = client.level ?: return false

        // Drop the cache when the world changes so stale positions never leak.
        val levelHash = System.identityHashCode(level)
        if (levelHash != lastLevelHash) {
            lastLevelHash = levelHash
            cache.clear()
        }

//? if >=26.2 {
/*        val camera = client.gameRenderer.mainCamera()
*///?} else {
        val camera = client.gameRenderer.mainCamera
//?}
        if (!camera.isInitialized) return false
//? if >=1.21.11 {
        val camPos = camera.position()
//?} else {
/*        val camPos = camera.getPosition()
*///?}
        val cx = blockPos.x + 0.5
        val cy = blockPos.y + 0.5
        val cz = blockPos.z + 0.5
        val dx = cx - camPos.x
        val dy = cy - camPos.y
        val dz = cz - camPos.z
        val distSq = dx * dx + dy * dy + dz * dz

        // Distance cap: chests/signs/banners past this range skip rendering entirely.
        // Checked before occlusion since it needs no raycast at all.
        val maxDist = perf.blockEntityDistance()
        if (maxDist > 0 && distSq > maxDist.toDouble() * maxDist.toDouble()) return true

        if (!perf.blockEntityCullEnabled()) return false
        if (distSq < MIN_DISTANCE_SQ) return false

        val key = blockPos.asLong()
        val now = System.currentTimeMillis()
        val hit = cache[key]
        if (hit != null && hit.expires > now) return hit.culled

        val culled = occluded(level, camPos.x, camPos.y, camPos.z, blockPos)
        if (hit != null) {
            hit.culled = culled
            hit.expires = now + TTL_MS
        } else {
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[key] = Entry(culled, now + TTL_MS)
        }
        return culled
    }

    /** Cull only if every sampled ray into the block's 1x1x1 cell is blocked. */
    private fun occluded(level: net.minecraft.client.multiplayer.ClientLevel, ox: Double, oy: Double, oz: Double, p: BlockPos): Boolean {
        val x0 = p.x + 0.15
        val x1 = p.x + 0.85
        val y0 = p.y + 0.15
        val y1 = p.y + 0.85
        val z0 = p.z + 0.15
        val z1 = p.z + 0.85
        val cx = p.x + 0.5
        val cy = p.y + 0.5
        val cz = p.z + 0.5
        if (!Occlusion.rayBlocked(level, ox, oy, oz, cx, cy, cz, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, x0, y1, z0, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, x1, y1, z1, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, x0, y0, z1, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, x1, y0, z0, pos)) return false
        return true
    }
}
