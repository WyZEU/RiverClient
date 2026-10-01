package dev.wyz.clientcore.pvp

/**
 * Clicks per second from real mouse-press events (MouseHandlerMixin), not from sampling
 * the attack key once a tick: at 20 ticks a second, a press and release inside one tick
 * was never seen, so anything past about 10 CPS read low. Only clicks made in game count;
 * clicking around in a menu is not combat.
 */
object CpsTracker {
    private val left = ArrayDeque<Long>()
    private val right = ArrayDeque<Long>()

    @JvmStatic
    fun press(button: Int) {
        val now = System.currentTimeMillis()
        when (button) {
            0 -> left.addLast(now)
            1 -> right.addLast(now)
        }
    }

    private fun count(queue: ArrayDeque<Long>): Int {
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty() && now - queue.first() > 1000L) queue.removeFirst()
        return queue.size
    }

    fun left(): Int = count(left)
    fun right(): Int = count(right)
}
