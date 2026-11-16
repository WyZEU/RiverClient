param([Parameter(Mandatory)][string]$ArtifactDirectory, [Parameter(Mandatory)][string]$PublicationDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$config = Get-Content -Raw (Join-Path $PSScriptRoot '..\windows-signing.json') | ConvertFrom-Json
$files = @(
    (Join-Path $ArtifactDirectory 'River-Client-Setup.exe'),
    (Join-Path $ArtifactDirectory 'River-Client-Portable.exe'),
    (Join-Path $PublicationDirectory 'app\RiverClient.exe'),
    (Join-Path $PublicationDirectory 'app\resources\app.asar.unpacked\app\np-helper.exe')
)
foreach ($file in $files) {
    $signature = Get-AuthenticodeSignature -LiteralPath $file
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -ne $config.subject -or $null -eq $signature.TimeStamperCertificate) {
        throw "Publication requires a valid timestamped Supro Nivelo signature: $file"
    }
}
Write-Output 'Verified the downloaded installers and extracted application/helper signatures.'
