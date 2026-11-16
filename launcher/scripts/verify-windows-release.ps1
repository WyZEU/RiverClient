$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$launcher = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $launcher 'dist'
$unpacked = Join-Path $dist 'win-unpacked'
$config = Get-Content -Raw (Join-Path $launcher 'windows-signing.json') | ConvertFrom-Json
$pkg = Get-Content -Raw (Join-Path $launcher 'package.json') | ConvertFrom-Json
$evidence = [ordered]@{ sourceCommit = $env:GITHUB_SHA; version = $pkg.riverVersion; signatures = @(); checks = @() }
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ('river-release-' + [guid]::NewGuid())
$install = Join-Path $scratch 'installed'

function Assert-Signed([string]$Path) {
    $s = Get-AuthenticodeSignature -LiteralPath $Path
    if ($s.Status -ne 'Valid' -or $null -eq $s.SignerCertificate -or
        $s.SignerCertificate.Subject -ne $config.subject -or $null -eq $s.TimeStamperCertificate) {
        throw "Expected a valid timestamped Supro Nivelo signature: $Path ($($s.Status))."
    }
    $evidence.signatures += [ordered]@{
        file = $Path; subject = $s.SignerCertificate.Subject
        thumbprint = $s.SignerCertificate.Thumbprint
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Run-Checked([string]$Exe, [string]$Arguments, [string]$Label) {
    $stdout = Join-Path $scratch "$Label.stdout.txt"
    $stderr = Join-Path $scratch "$Label.stderr.txt"
    $start = @{ FilePath = $Exe; PassThru = $true; WindowStyle = 'Hidden'
        RedirectStandardOutput = $stdout; RedirectStandardError = $stderr }
    if ($Arguments) { $start.ArgumentList = $Arguments }
    $p = Start-Process @start
    if (-not $p.WaitForExit(60000)) {
        Stop-Process -Id $p.Id -ErrorAction SilentlyContinue
        throw "$Label timed out."
    }
    $p.WaitForExit()
    $result = [ordered]@{ name = $Label; exitCode = $p.ExitCode
        stdout = (Get-Content -Raw $stdout -ErrorAction SilentlyContinue)
        stderr = (Get-Content -Raw $stderr -ErrorAction SilentlyContinue) }
    $evidence.checks += $result
    if ($p.ExitCode -ne 0) { throw "$Label exited with $($p.ExitCode): $($result.stderr)" }
    return $result.stdout
}

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    if ($env:GITHUB_SHA -notmatch '^[a-f0-9]{40}$') { throw 'Missing source commit.' }
    $runtime = Get-Content -Raw (Join-Path $unpacked 'resources\bundled\river-build-info.json') | ConvertFrom-Json
    if ($runtime.sourceCommit -ne $env:GITHUB_SHA -or $runtime.version -ne $pkg.riverVersion) {
        throw 'Packaged runtime provenance mismatch.'
    }
    # Only the default Minecraft version and the bootstrap ship inside the app. The other
    # versions go into River-Client-Mods.zip and are downloaded when an instance needs them.
    $mods = Join-Path $scratch 'mods'
    New-Item -ItemType Directory -Path $mods | Out-Null
    $bundledJar = "clientcore-$($runtime.version)+$($runtime.bundledMinecraft).jar"
    if (-not ($runtime.files | Where-Object { $_.name -eq $bundledJar })) { throw "Missing bundled JAR $bundledJar." }
    foreach ($jar in $runtime.files) {
        $inApp = Join-Path $unpacked ("resources\bundled\" + $jar.name)
        if ($jar.name -eq $bundledJar -or $jar.name -match '^river-bootstrap-[0-9.]+\.jar$') {
            $file = $inApp
        } else {
            if (Test-Path -LiteralPath $inApp) { throw "A non-default Minecraft version is inside the app: $($jar.name)" }
            $file = Join-Path $mods $jar.name
            Copy-Item -LiteralPath (Join-Path $launcher ("release-mods\" + $jar.name)) -Destination $file
        }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $jar.sha256) {
            throw "Packaged JAR changed after staging: $($jar.name)"
        }
    }
    $setup = Join-Path $dist 'River-Client-Setup.exe'
    $portable = Join-Path $dist 'River-Client-Portable.exe'
    $helperRelative = 'resources\app.asar.unpacked\app\np-helper.exe'
    foreach ($file in @($setup, $portable, (Join-Path $unpacked 'RiverClient.exe'), (Join-Path $unpacked $helperRelative))) {
        Assert-Signed $file
    }
    $null = Run-Checked $setup "/S /D=$install" 'install'
    $installedExe = Join-Path $install 'RiverClient.exe'
    Assert-Signed $installedExe
    Assert-Signed (Join-Path $install $helperRelative)
    if ((Get-FileHash $installedExe).Hash -ne (Get-FileHash (Join-Path $unpacked 'RiverClient.exe')).Hash) {
        throw 'Installed executable differs from signed build.'
    }
    $uninstallers = @(Get-ChildItem -LiteralPath $install -Filter '*Uninstall*.exe')
    if ($uninstallers.Count -ne 1) { throw 'Expected exactly one installed uninstaller.' }
    Assert-Signed $uninstallers[0].FullName
    # Exercise the packaged Electron runtime without signing into a user account,
    # contacting production services or starting the game's auto-updater.
    $previousMode = $env:ELECTRON_RUN_AS_NODE
    try {
        $env:ELECTRON_RUN_AS_NODE = '1'
        $result = Run-Checked $installedExe '-e "console.log(JSON.stringify(process.versions))"' 'electron-runtime'
        $versions = $result | ConvertFrom-Json
        if (-not $versions.electron -or -not $versions.node) { throw 'Electron runtime did not start.' }
    } finally { $env:ELECTRON_RUN_AS_NODE = $previousMode }
    $helperResult = Run-Checked (Join-Path $install $helperRelative) '' 'media-helper'
    $helperState = $helperResult | ConvertFrom-Json
    if ($null -eq $helperState.playing) { throw 'Media helper did not return its JSON contract.' }
    $null = Run-Checked $uninstallers[0].FullName '/S' 'uninstall'
    $deadline = (Get-Date).AddSeconds(15)
    while ((Test-Path $installedExe) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 250 }
    if (Test-Path $installedExe) { throw 'Uninstall did not remove the test application.' }

    $release = Join-Path $dist 'signed-release'
    if (Test-Path $release) { throw 'Release output already exists; start from a fresh build.' }
    New-Item -ItemType Directory -Path $release | Out-Null
    Copy-Item -LiteralPath $setup, $portable -Destination $release
    [System.IO.Compression.ZipFile]::CreateFromDirectory($mods, (Join-Path $release 'River-Client-Mods.zip'))
    $zip = Join-Path $release 'River-Client-App.zip'
    $appFiles = @(Get-ChildItem -LiteralPath $unpacked -File -Recurse | ForEach-Object {
        [ordered]@{
            path = [System.IO.Path]::GetRelativePath($unpacked, $_.FullName).Replace('\', '/')
            size = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } | Sort-Object { $_.path })
    [System.IO.Compression.ZipFile]::CreateFromDirectory($unpacked, $zip)
    $expectedFiles = @{}
    foreach ($file in $appFiles) { $expectedFiles[$file.path] = $file }
    $archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $entries = @($archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        if ($entries.Count -ne $appFiles.Count) { throw 'App ZIP file count mismatch.' }
        foreach ($entry in $entries) {
            if (-not $expectedFiles.ContainsKey($entry.FullName)) { throw "Unexpected ZIP entry: $($entry.FullName)" }
            $expected = $expectedFiles[$entry.FullName]
            $stream = $entry.Open()
            $hasher = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = [Convert]::ToHexString($hasher.ComputeHash($stream)).ToLowerInvariant() }
            finally { $stream.Dispose(); $hasher.Dispose() }
            if ($hash -ne $expected.sha256 -or $entry.Length -ne $expected.size) {
                throw "App ZIP changed a signed release file: $($entry.FullName)"
            }
            $expectedFiles.Remove($entry.FullName)
        }
        if ($expectedFiles.Count) { throw 'App ZIP omitted release files.' }
    } finally { $archive.Dispose() }
    [ordered]@{ sourceCommit = $env:GITHUB_SHA; version = $pkg.riverVersion; files = $appFiles } |
        ConvertTo-Json -Depth 6 | Set-Content (Join-Path $release 'WINDOWS-FILES.json') -Encoding utf8
    $evidence.runtime = $runtime
    $evidence.validation = 'Authenticode, per-user install/uninstall, Electron runtime and media helper; no login/gameplay/UI test'
    $evidence.signing = $config
    $evidence | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $release 'WINDOWS-BUILD-INFO.json') -Encoding utf8
    Copy-Item -LiteralPath $env:RIVER_SIGNING_LOG -Destination (Join-Path $release 'signatures.jsonl')
    Get-ChildItem -LiteralPath $release -File | Sort-Object Name | ForEach-Object {
        "{0}  {1}" -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
    } | Set-Content (Join-Path $release 'SHA256SUMS.txt') -Encoding ascii
} finally {
    $evidence | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $dist 'windows-validation.json') -Encoding utf8
    if (Test-Path $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue }
}
