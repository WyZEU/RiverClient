# Mac music support

The Spotify module (the music HUD) and Lyrics read the active macOS Now Playing
session. Apple Music, Spotify and YouTube Music can supply that session. Enable
the desired modules in River and play a track in the music app. No River Spotify
login, developer tools, downloaded helper or extra permission is required.

Web players must publish their media session to macOS. A browser tab can play
audio without publishing it; in that case use a desktop player. YouTube Music
was tested with the desktop app distributed by
[Pear Desktop](https://github.com/pear-devs/pear-desktop). It is a third-party
player, not a required River dependency. Browser support depends on the browser
and its media-session settings.

The panel shows the system's active player. Previous, play/pause and next are
available while a game menu is open. Ads, live streams and free account limits
may restrict those controls. Track metadata and playback position also feed
Lyrics, independently of whether the music panel is enabled. Lyrics still need
a matching timed text from a lyrics provider; an instrumental or missing text
does not indicate a broken media reader.

Album art is optional. The reader accepts embedded image data and HTTPS artwork
URLs from the Apple, Spotify and YouTube image CDNs. Some players only expose an
opaque artwork identifier, so the panel retains its existing fallback image.
In particular, the tested YouTube Music desktop version exposed metadata and
playback controls but no usable cover data.

## Implementation

`MediaSession` starts one bundled `session-macos.js` process through the system's
`/usr/bin/osascript`. It queries MediaRemote every 500 ms, interpolates from the
player's timestamp and playback rate, and shares the result with both modules.
The framework is private and may change in a macOS update. Unsupported or stalled
readers fail without affecting the game; restart attempts are spaced by at least
15 seconds from the previous start.

The helper is stopped after ten seconds without a consumer and exits if its game
process disappears. An eight-second heartbeat watchdog kills a stuck helper;
snapshots older than three seconds are discarded. Commands contain the source
process identity and a short expiry so a queued click is discarded after a player
switch. Final command routing is handled by macOS, like a system media key.

Each game process has its own command and cover directory. Commands and covers
are written atomically. Cover downloads happen on artwork changes, outside the
render thread, with a two-second timeout and image size limits. There is no
network polling for playback state and no connection to a player account API.

## Verification

Run `node --test launcher/tests/macos-media.test.js` for timestamp, pause, seek,
duration, metadata, command expiry/source and artwork URL regressions.

For an actual player test, run this from the repository root while a song plays:

```sh
/usr/bin/osascript -l JavaScript src/client/resources/assets/clientcore/media/session-macos.js --once
```

Check the title, artist, position and `playing` state while pausing, seeking,
changing tracks, switching players and quitting the player. Then verify the
panel and timed lyrics inside a world, and the three controls in a game menu.
Check paused-track hiding, missing artwork and a song without lyrics as well.

Developer captures run hidden and muted. Use a disposable game directory with
`-Driver.devshot=world-media-live -Driver.bench.world=<test-world>` to capture
real playback after allowing time for the lyrics lookup. Add
`-Driver.devshot.createWorld=true` to create a missing flat test world.
`world-audit-<module-id>` captures a module after exercising its settings and
checking a profile save/load round trip. This smoke test does not invoke action
buttons or replace manual checks of keybinds, multiplayer and external services.
Use `-Driver.mixinAudit=true` when booting each supported Minecraft version.

Native runtime verification must be recorded with the macOS version, architecture,
player version and exact source commit. An ARM Mac run does not verify Intel
hardware, and compiling all Minecraft targets does not replace booting them.
