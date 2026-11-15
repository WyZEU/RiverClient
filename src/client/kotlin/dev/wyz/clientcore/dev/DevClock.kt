package dev.wyz.clientcore.dev

/**
 * The clock the lyrics read. It is the real clock, except during a hidden recording run
 * (DevShot, -Driver.devshot.record), which steps it one video frame at a time so every
 * captured frame lands exactly 1/30 s after the last, however slow the capture is.
 */
object DevClock {
    @JvmStatic var fixedMs = 0L

    fun millis(): Long = if (fixedMs != 0L) fixedMs else System.currentTimeMillis()

    fun nanos(): Long = if (fixedMs != 0L) fixedMs * 1_000_000L else System.nanoTime()
}
