package dev.wyz.clientcore.media

/** A host clock anchored to local monotonic time, independent of wall-clock changes. */
class JamTimeline {
    private var sequence = -1L
    private var position = 0L
    private var duration = 0L
    private var playing = false
    private var received = 0L

    @Synchronized
    fun update(sequence: Long, positionMs: Long, durationMs: Long, playing: Boolean, nowMs: Long, transitMs: Long = 0): Boolean {
        if (sequence < this.sequence || positionMs < 0 || durationMs < 0) return false
        this.sequence = sequence
        this.duration = durationMs
        this.playing = playing
        this.received = nowMs - transitMs.coerceIn(0, 90_000)
        this.position = positionMs
        return true
    }

    @Synchronized
    fun position(nowMs: Long): Long = (position + if (playing) (nowMs - received).coerceAtLeast(0) else 0)
        .coerceIn(0, if (duration > 0) duration else 86_400_000)

    @Synchronized
    fun fresh(nowMs: Long): Boolean = sequence >= 0 && nowMs - received <= 45_000

    companion object {
        fun spotifyUri(value: String): String? {
            val text = value.trim().substringBefore('?')
            val id = when {
                text.startsWith("spotify:track:") -> text.removePrefix("spotify:track:")
                text.startsWith("https://open.spotify.com/track/") -> text.removePrefix("https://open.spotify.com/track/")
                else -> return null
            }
            return if (id.matches(Regex("[A-Za-z0-9]{22}"))) "spotify:track:$id" else null
        }
    }
}
