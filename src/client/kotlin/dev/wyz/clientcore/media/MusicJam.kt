package dev.wyz.clientcore.media

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.wyz.clientcore.lyrics.LyricLine
import dev.wyz.clientcore.lyrics.LyricsService
import dev.wyz.clientcore.lyrics.LyricsState
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.LyricsModule
import dev.wyz.clientcore.module.impl.NowPlayingModule
import dev.wyz.clientcore.net.RiverSocial
import net.minecraft.client.Minecraft

/** Shares a song clock over River Friends. Nothing is shared before starting or joining a jam. */
object MusicJam {
    data class Song(val title: String = "", val artist: String = "", val album: String = "", val uri: String = "",
                    val durationMs: Long = 0, val positionMs: Long = 0, val playing: Boolean = false)
    data class Member(val uuid: String = "", val name: String = "")
    data class Room(val id: String = "", val generation: String = "", val host: String = "", val hostName: String = "",
                    val members: List<Member> = emptyList(), val track: Song = Song(), val lyrics: List<LyricLine>? = null,
                    val sequence: Long = 0, val updatedAt: Long = 0)
    data class Invite(val id: String = "", val generation: String = "", val hostName: String = "", val expiresAt: Long = 0)
    private val gson = Gson()
    @Volatile var room: Room? = null
        private set
    var invites: List<Invite> = emptyList()
        private set
    var message = ""
        private set
    var revision = 0
        private set
    var busy = false
        private set
    @Volatile private var timeline = JamTimeline()
    @Volatile private var lines: List<LyricLine> = emptyList()
    private var epoch = 0
    private var lastSent: Song? = null
    private var sentLyrics: List<LyricLine>? = null
    private var nextPublish = 0L
    private var lastPublish = 0L
    private var lastPacket = 0L
    private var nextSequence = 1L
    private var failures = 0
    private var preview = false
    private var requestedUri = ""
    private var uriSong = ""
    private var syncPlayback = false
    private var savedLyrics: Boolean? = null
    private var savedMusic: Boolean? = null
    val hosting: Boolean get() = room?.host == RiverSocial.selfUuid && room != null
    val following: Boolean get() = room != null && !hosting
    val playerMessage: String get() = if (syncPlayback) JamPlayer.message else "Lyrics only"
    private fun now() = System.nanoTime() / 1_000_000L
    private fun key(song: Song) = "${song.title}|${song.artist}|${song.durationMs}"

    fun localSong(): Song? {
        MediaSession.current()?.let { return Song(it.title, it.artist, it.album, it.trackUri, it.durationMs, it.positionMs, it.playing) }
        if (following) return null
        return ModuleRegistry.get<NowPlayingModule>("now_playing")?.playback()?.let {
            Song(it.title, it.artist, it.album, durationMs = it.durationSec * 1000L, positionMs = it.positionMs, playing = it.playing)
        }
    }

    fun playback(): MediaSession.Track? {
        val active = room ?: return null
        if (hosting || !timeline.fresh(now())) return null
        val song = active.track
        return MediaSession.Track(song.title, song.artist, song.album, song.durationMs, timeline.position(now()), song.playing, source = "River Music Jam", trackUri = song.uri)
    }

    fun lyrics(): List<LyricLine>? = if (following) lines else null

    private fun request(route: String, body: JsonObject = JsonObject(), done: (JsonObject) -> Unit = {}) {
        if (busy) return
        busy = true
        revision++
        val expected = epoch
        RiverSocial.jamRequest(route, body) { result ->
            if (expected != epoch) return@jamRequest
            busy = false
            revision++
            if (result == null || result.get("ok")?.asBoolean != true) {
                failures++
                message = result?.get("message")?.asString ?: "Music Jam is unavailable."
                nextPublish = now() + (5000L shl failures.coerceAtMost(4)).coerceAtMost(60_000)
                revision++
            } else {
                failures = 0
                message = result.get("message")?.asString.orEmpty()
                done(result)
            }
        }
    }

    fun refresh() {
        if (!RiverSocial.signedIn || preview) return
        request("/jam/state") { result ->
            invites = result.getAsJsonArray("invites")?.map { gson.fromJson(it, Invite::class.java) }.orEmpty()
            // Reconnect an already joined session; a restart never silently starts playback.
            if (room != null) accept(result, false)
            revision++
        }
    }

    fun start(link: String = "") {
        val song = localSong() ?: run { message = "No song is playing."; revision++; return }
        val uri = JamTimeline.spotifyUri(link) ?: JamTimeline.spotifyUri(song.uri).orEmpty()
        if (link.isNotBlank() && uri.isEmpty()) { message = "Enter a Spotify track link."; revision++; return }
        request("/jam/create", JsonObject().apply { add("track", gson.toJsonTree(song.copy(uri = uri))) }) {
            requestedUri = uri
            uriSong = key(song)
            nextSequence = 1
            lastSent = null
            sentLyrics = null
            nextPublish = 0
            accept(it, true)
        }
    }

    fun invite(uuid: String) {
        val active = room ?: return
        request("/jam/invite", identity(active).apply { addProperty("uuid", uuid) }) { revision++ }
    }

    fun setTrackLink(link: String) {
        if (!hosting) return
        val uri = JamTimeline.spotifyUri(link) ?: run { message = "Enter a Spotify track link."; revision++; return }
        val song = localSong() ?: return
        requestedUri = uri
        uriSong = key(song)
        lastSent = null
        nextPublish = 0
        message = "Track link updated."
        revision++
    }

    fun join(invite: Invite, play: Boolean) {
        request("/jam/join", JsonObject().apply { addProperty("id", invite.id); addProperty("generation", invite.generation) }) {
            syncPlayback = play
            accept(it, true)
            invites = invites.filter { other -> other.generation != invite.generation }
            revision++
        }
    }

    fun leave() {
        clear()
        RiverSocial.jamRequest("/jam/leave", JsonObject()) { }
    }

    fun clear() {
        epoch++
        busy = false
        preview = false
        room = null
        syncPlayback = false
        timeline = JamTimeline()
        lines = emptyList()
        JamPlayer.stop()
        ModuleRegistry.get<LyricsModule>("lyrics")?.let { module -> savedLyrics?.let { if (module.enabled) module.enabled = it } }
        ModuleRegistry.get<NowPlayingModule>("now_playing")?.let { module -> savedMusic?.let { if (module.enabled) module.enabled = it } }
        savedLyrics = null
        savedMusic = null
        revision++
    }

    fun receive(payload: JsonObject) {
        when (payload.get("type")?.asString) {
            "jam" -> accept(payload, false)
            "jam-invite" -> {
                val invite = gson.fromJson(payload.get("invite"), Invite::class.java) ?: return
                invites = (invites.filter { it.id != invite.id } + invite).takeLast(8)
                message = "${invite.hostName} invited a Music Jam."
                dev.wyz.clientcore.ui.FriendMessageToasts.musicInvite(invite.id, invite.hostName)
                revision++
            }
            "jam-ended" -> if (payload.get("id")?.asString == room?.id && payload.get("generation")?.asString == room?.generation) { clear(); message = "Music Jam ended." }
        }
    }

    private fun accept(payload: JsonObject, joining: Boolean) {
        val value = payload.get("room")?.takeUnless { it.isJsonNull } ?: run { if (room != null) clear(); return }
        val next = runCatching { gson.fromJson(value, Room::class.java) }.getOrNull() ?: return
        if (!joining && next.generation != room?.generation) return
        if (next.members.none { it.uuid == RiverSocial.selfUuid }) return
        if (joining) timeline = JamTimeline()
        if (!timeline.update(next.sequence, next.track.positionMs, next.track.durationMs, next.track.playing, now(),
                ((payload.get("serverNow")?.asLong ?: next.updatedAt) - next.updatedAt).coerceAtLeast(0))) return
        val changed = room?.let { key(it.track) != key(next.track) } != false
        if (changed) lines = emptyList()
        next.lyrics?.let { lines = it }
        val previousMembers = room?.members
        room = next
        lastPacket = now()
        nextSequence = maxOf(nextSequence, next.sequence + 1)
        if (joining) {
            ModuleRegistry.get<LyricsModule>("lyrics")?.let { if (savedLyrics == null) savedLyrics = it.enabled; it.enabled = true }
            ModuleRegistry.get<NowPlayingModule>("now_playing")?.let { if (savedMusic == null) savedMusic = it.enabled; it.enabled = true }
        }
        if (joining || changed || previousMembers != next.members) revision++
    }

    private fun identity(room: Room) = JsonObject().apply { addProperty("id", room.id); addProperty("generation", room.generation) }

    fun resetAccount() { clear(); invites = emptyList(); message = "" }

    fun preview(host: Boolean) {
        check(System.getProperty("river.devshot") != null)
        clear()
        preview = true
        val self = Member(RiverSocial.selfUuid, "RiverTest")
        val friend = Member("f-galaxy", "Galaxysdc")
        val song = Song("River demo", "River", uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC", durationMs = 180_000, positionMs = 4000, playing = true)
        val demo = Room("preview", "preview", if (host) self.uuid else friend.uuid, if (host) self.name else friend.name,
            listOf(self, friend), song, listOf(LyricLine(0, "A shared song"), LyricLine(5000, "A shared moment")), 1, System.currentTimeMillis())
        accept(JsonObject().apply { add("room", gson.toJsonTree(demo)); addProperty("serverNow", demo.updatedAt) }, true)
    }

    fun tick(client: Minecraft) {
        if (preview) return
        if (room == null) return
        if (!RiverSocial.signedIn || client.level == null) { leave(); return }
        if (!hosting) {
            if (now() - lastPacket > 90_000) { clear(); message = "Music Jam ended."; return }
            if (syncPlayback) playback()?.let { JamPlayer.sync(it) }
            return
        }
        val clock = now()
        if (busy || clock < nextPublish || clock - lastPublish < 1000) return
        val local = localSong() ?: room!!.track.copy(playing = false)
        val uri = JamPlayer.uriFor(local).ifEmpty { requestedUri.takeIf { key(local) == uriSong }.orEmpty() }
        val song = local.copy(uri = uri)
        val ready = (LyricsService.stateFor(song.title, song.artist, song.album, (song.durationMs / 1000).toInt()) as? LyricsState.Ready)?.lines
        val previous = lastSent
        val changed = previous == null || key(previous) != key(song) || previous.uri != song.uri || previous.playing != song.playing ||
            kotlin.math.abs(song.positionMs - previous.positionMs - if (previous.playing) clock - lastPublish else 0) > 1500
        if (!changed && ready === sentLyrics && clock - lastPublish < 10_000) return
        val body = identity(room!!).apply {
            addProperty("sequence", nextSequence++)
            add("track", gson.toJsonTree(song))
            if (ready !== sentLyrics || changed) {
                val text = gson.toJsonTree(ready.orEmpty().take(500))
                add("lyrics", if (text.toString().toByteArray(Charsets.UTF_8).size <= 64_000) text else gson.toJsonTree(emptyList<LyricLine>()))
            }
        }
        lastPublish = clock
        request("/jam/publish", body) {
            lastSent = song
            sentLyrics = ready
            nextPublish = 0
            accept(it, false)
        }
    }
}
