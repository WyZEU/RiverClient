package dev.wyz.clientcore.input

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Zoom: eases in and out, scrolls further in or back out while held, and slows the mouse in
 * step with the zoom so aiming does not get twitchier the further in you are.
 *
 * Smoothness comes from three things:
 * - Motion is a critically damped spring, not a timed ramp: it starts softly, lands softly,
 *   never overshoots, and a press mid-way simply turns around from wherever it is.
 * - It moves in magnification, not degrees. Halving the FOV angle is not half the zoom; the
 *   picture grows with tan(fov / 2), so the spring runs on the log of that and the view grows
 *   at an even rate the whole way in.
 * - Scrolling sets a new target that the same kind of spring glides to, instead of jumping.
 *
 * The key is read here, every frame, rather than in the client tick, which only runs 20
 * times a second and so could start a zoom up to 50 ms late.
 *
 * GameRendererMixin runs every frame's FOV through [apply]; MouseHandlerMixin asks
 * [onScroll] before the hotbar gets a scroll and scales mouse movement by [sensitivityScale].
 * ClientKeybinds keeps the settings below current.
 */
object ZoomController {
    /** True while zoomed in: the key held, or toggled on. */
    @JvmStatic
    var zooming: Boolean = false
        set(value) {
            // A new zoom starts from the configured FOV, not wherever the last one was scrolled to.
            if (value && !field) {
                scrollTarget = zoomFov
                scrollLevel = levelOf(zoomFov)
                scrollVelocity = 0.0
            }
            field = value
        }

    /** The configured zoom FOV in degrees. */
    @JvmStatic var zoomFov: Double = 30.0
    @JvmStatic var smooth: Boolean = true
    @JvmStatic var scrollToZoom: Boolean = true
    @JvmStatic var reduceSensitivity: Boolean = true
    /** Press to toggle rather than hold. */
    @JvmStatic var toggle: Boolean = false
    /** Whether the Zoom module is on; off ends any zoom. */
    @JvmStatic var enabled: Boolean = false
    /** Reads the zoom key; false while a screen has the keyboard. Set by ClientKeybinds. */
    @JvmStatic var keyDown: () -> Boolean = { false }

    /** How quickly zooming in and out settles (rad/s): about a sixth of a second. */
    private const val ZOOM_SPEED = 34.0
    /** Scroll steps settle a little slower, so a flick of the wheel reads as one glide. */
    private const val SCROLL_SPEED = 22.0
    private const val MIN_FOV = 4.0
    private const val SCROLL_STEP = 0.8

    // How far in, 0 (normal) to 1 (fully zoomed), and how fast it is moving.
    private var progress = 0.0
    private var velocity = 0.0
    // The zoomed FOV as a level (ln of 1 / tan(fov / 2)), gliding to the scroll target.
    private var scrollTarget = 30.0
    private var scrollLevel = levelOf(30.0)
    private var scrollVelocity = 0.0

    private var lastFrameNanos = 0L
    private var keyWasDown = false
    /** Normal over zoomed picture size as of the last frame (1 when not zoomed), for the mouse. */
    private var lastRatio = 1.0

    private fun levelOf(fovDegrees: Double): Double = -ln(tan(Math.toRadians(fovDegrees) / 2.0))

    /** The FOV to render this frame, given the one vanilla worked out. */
    @JvmStatic
    fun apply(original: Double): Double = applyAt(original, System.nanoTime())

    /** [apply] at a given time, so the motion can be tested frame by frame. */
    fun applyAt(original: Double, now: Long): Double {
        // Seconds since the last frame. GameRenderer may ask more than once a frame; the
        // repeat calls see almost no time pass and change nothing.
        val dt = if (lastFrameNanos == 0L) 0.0 else ((now - lastFrameNanos) / 1_000_000_000.0).coerceIn(0.0, 0.25)
        lastFrameNanos = now
        readKey()

        val goal = if (zooming) 1.0 else 0.0
        if (!smooth) {
            progress = goal; velocity = 0.0
            scrollLevel = levelOf(scrollTarget); scrollVelocity = 0.0
        } else if (dt > 0.0) {
            spring(progress, velocity, goal, ZOOM_SPEED, dt).let { progress = it.first; velocity = it.second }
            spring(scrollLevel, scrollVelocity, levelOf(scrollTarget), SCROLL_SPEED, dt).let { scrollLevel = it.first; scrollVelocity = it.second }
        }
        if (!zooming && progress < 1e-4 && abs(velocity) < 1e-3) {
            progress = 0.0; velocity = 0.0
            lastRatio = 1.0
            return original
        }
        // Between the normal view and the zoomed one, in picture size rather than degrees.
        val normal = levelOf(original)
        val zoomed = maxOf(normal, scrollLevel)
        val level = normal + (zoomed - normal) * progress.coerceIn(0.0, 1.0)
        lastRatio = exp(normal - level)
        return Math.toDegrees(2.0 * atan(exp(-level)))
    }

    /**
     * One step of a critically damped spring pulling [x] toward [target], solved exactly for
     * the time that passed, so it is just as smooth at 30 fps as at 300 and cannot blow up.
     */
    private fun spring(x: Double, v: Double, target: Double, omega: Double, dt: Double): Pair<Double, Double> {
        val e = x - target
        val b = v + omega * e
        val decay = exp(-omega * dt)
        return Pair(target + (e + b * dt) * decay, (v - omega * b * dt) * decay)
    }

    private fun readKey() {
        if (!enabled) {
            zooming = false
            keyWasDown = false
            return
        }
        val down = keyDown()
        if (toggle) {
            if (down && !keyWasDown) zooming = !zooming
        } else {
            zooming = down
        }
        keyWasDown = down
    }

    /** True when the scroll was used for zooming and must not change the hotbar slot. */
    @JvmStatic
    fun onScroll(amount: Double): Boolean {
        if (!zooming || !scrollToZoom || amount == 0.0) return false
        val next = if (amount > 0) scrollTarget * SCROLL_STEP else scrollTarget / SCROLL_STEP
        scrollTarget = next.coerceIn(MIN_FOV, zoomFov)
        return true
    }

    /** Mouse movement multiplier while zoomed: the picture is this much bigger, so turn this much less. */
    @JvmStatic
    fun sensitivityScale(): Double = if (reduceSensitivity) lastRatio.coerceIn(0.03, 1.0) else 1.0
}
