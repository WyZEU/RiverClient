# Reads the current Windows media session and prints it as one line of JSON.
#
# Uses the OS-level media transport controls rather than the Spotify Web API, so it
# needs no login and no API key. Prefers the Spotify session when one exists, and
# only falls back to whatever else is playing if Spotify is closed.
$ErrorActionPreference = "Stop"
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime

    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object {
            $_.Name -eq 'AsTask' -and
            $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1

    function Await($op, $t) {
        $m = $asTask.MakeGenericMethod($t)
        $task = $m.Invoke($null, @($op))
        if (-not $task.Wait(3000)) { throw "timed out" }
        $task.Result
    }

    $mgrT = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
    $mgr = Await ($mgrT::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

    # Prefer Spotify over whatever happens to hold the media session.
    #
    # GetCurrentSession() returns the most recent session, so opening a YouTube tab
    # takes the module over and the HUD starts showing a video title under a Spotify
    # logo. Look through every session for Spotify first, and only fall back to the
    # current one when Spotify is not running at all.
    $session = $null
    try {
        $sessions = $mgr.GetSessions()
        foreach ($candidate in $sessions) {
            if ([string]$candidate.SourceAppUserModelId -match 'Spotify') {
                $session = $candidate
                break
            }
        }
    } catch {
        # GetSessions can throw on older Windows builds; fall through to current.
    }
    if (-not $session) { $session = $mgr.GetCurrentSession() }

    if (-not $session) {
        Write-Output '{"playing":false}'
        exit 0
    }

    $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    $timeline = $session.GetTimelineProperties()
    $status = $session.GetPlaybackInfo().PlaybackStatus.ToString()

    $payload = [ordered]@{
        playing  = ($status -eq 'Playing')
        status   = $status
        title    = [string]$props.Title
        artist   = [string]$props.Artist
        album    = [string]$props.AlbumTitle
        source   = [string]$session.SourceAppUserModelId
        position = [int]$timeline.Position.TotalSeconds
        duration = [int]$timeline.EndTime.TotalSeconds
        art      = ""
        at       = [int64]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    }

    $payload | ConvertTo-Json -Compress
} catch {
    Write-Output '{"playing":false}'
    exit 0
}
