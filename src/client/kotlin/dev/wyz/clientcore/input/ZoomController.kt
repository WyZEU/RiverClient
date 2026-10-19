package dev.wyz.clientcore.input

/**
 * Zoom, after Zoomify: eases in and out instead of snapping, scrolls further in or back
 * out while held, and slows the mouse down in step with the zoom so aiming does not get
 * twitchier the further in you are.
 *
 * GameRendererMixin runs every frame's FOV through [apply]; MouseHandlerMixin asks
 * [onScroll] before the hotbar gets a scroll and scales mouse movement by
 * [sensitivityScale].
 */
object ZoomController {
    /** True while the zoom key is held (or toggled on). */
    @JvmStatic
    var zooming: Boolean = false
        set(value) {
            // A new zoom starts from the configured FOV, not wherever the last one was scrolled to.
            if (value && !field) scrolledFov = null
            field = value
        }

    /** The configured zoom FOV. */
    @JvmStatic
    var zoomFov: Double = 30.0

    @JvmStatic var smooth: Boolean = true
    @JvmStatic var scrollToZoom: Boolean = true
    @JvmStatic var reduceSensitivity: Boolean = true

    private const val TRANSITION_MS = 140.0
    private const val MIN_FOV = 4.0

    private var scrolledFov: Double? = null
    private var progress = 0.0
    private var lastFrameNanos = 0L
    /** Zoomed FOV over normal FOV as of the last frame, for the mouse. */
    private var lastRatio = 1.0

    private fun target(): Double = scrolledFov ?: zoomFov

    /** The FOV to render this frame, given the one vanilla worked out. */
    @JvmStatic
    fun apply(original: Double): Double {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1_000_000.0
        lastFrameNanos = now
        val goal = if (zooming) 1.0 else 0.0
        progress = if (!smooth) goal else {
            val step = (dt / TRANSITION_MS).coerceIn(0.0, 1.0)
            if (progress < goal) minOf(goal, progress + step) else maxOf(goal, progress - step)
        }
        if (progress <= 0.0) {
            lastRatio = 1.0
            return original
        }
        // Ease out on the way in, ease in on the way out: fast start, soft landing.
        val t = progress * progress * (3 - 2 * progress)
        val zoomed = minOf(original, target())
        val fov = original + (zoomed - original) * t
        lastRatio = if (original > 0) fov / original else 1.0
        return fov
    }

    /** True when the scroll was used for zooming and must not change the hotbar slot. */
    @JvmStatic
    fun onScroll(amount: Double): Boolean {
        if (!zooming || !scrollToZoom || amount == 0.0) return false
        val current = target()
        val next = if (amount > 0) current * 0.8 else current / 0.8
        scrolledFov = next.coerceIn(MIN_FOV, zoomFov)
        return true
    }

    /** Mouse movement multiplier while zoomed. */
    @JvmStatic
    fun sensitivityScale(): Double = if (reduceSensitivity) lastRatio.coerceIn(0.05, 1.0) else 1.0
}
