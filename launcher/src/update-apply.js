const quote = (value) => String(value).replace(/'/g, "''");

function buildApplyUpdateScript({ pid, sourceDir, installDir, relaunchExe, relaunchFallbackExe, logPath, updateRoot, mode = "package", manifestPath = "" }) {
  // Keep compatible with Windows PowerShell 5.1, which runs the installed updater.
  return String.raw`$ErrorActionPreference = 'Stop'
Start-Sleep -Seconds 2
Wait-Process -Id ${Number(pid)} -ErrorAction SilentlyContinue
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Process -Name 'RiverClient' -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
Start-Sleep -Seconds 1
$source = [IO.Path]::GetFullPath('${quote(sourceDir)}').TrimEnd([char[]]'\/')
$target = [IO.Path]::GetFullPath('${quote(installDir)}').TrimEnd([char[]]'\/')
$log = '${quote(logPath)}'
$mode = '${quote(mode)}'
$manifestPath = '${quote(manifestPath)}'
$primary = '${quote(relaunchExe)}'
$fallback = '${quote(relaunchFallbackExe)}'
$updateRoot = '${quote(updateRoot)}'
$transactions = Join-Path $updateRoot 'transactions'
$backup = Join-Path $transactions ([Guid]::NewGuid().ToString('N'))
$journal = Join-Path $backup 'transaction.json'
$operations = New-Object 'System.Collections.Generic.List[object]'
$touched = New-Object 'System.Collections.Generic.List[object]'
$lock = $null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $log) | Out-Null
Set-Content -LiteralPath $log -Value ('Update script started: ' + (Get-Date).ToString('s')) -Encoding utf8
function Log([string]$message) {
  # Logging must not prevent restoration when the disk is full or the log is locked.
  try { Add-Content -LiteralPath $log -Value $message } catch { Write-Warning $message }
}
function Copy-WithRetry([string]$from, [string]$to) {
  $lastError = $null
  for ($attempt = 1; $attempt -le 10; $attempt++) {
    try { Copy-Item -LiteralPath $from -Destination $to -Force -ErrorAction Stop; return }
    catch { $lastError = $_; Start-Sleep -Milliseconds ([Math]::Min(2500, 250 * $attempt)) }
  }
  throw $lastError
}
function File-Hash([string]$file) {
  $stream = $null
  $sha = [Security.Cryptography.SHA256]::Create()
  try {
    $stream = [IO.File]::OpenRead($file)
    return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
  } finally { if ($null -ne $stream) { $stream.Dispose() }; $sha.Dispose() }
}
function Checked-Path([string]$root, [string]$relative) {
  $parts = $relative -split '/'
  if ([string]::IsNullOrWhiteSpace($relative) -or $relative -match '[\\:*?"<>|]' -or
      @($parts | Where-Object { $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' -or $_ -match '[. ]$' }).Count) {
    throw "Invalid update path: $relative"
  }
  $result = $root
  foreach ($part in $parts) {
    $result = Join-Path $result $part
    if ((Test-Path -LiteralPath $result) -and ((Get-Item -LiteralPath $result -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
      throw "Update path is a link: $relative"
    }
  }
  return $result
}
function Relative-Path([string]$root, [string]$file) { return $file.Substring($root.Length).TrimStart([char[]]'\/').Replace('\', '/') }
function Write-Journal([string]$state) {
  @{ state = $state; target = $target; operations = $operations.ToArray(); touched = @($touched.ToArray() | ForEach-Object { $_.relative }) } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $journal -Encoding utf8
}
function Add-Operation([string]$relative, [string]$from) {
  $to = Checked-Path $target $relative
  if ((Test-Path -LiteralPath $to) -and -not (Test-Path -LiteralPath $to -PathType Leaf)) { throw "Expected a file: $relative" }
  $operations.Add(@{ relative = $relative; source = $from; target = $to; existed = (Test-Path -LiteralPath $to -PathType Leaf); hash = '' })
}
try {
  New-Item -ItemType Directory -Force -Path $updateRoot | Out-Null
  $lock = [IO.File]::Open((Join-Path $updateRoot 'apply.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
  if ($source -eq $target) { throw 'Update staging and installation directories are identical.' }
  New-Item -ItemType Directory -Force -Path $backup | Out-Null
  $allowed = @{}
  $changed = @{}
  $scopes = @('resources', 'locales')
  if ($mode -eq 'delta') {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    foreach ($entry in @($manifest.files)) {
      $relative = [string]$entry.path
      $null = Checked-Path $target $relative
      if ($allowed.ContainsKey($relative)) { throw "Duplicate update path: $relative" }
      $allowed[$relative] = $true
    }
    foreach ($entry in @($manifest.changedFiles)) {
      $relative = [string]$entry
      if (-not $allowed.ContainsKey($relative) -or $changed.ContainsKey($relative)) { throw "Invalid changed file: $relative" }
      $changed[$relative] = $true
      $from = Checked-Path $source $relative
      if (-not (Test-Path -LiteralPath $from -PathType Leaf)) { throw "Missing staged file: $relative" }
      Add-Operation $relative $from
    }
  } elseif ($mode -eq 'package') {
    $children = @(Get-ChildItem -LiteralPath $source -Force)
    if ($children.Count -eq 1 -and $children[0].PSIsContainer) { $source = $children[0].FullName }
    foreach ($file in @(Get-ChildItem -LiteralPath $source -File -Recurse -Force)) {
      $relative = Relative-Path $source $file.FullName
      $from = Checked-Path $source $relative
      $allowed[$relative] = $true
      Add-Operation $relative $from
    }
    $scopes = @($scopes | Where-Object { Test-Path -LiteralPath (Join-Path $source $_) -PathType Container })
  } else { throw "Unknown update mode: $mode" }
  if (-not $allowed.ContainsKey('resources/app.asar') -or -not $allowed.ContainsKey('RiverClient.exe')) { throw 'Incomplete launcher update.' }
  foreach ($scope in $scopes) {
    $scopeRoot = Checked-Path $target $scope
    if (-not (Test-Path -LiteralPath $scopeRoot)) { continue }
    foreach ($file in @(Get-ChildItem -LiteralPath $scopeRoot -File -Recurse -Force)) {
      $relative = Relative-Path $target $file.FullName
      if (-not $allowed.ContainsKey($relative)) { Add-Operation $relative '' }
    }
  }
  # Finish and verify every backup before changing any installed file. A backup failure
  # leaves the installation untouched, including files later in the manifest.
  foreach ($operation in $operations) {
    if (-not $operation.existed) { continue }
    $saved = Checked-Path (Join-Path $backup 'files') $operation.relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $saved) | Out-Null
    $operation.hash = File-Hash $operation.target
    Copy-WithRetry $operation.target $saved
    if ((File-Hash $saved) -ne $operation.hash) { throw "Backup verification failed: $($operation.relative)" }
  }
  Write-Journal 'prepared'
  foreach ($operation in $operations) {
    # Record before copying: Copy-Item may partially overwrite a file before failing.
    $touched.Add($operation)
    Write-Journal 'applying'
    if ($operation.source) {
      New-Item -ItemType Directory -Force -Path (Split-Path -Parent $operation.target) | Out-Null
      Copy-WithRetry $operation.source $operation.target
      if ((File-Hash $operation.source) -ne (File-Hash $operation.target)) {
        throw "Copy verification failed: $($operation.relative)"
      }
    } else { Remove-Item -LiteralPath $operation.target -Force -ErrorAction Stop }
    Log ("Applied: " + $operation.relative)
  }
  $start = if (Test-Path -LiteralPath $primary -PathType Leaf) { $primary } elseif (Test-Path -LiteralPath $fallback -PathType Leaf) { $fallback } else { throw 'No executable found after update.' }
  Write-Journal 'completed'
  Log 'Update files verified.'
} catch {
  $failure = $_.Exception.Message
  $rollbackFailed = $false
  Log ("Update failed: " + $failure)
  for ($index = $touched.Count - 1; $index -ge 0; $index--) {
    $operation = $touched[$index]
    try {
      if ($operation.existed) {
        $saved = Checked-Path (Join-Path $backup 'files') $operation.relative
        Copy-WithRetry $saved $operation.target
        if ((File-Hash $operation.target) -ne $operation.hash) { throw 'Restored file hash mismatch.' }
      } elseif (Test-Path -LiteralPath $operation.target) { Remove-Item -LiteralPath $operation.target -Force -ErrorAction Stop }
    } catch {
      $rollbackFailed = $true
      Log ("Restore failed: " + $operation.relative + ': ' + $_.Exception.Message)
    }
  }
  if (Test-Path -LiteralPath $backup) {
    try { Write-Journal $(if ($rollbackFailed) { 'restore-failed' } else { 'rolled-back' }) } catch { Log 'Could not record rollback status.' }
    Log ("Recovery files retained: " + $backup)
  }
  if (-not $rollbackFailed -and $null -ne $lock) {
    Log 'Previous launcher files preserved.'
    $restart = if (Test-Path -LiteralPath $primary -PathType Leaf) { $primary } else { $fallback }
    if (Test-Path -LiteralPath $restart -PathType Leaf) {
      try { Start-Process -FilePath $restart -ArgumentList @('--river-update-failed=' + $log) -WorkingDirectory $target -WindowStyle Normal }
      catch { Log 'Could not restart the previous launcher.' }
    }
  } else { Log 'Launcher was not restarted. Restore the retained recovery files or reinstall River Client.' }
  exit 1
} finally { if ($null -ne $lock) { $lock.Dispose() } }
try {
  Start-Process -FilePath $start -WorkingDirectory $target -WindowStyle Normal
  Log 'Relaunch started.'
} catch { Log 'Update completed, but River Client could not be started. Open the launcher again.'; exit 1 }
# Only completed transactions are disposable. Failed recovery files are never swept.
try {
  Get-ChildItem -LiteralPath $transactions -Directory | Where-Object {
    $record = Join-Path $_.FullName 'transaction.json'
    (Test-Path -LiteralPath $record) -and ((Get-Content -LiteralPath $record -Raw | ConvertFrom-Json).state -eq 'completed')
  } | Sort-Object LastWriteTime -Descending | Select-Object -Skip 2 | Remove-Item -Recurse -Force
  $currentVersion = Split-Path -Parent $log
  Get-ChildItem -LiteralPath $updateRoot -Directory | Where-Object {
    $_.Name -match '^\d+(\.\d+){2,3}$' -and $_.FullName -ne $currentVersion
  } | Sort-Object LastWriteTime -Descending | Select-Object -Skip 1 | Remove-Item -Recurse -Force
} catch { Log 'Old completed backups could not be cleaned up.' }
`.replace(/\r?\n/g, "\r\n");
}

module.exports = { buildApplyUpdateScript };
