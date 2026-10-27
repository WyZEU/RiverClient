package dev.wyz.clientcore.perf

/**
 * Live counters River populates so the Perf Stats HUD can show real numbers
 * (no fabricated benchmarks). Everything is read/written on the render thread.
 */
object PerfStats {

    /** Smoothed time River spends drawing its own HUD each frame, in nanoseconds. */
    @Volatile
    var hudRenderNanos: Long = 0L
        private set

    /** Entities River considered for culling last tick, and how many it hid. */
    @Volatile
    var entitiesConsidered: Int = 0

    @Volatile
    var entitiesCulled: Int = 0

    private var hudSmoothed = 0.0

    fun recordHudRender(nanos: Long) {
        hudSmoothed = if (hudSmoothed == 0.0) nanos.toDouble() else hudSmoothed * 0.9 + nanos * 0.1
        hudRenderNanos = hudSmoothed.toLong()
    }

    fun recordCulling(considered: Int, culled: Int) {
        entitiesConsidered = considered
        entitiesCulled = culled
    }
}
