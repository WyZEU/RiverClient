# River's in-game media reader. Prints the Windows media session (Spotify first) as one
# JSON line every 400 ms, with the playback position in milliseconds worked out from the
# session's last update so synced lyrics line up. Also runs media keys the game asks for
# (a command.txt next to this script: next, prev or playpause). Exits on its own once the
# game is gone.
param([int]$GamePid)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
        Where-Object {
            $_.Name -eq 'AsTask' -and
            $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
        } | Select-Object -First 1
    function Await($op, $t) {
        $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op))
        if (-not $task.Wait(3000)) { throw "timed out" }
        $task.Result
    }
    $mgrT = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
    $mgr = Await ($mgrT::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
} catch {
    Write-Output ("Could not open the media session: " + $_.Exception.Message)
    Write-Output '{"error":true}'
    exit 0
}

$commandFile = Join-Path $PSScriptRoot 'command.txt'
$lastTrack = ''
$chosenApp = ''
# For players that report no timeline: how long this song has been playing, as far as can be seen.
$counted = 0.0
$songLength = 0.0
$lastLoop = [DateTimeOffset]::UtcNow
# Only a change seen while running counts; the song playing at start is trusted as reported.
$trackStartedAt = [DateTimeOffset]::MinValue.AddDays(1)

while ($true) {
    if ($GamePid -gt 0 -and -not (Get-Process -Id $GamePid -ErrorAction SilentlyContinue)) { exit 0 }
    $line = '{"playing":false}'
    try {
        # Whatever is playing, Spotify first and then the app already being read, so an open but
        # paused Spotify does not hide YouTube Music in a browser. With nothing playing, stay on
        # the same app so a pause keeps its song.
        $session = $null
        $sessions = @()
        try { $sessions = @($mgr.GetSessions()) } catch {}
        $playingNow = @($sessions | Where-Object {
            try { $_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing' } catch { $false }
        })
        $session = $playingNow | Where-Object { [string]$_.SourceAppUserModelId -match 'Spotify' } | Select-Object -First 1
        if (-not $session) { $session = $playingNow | Where-Object { [string]$_.SourceAppUserModelId -eq $chosenApp } | Select-Object -First 1 }
        if (-not $session) { $session = $playingNow | Select-Object -First 1 }
        if (-not $session) { $session = $sessions | Where-Object { [string]$_.SourceAppUserModelId -eq $chosenApp } | Select-Object -First 1 }
        if (-not $session) { $session = $mgr.GetCurrentSession() }
        if ($session) { $chosenApp = [string]$session.SourceAppUserModelId }

        if ($session -and (Test-Path $commandFile)) {
            $command = (Get-Content $commandFile -Raw).Trim()
            Remove-Item $commandFile -Force
            try {
                switch ($command) {
                    'next' { [void](Await ($session.TrySkipNextAsync()) ([bool])) }
                    'prev' { [void](Await ($session.TrySkipPreviousAsync()) ([bool])) }
                    'playpause' { [void](Await ($session.TryTogglePlayPauseAsync()) ([bool])) }
                }
            } catch {}
        }

        if ($session) {
            $props = Await ($session.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $timeline = $session.GetTimelineProperties()
            $playing = $session.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing'
            $now = [DateTimeOffset]::UtcNow

            # Spotify does not always update its timeline when the next song starts, so for a
            # moment the old song's position is still reported. A position from before the
            # song changed is ignored and the time since the change is used instead.
            $track = [string]$props.Title + '|' + [string]$props.Artist
            if ($track -ne $lastTrack) {
                if ($lastTrack -ne '') { $trackStartedAt = $now }
                $lastTrack = $track
                $counted = 0.0
                $songLength = 0.0
            }
            # Browsers correct the length by a second or so mid-song; that is still the same song.
            $reportedLength = $timeline.EndTime.TotalMilliseconds
            if ($songLength -le 0 -or [Math]::Abs($reportedLength - $songLength) -gt 3000) { $songLength = $reportedLength }
            if ($playing) { $counted += ($now - $lastLoop).TotalMilliseconds }
            $updated = $timeline.LastUpdatedTime
            if ($timeline.EndTime.TotalMilliseconds -le 0 -and $timeline.Position.TotalMilliseconds -le 0) {
                # Some browser players give a song but no timeline at all.
                $position = $counted
            } elseif ($updated.ToUnixTimeMilliseconds() -gt 0 -and $updated -lt $trackStartedAt.AddMilliseconds(-500)) {
                $position = ($now - $trackStartedAt).TotalMilliseconds
            } else {
                $position = $timeline.Position.TotalMilliseconds
                if ($playing -and $updated.ToUnixTimeMilliseconds() -gt 0) {
                    $position += ($now - $updated).TotalMilliseconds
                }
            }

            $line = [ordered]@{
                playing    = $playing
                title      = [string]$props.Title
                artist     = [string]$props.Artist
                album      = [string]$props.AlbumTitle
                positionMs = [int64]$position
                durationMs = [int64]$songLength
            } | ConvertTo-Json -Compress
        }
    } catch {}
    $lastLoop = [DateTimeOffset]::UtcNow
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 400
}
