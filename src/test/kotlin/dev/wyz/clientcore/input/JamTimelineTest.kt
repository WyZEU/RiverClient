package dev.wyz.clientcore.input

import dev.wyz.clientcore.media.JamTimeline
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JamTimelineTest {
    @Test fun `remote clock plays pauses seeks and rejects out of order updates`() {
        val clock = JamTimeline()
        assertTrue(clock.update(1, 1000, 10_000, true, 50_000, 100))
        assertEquals(3100, clock.position(52_000))
        assertTrue(clock.update(2, 3000, 10_000, false, 52_000, 100))
        assertEquals(3000, clock.position(54_000))
        assertFalse(clock.update(1, 9000, 10_000, true, 54_000))
        assertEquals(3000, clock.position(54_000))
        assertTrue(clock.update(3, 500, 10_000, true, 54_000))
        assertEquals(1500, clock.position(55_000))
        assertEquals(10_000, clock.position(70_000))
        assertFalse(clock.fresh(100_000))
    }
    @Test fun `only exact Spotify track links can open a player`() {
        val id = "4uLU6hMCjMI75M1A2tKUQC"
        assertEquals("spotify:track:$id", JamTimeline.spotifyUri("https://open.spotify.com/track/$id?si=ignored"))
        for (value in listOf("file:///tmp/song", "spotify:playlist:$id", "https://open.spotify.com.evil/track/$id", "spotify:track:../../evil", "spotify:track:$id;open")) {
            assertNull(JamTimeline.spotifyUri(value))
        }
    }
}
