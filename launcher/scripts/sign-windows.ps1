param([Parameter(Mandatory)][string]$FilePath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$config = Get-Content -Raw (Join-Path $PSScriptRoot '..\windows-signing.json') | ConvertFrom-Json
if ($env:RIVER_RELEASE_BUILD -ne '1' -or -not $env:RIVER_SIGNING_LOG) {
    throw 'Release signing requires RIVER_RELEASE_BUILD=1 and RIVER_SIGNING_LOG.'
}
$file = (Get-Item -LiteralPath $FilePath).FullName
Import-Module ArtifactSigning -RequiredVersion $config.moduleVersion -ErrorAction Stop
$parameters = @{
    Endpoint = $config.endpoint
    CodeSigningAccountName = $config.account
    CertificateProfileName = $config.profile
    Files = $file
    FileDigest = 'SHA256'
    TimestampRfc3161 = 'http://timestamp.acs.microsoft.com'
    TimestampDigest = 'SHA256'
    Description = 'River Client'
    DescriptionUrl = 'https://riverclient.xyz'
    ExcludeEnvironmentCredential = $true
    ExcludeWorkloadIdentityCredential = $true
    ExcludeManagedIdentityCredential = $true
    ExcludeSharedTokenCacheCredential = $true
    ExcludeVisualStudioCredential = $true
    ExcludeVisualStudioCodeCredential = $true
    ExcludeAzureCliCredential = $false
    ExcludeAzurePowerShellCredential = $true
    ExcludeAzureDeveloperCliCredential = $true
    ExcludeInteractiveBrowserCredential = $true
}
Invoke-ArtifactSigning @parameters
$signature = Get-AuthenticodeSignature -LiteralPath $file
if ($signature.Status -ne 'Valid' -or
    $null -eq $signature.SignerCertificate -or
    $signature.SignerCertificate.Subject -ne $config.subject -or
    $null -eq $signature.TimeStamperCertificate) {
    throw "Invalid or unexpected timestamped signature on $file ($($signature.Status))."
}
# NSIS deletes its temporary uninstaller after embedding it. Retain evidence of
# every hook call, then verify the installed uninstaller again in the smoke check.
[ordered]@{
    path = $file
    sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    subject = $signature.SignerCertificate.Subject
    thumbprint = $signature.SignerCertificate.Thumbprint
    timestampSubject = $signature.TimeStamperCertificate.Subject
} | ConvertTo-Json -Compress | Add-Content -LiteralPath $env:RIVER_SIGNING_LOG -Encoding utf8
