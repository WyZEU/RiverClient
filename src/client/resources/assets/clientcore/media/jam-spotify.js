// This helper is used only after starting or accepting a Music Jam.
function run(args) {
    var spotify = Application("com.spotify.client");
    if (args[0] === "read") {
        if (!spotify.running()) return "{}";
        var track = spotify.currentTrack;
        return JSON.stringify({ title: track.name(), artist: track.artist(), uri: track.id() });
    }
    var uri = args[1], position = Number(args[4]);
    if (args[0] !== "sync" || !/^spotify:track:[A-Za-z0-9]{22}$/.test(uri) || !isFinite(position) || position < 0 || position > 86400000) throw Error("Invalid song.");
    if (!spotify.running()) spotify.launch();
    if (spotify.currentTrack.id() !== uri) { spotify.playTrack(uri); return "waiting"; }
    if (spotify.currentTrack.name() !== args[2] || spotify.currentTrack.artist() !== args[3]) return "waiting";
    if (Math.abs(spotify.playerPosition() * 1000 - position) > 1500) spotify.playerPosition = position / 1000;
    if (args[5] === "true" && spotify.playerState() !== "playing") spotify.play();
    if (args[5] === "false" && spotify.playerState() === "playing") spotify.pause();
    return "ok";
}
