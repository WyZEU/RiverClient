using System;
using System.IO;
using System.Threading;
using Windows.Foundation;
using Windows.Media.Control;
using Windows.Storage.Streams;

// Reads the current Windows media session and prints it as JSON. Optionally writes
// the album art (thumbnail) to the file path given as arg[0].
//
// Deliberately uses only pure WinRT types (IAsyncOperation.GetResults, DataReader):
// the usual .GetAwaiter()/.AsStreamForRead() helpers live in a facade that drags in
// the SDK union metadata, which isn't on stock machines. Polling the async status
// by hand keeps this compilable against just the system WinMetadata winmds.
class NowPlaying {
    static T Wait<T>(IAsyncOperation<T> op) {
        while (op.Status == AsyncStatus.Started) Thread.Sleep(8);
        return op.GetResults();
    }
    static T Wait<T, P>(IAsyncOperationWithProgress<T, P> op) {
        while (op.Status == AsyncStatus.Started) Thread.Sleep(8);
        return op.GetResults();
    }

    static string Esc(string v) {
        return (v ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
    }

    static int Main(string[] args) {
        try {
            var mgr = Wait(GlobalSystemMediaTransportControlsSessionManager.RequestAsync());
            var s = mgr.GetCurrentSession();
            if (s == null) { Console.WriteLine("{\"playing\":false}"); return 0; }

            // Control mode: "--do next|prev|playpause" performs a media action and exits.
            int ctl = Array.IndexOf(args, "--do");
            if (ctl >= 0 && ctl + 1 < args.Length) {
                var action = args[ctl + 1];
                if (action == "next") Wait(s.TrySkipNextAsync());
                else if (action == "prev") Wait(s.TrySkipPreviousAsync());
                else if (action == "playpause") Wait(s.TryTogglePlayPauseAsync());
                Console.WriteLine("{\"ok\":true}");
                return 0;
            }

            var props = Wait(s.TryGetMediaPropertiesAsync());
            var tl = s.GetTimelineProperties();
            var status = s.GetPlaybackInfo().PlaybackStatus.ToString();

            string artPath = "";
            if (props.Thumbnail != null && args.Length > 0) {
                try {
                    var stream = Wait(props.Thumbnail.OpenReadAsync());
                    uint size = (uint)stream.Size;
                    var reader = new DataReader(stream);
                    Wait(reader.LoadAsync(size));
                    var bytes = new byte[size];
                    reader.ReadBytes(bytes);
                    File.WriteAllBytes(args[0], bytes);
                    artPath = args[0];
                } catch { }
            }

            Console.WriteLine(
                "{\"playing\":" + ((status == "Playing") ? "true" : "false") +
                ",\"status\":\"" + Esc(status) + "\"" +
                ",\"title\":\"" + Esc(props.Title) + "\"" +
                ",\"artist\":\"" + Esc(props.Artist) + "\"" +
                ",\"album\":\"" + Esc(props.AlbumTitle) + "\"" +
                ",\"source\":\"" + Esc(s.SourceAppUserModelId) + "\"" +
                ",\"position\":" + ((long)tl.Position.TotalSeconds) +
                ",\"duration\":" + ((long)tl.EndTime.TotalSeconds) +
                ",\"artFile\":\"" + Esc(artPath) + "\"}"
            );
            return 0;
        } catch (Exception e) {
            Console.WriteLine("{\"playing\":false,\"error\":\"" + e.Message.Replace("\"", "'") + "\"}");
            return 0;
        }
    }
}
