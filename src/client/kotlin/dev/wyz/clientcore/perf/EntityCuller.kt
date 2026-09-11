package dev.wyz.clientcore.perf

import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.PerformanceModule
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB

/**
 * River-native entity occlusion culling (no external mods). Each client tick it
 * raycasts from the camera to a handful of points on every rendered entity's box;
 * if every ray is blocked by a full opaque block, the entity is marked occluded and
 * [dev.wyz.clientcore.mixin.EntityRenderDispatcherMixin] skips drawing it. This cuts
 * the model/entity render cost in bases, caves and crowded areas.
 *
 * Runs on the render thread (same thread as rendering), so world reads are safe and
 * no locking is needed. It errs toward rendering: an entity is only culled when ALL
 * sample rays are blocked, so it never pops entities you can actually see.
 */
object EntityCuller {

    private const val MIN_DISTANCE_SQ = 8.0 * 8.0
    private const val MAX_DISTANCE = 128.0
    private const val BUDGET = 160

    // Two reusable sets swapped each tick so the culler allocates nothing per tick.
    @Volatile
    private var current: HashSet<Int> = HashSet()
    private var building: HashSet<Int> = HashSet()
    private val pos = BlockPos.MutableBlockPos()

    fun isCulled(entityId: Int): Boolean = current.contains(entityId)

    fun update(client: Minecraft) {
        val level = client.level
        val perf = ModuleRegistry.get<PerformanceModule>("performance")
        if (level == null || perf == null || !perf.active || !perf.entityCullEnabled()) {
            if (current.isNotEmpty()) current = HashSet()
            PerfStats.recordCulling(0, 0)
            return
        }
//? if >=26.2 {
/*        val camera = client.gameRenderer.mainCamera()
*///?} else {
        val camera = client.gameRenderer.mainCamera
//?}
        if (!camera.isInitialized) return
//? if >=1.21.11 {
        val camPos = camera.position()
//?} else {
/*        val camPos = camera.getPosition()
*///?}
        val ox = camPos.x
        val oy = camPos.y
        val oz = camPos.z
//? if >=1.21.11 {
        val forward = camera.forwardVector()
        val cameraEntity = camera.entity()
//?} else {
/*        val forward = camera.lookVector
        val cameraEntity = camera.entity
*///?}

        val next = building
        next.clear()
        var budget = BUDGET
        var considered = 0

        for (entity in level.entitiesForRendering()) {
            if (entity === cameraEntity || entity.isCurrentlyGlowing) continue
            val box = entity.boundingBox
            val cx = (box.minX + box.maxX) * 0.5
            val cy = (box.minY + box.maxY) * 0.5
            val cz = (box.minZ + box.maxZ) * 0.5
            val dx = cx - ox
            val dy = cy - oy
            val dz = cz - oz
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < MIN_DISTANCE_SQ || distSq > MAX_DISTANCE * MAX_DISTANCE) continue
            // Behind the camera: vanilla frustum culling already drops it, don't waste rays.
            if (dx * forward.x() + dy * forward.y() + dz * forward.z() <= 0.0) continue

            considered++
            if (budget-- <= 0) {
                // Out of budget this tick: keep the previous decision so nothing flickers.
                if (current.contains(entity.id)) next.add(entity.id)
                continue
            }
            if (fullyOccluded(level, ox, oy, oz, box, pos)) next.add(entity.id)
        }
        // Swap: publish `next` as current, reuse the old current as next tick's buffer.
        val old = current
        current = next
        building = old
        PerfStats.recordCulling(considered, next.size)
    }

    private fun fullyOccluded(
        level: net.minecraft.client.multiplayer.ClientLevel,
        ox: Double, oy: Double, oz: Double,
        box: AABB,
        pos: BlockPos.MutableBlockPos
    ): Boolean {
        // Sample the center plus spread points; cull only if every ray is blocked.
        val cx = (box.minX + box.maxX) * 0.5
        val cz = (box.minZ + box.maxZ) * 0.5
        if (!Occlusion.rayBlocked(level, ox, oy, oz, cx, (box.minY + box.maxY) * 0.5, cz, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, cx, box.maxY - 0.05, cz, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, box.minX + 0.05, box.minY + 0.05, box.minZ + 0.05, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, box.maxX - 0.05, box.minY + 0.05, box.maxZ - 0.05, pos)) return false
        if (!Occlusion.rayBlocked(level, ox, oy, oz, box.minX + 0.05, box.maxY - 0.05, box.maxZ - 0.05, pos)) return false
        return true
    }
}
