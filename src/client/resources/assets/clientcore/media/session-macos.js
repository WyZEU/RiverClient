/* macOS Now Playing, shared by the HUD and lyrics even without River Launcher. */
function mediaSnapshot(info, playing, source, now) {
    function text(key) { return typeof info[key] === "string" ? info[key].slice(0, 4096) : ""; }
    function number(key, fallback) { return typeof info[key] === "number" && isFinite(info[key]) ? info[key] : fallback; }
    var duration = Math.max(0, number("Duration", 0) * 1000);
    var rate = playing ? Math.max(0, Math.min(4, number("PlaybackRate", 1))) : 0;
    var stamp = number("Timestamp", now / 1000) * 1000;
    var elapsed = Math.max(0, number("ElapsedTime", 0) * 1000 + Math.max(0, now - stamp) * rate);
    return {
        title: text("Title"), artist: text("Artist"), album: text("Album"),
        durationMs: Math.round(duration), positionMs: Math.round(duration > 0 ? Math.min(duration, elapsed) : elapsed),
        playing: !!playing, rate: rate, source: source || "", at: now, artFile: ""
    };
}

function mediaCommand(command, snapshot, now) {
    if (!command || !snapshot.title || !snapshot.source || command.source !== snapshot.source) return null;
    if (typeof command.at !== "number" || !isFinite(command.at) || now - command.at > 3000 || command.at > now + 1000) return null;
    switch (command.action) {
        case "playpause": return 2;
        case "next": return 4;
        case "prev": return 5;
        default: return null;
    }
}

function artworkUrl(value) {
    return typeof value === "string" && /^https:\/\/(?:[a-z0-9-]+\.)*(?:mzstatic\.com|scdn\.co|spotifycdn\.com|ytimg\.com)\//i.test(value)
        ? value : "";
}

function run(args) {
    ObjC.import("Foundation");
    ObjC.import("AppKit");
    var framework = $.NSBundle.bundleWithPath("/System/Library/PrivateFrameworks/MediaRemote.framework");
    if (!framework || !framework.load) return JSON.stringify({ error: true });
    var request = $.NSClassFromString("MRNowPlayingRequest");
    if (!request || request.isNil()) return JSON.stringify({ error: true });
    ObjC.bindFunction("MRMediaRemoteSendCommand", ["bool", ["int", "id"]]);
    ObjC.bindFunction("MRMediaRemoteRegisterForNowPlayingNotifications", ["void", ["id"]]);
    $.MRMediaRemoteRegisterForNowPlayingNotifications($.NSOperationQueue.mainQueue.underlyingQueue);
    ObjC.bindFunction("kill", ["int", ["int", "int"]]);

    var streaming = args[0] === "--stream";
    var parent = Number(args[1]);
    var dir = args[2];
    if (streaming && (!Number.isInteger(parent) || parent <= 1 || !dir)) return JSON.stringify({ error: true });
    var output = $.NSFileHandle.fileHandleWithStandardOutput;
    var files = $.NSFileManager.defaultManager;
    var coverKey = "", coverFile = "", pendingArt = null;

    function present(value) { return value && !value.isNil(); }
    function value(dict, key) {
        var v = dict.objectForKey("kMRMediaRemoteNowPlayingInfo" + key);
        if (!present(v)) return null;
        if (v.isKindOfClass($.NSDate)) return v.timeIntervalSince1970;
        if (v.isKindOfClass($.NSString) || v.isKindOfClass($.NSNumber)) return ObjC.unwrap(v);
        return null;
    }
    function sample() {
        var fields = {}, source = "", item = request.localNowPlayingItem;
        var player = request.localNowPlayingPlayerPath;
        if (present(player) && present(player.client)) {
            var client = player.client;
            source = String(client.processIdentifier) + ":" + (present(client.bundleIdentifier) ? ObjC.unwrap(client.bundleIdentifier) : "");
        }
        pendingArt = null;
        if (present(item) && present(item.nowPlayingInfo)) {
            var info = item.nowPlayingInfo;
            ["Title", "Artist", "Album", "Duration", "ElapsedTime", "PlaybackRate", "Timestamp"].forEach(function(key) {
                fields[key] = value(info, key);
            });
            var data = info.objectForKey("kMRMediaRemoteNowPlayingInfoArtworkData");
            var key = source + "|" + fields.Title + "|" + fields.Artist + "|" + value(info, "ArtworkIdentifier") + "|" + (present(data) ? data.length : 0);
            if (key !== coverKey) {
                coverKey = key;
                coverFile = "";
                pendingArt = { data: data, url: artworkUrl(value(info, "ArtworkIdentifier")) };
            }
        } else {
            coverKey = "";
            coverFile = "";
        }
        var result = mediaSnapshot(fields, !!request.localIsPlaying, source, Date.now());
        result.artFile = coverFile;
        return result;
    }
    function saveArt(art) {
        if (!art) return;
        try {
            var data = art.data;
            if (!present(data) && art.url) {
                var req = $.NSURLRequest.requestWithURLCachePolicyTimeoutInterval($.NSURL.URLWithString(art.url), 0, 2);
                data = $.NSURLConnection.sendSynchronousRequestReturningResponseError(req, null, null);
            }
            if (!present(data) || data.length > 4 * 1024 * 1024) return;
            var bitmap = $.NSBitmapImageRep.imageRepWithData(data);
            if (!present(bitmap) || bitmap.pixelsWide > 2048 || bitmap.pixelsHigh > 2048) return;
            var png = bitmap.representationUsingTypeProperties(4, $({}));
            var path = dir + "/artwork.png";
            if (present(png) && png.writeToFileAtomically(path, true)) coverFile = path;
        } catch (_) { /* Artwork is optional; keep the playback clock running. */ }
    }
    function drain(snapshot) {
        var path = dir + "/command.json";
        if (!files.fileExistsAtPath(path)) return;
        try {
            var raw = $.NSString.stringWithContentsOfFileEncodingError(path, $.NSUTF8StringEncoding, null);
            files.removeItemAtPathError(path, null);
            if (!present(raw)) return;
            var code = mediaCommand(JSON.parse(ObjC.unwrap(raw)), snapshot, Date.now());
            if (code !== null) $.MRMediaRemoteSendCommand(code, $({}));
        } catch (_) { /* Discard invalid commands or unavailable system controls. */ }
    }
    if (!streaming) return JSON.stringify(sample());
    while ($.kill(parent, 0) === 0) {
        try {
            var snapshot = sample();
            drain(snapshot);
            output.writeData($(JSON.stringify(snapshot) + "\n").dataUsingEncoding($.NSUTF8StringEncoding));
            saveArt(pendingArt);
        } catch (_) {
            output.writeData($('{"error":true}\n').dataUsingEncoding($.NSUTF8StringEncoding));
            return;
        }
        $.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.5));
    }
}
