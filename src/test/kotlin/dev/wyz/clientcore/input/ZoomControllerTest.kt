package dev.wyz.clientcore.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZoomControllerTest {
    private var held = false
    private var clock = 1_000_000_000L

    @BeforeEach
    fun reset() {
        held = false
        ZoomController.zoomFov = 30.0
        ZoomController.smooth = true
        ZoomController.scrollToZoom = true
        ZoomController.reduceSensitivity = true
        ZoomController.toggle = false
        ZoomController.enabled = true
        ZoomController.keyDown = { held }
        // Let any zoom from an earlier test settle back out.
        repeat(400) { frame(70.0, 60) }
    }

    /** One frame at [fps]; returns the FOV it renders. */
    private fun frame(original: Double, fps: Int): Double {
        clock += 1_000_000_000L / fps
        return ZoomController.applyAt(original, clock)
    }

    private fun zoomIn(fps: Int, seconds: Double): List<Double> {
        held = true
        return List((fps * seconds).toInt()) { frame(70.0, fps) }
    }

    @Test
    fun zoomsInWithoutOvershootAndSettlesQuickly() {
        for (fps in listOf(30, 60, 144, 240)) {
            reset()
            val fovs = zoomIn(fps, 0.6)
            for (i in 1 until fovs.size) assertTrue(fovs[i] <= fovs[i - 1] + 1e-9, "FOV went back up at $fps fps")
            assertTrue(fovs.all { it >= 30.0 - 1e-6 }, "Overshot past the zoom at $fps fps")
            val settled = fovs.indexOfFirst { it < 30.5 } / fps.toDouble()
            assertTrue(settled in 0.05..0.3, "Took ${settled}s to settle at $fps fps")
        }
    }

    @Test
    fun releasingReturnsToNormal() {
        zoomIn(60, 0.5)
        held = false
        val out = List(60) { frame(70.0, 60) }
        assertEquals(70.0, out.last(), 1e-6)
        assertEquals(1.0, ZoomController.sensitivityScale(), 1e-9)
    }

    @Test
    fun scrollingGlidesInsteadOfJumping() {
        zoomIn(60, 0.5)
        val before = frame(70.0, 60)
        assertTrue(ZoomController.onScroll(1.0))
        val next = frame(70.0, 60)
        // One frame later it has moved toward 24 degrees, but nowhere near all the way.
        assertTrue(next < before && next > 27.0, "Scroll jumped from $before to $next")
        val later = List(60) { frame(70.0, 60) }.last()
        assertEquals(24.0, later, 0.1)
    }

    @Test
    fun mouseSlowsByThePictureSize() {
        zoomIn(60, 1.0)
        val expected = Math.tan(Math.toRadians(15.0)) / Math.tan(Math.toRadians(35.0))
        assertEquals(expected, ZoomController.sensitivityScale(), 1e-3)
    }

    @Test
    fun toggleZoomsOnPressAndOffOnTheNext() {
        ZoomController.toggle = true
        held = true; frame(70.0, 60)
        held = false; repeat(60) { frame(70.0, 60) }
        assertTrue(ZoomController.zooming)
        held = true; frame(70.0, 60)
        held = false; repeat(60) { frame(70.0, 60) }
        assertTrue(!ZoomController.zooming)
    }
}
