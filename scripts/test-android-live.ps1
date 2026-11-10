$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:DOTNET_ROOT = Join-Path $projectRoot '.tools/dotnet'
$executable = Join-Path $projectRoot 'windows/Collector/bin/Release/net8.0-windows/Collector.exe'
if (-not (Test-Path $executable)) { throw 'Build Windows Collector first' }
$directory = Join-Path $projectRoot ('runtime/android-live-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$collector = Start-Process -FilePath $executable -WindowStyle Hidden -PassThru -ArgumentList @(
    '--data-dir', ('"' + $directory + '"')
) -RedirectStandardOutput "$directory/collector.out.txt" -RedirectStandardError "$directory/collector.err.txt"
try {
    $identityFile = Join-Path $directory 'secrets/identity.dpapi'
    for ($attempt = 0; $attempt -lt 30 -and -not (Test-Path $identityFile); $attempt++) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path $identityFile)) { throw 'Collector identity initialization failed' }
    Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
    $plaintext = [Security.Cryptography.ProtectedData]::Unprotect(
        [IO.File]::ReadAllBytes($identityFile), $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { $identity = [Text.Encoding]::UTF8.GetString($plaintext) | ConvertFrom-Json }
    finally { [Array]::Clear($plaintext, 0, $plaintext.Length) }
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
        [Convert]::FromBase64String($identity.certificate), [string]::Empty,
        [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    try {
        $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificate.RawData))
    } finally { $certificate.Dispose() }
    $env:PC_TELEMETRY_TEST_PROFILE = [ordered]@{
        endpoint = 'wss://127.0.0.1:47631/v1/stream'
        fingerprint = $fingerprint
        deviceId = $identity.deviceId
        credentialId = $identity.credentialId
        token = $identity.token
    } | ConvertTo-Json -Compress
    & (Join-Path $PSScriptRoot 'build-android.ps1') -LiveTests
    Write-Output 'PASS Android shared core connected to a real Windows Collector. No physical Android device was involved.'
} finally {
    Remove-Item Env:PC_TELEMETRY_TEST_PROFILE -ErrorAction SilentlyContinue
    if (-not $collector.HasExited) { Stop-Process -Id $collector.Id }
    $collector.Dispose()
}
