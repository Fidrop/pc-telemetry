param([ValidatePattern('^stage[0-6]$')][string]$Stage = 'stage1')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path $PSScriptRoot -Parent
$artifactRoot = Join-Path $projectRoot 'artifacts'
New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
$archivePath = Join-Path $artifactRoot ("PC-Telemetry-$Stage-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
$files = @(Get-ChildItem -LiteralPath $projectRoot -File -Recurse -Force | Where-Object {
    $relative = $_.FullName.Substring($projectRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, [char]'/')
    $relative -notmatch '(^|/)(artifacts|runtime|\.tools|\.git|\.gradle|\.idea|\.vs|bin|obj|build)(/|$)' -and
        $relative -ne 'android/local.properties' -and $_.Extension -notin @('.pfx','.key','.user')
})
$archive = [System.IO.Compression.ZipFile]::Open($archivePath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, [char]'/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName,
            $relative, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    if ($archive.Entries.Count -ne $files.Count) { throw 'Archive file count mismatch' }
    foreach ($entry in $archive.Entries) {
        $expected = (Get-FileHash -LiteralPath (Join-Path $projectRoot $entry.FullName) -Algorithm SHA256).Hash
        $stream = $entry.Open()
        $hasher = [System.Security.Cryptography.SHA256]::Create()
        try {
            $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '')
            if ($expected -ne $actual) { throw "Archive content mismatch $($entry.FullName)" }
        } finally { $stream.Dispose(); $hasher.Dispose() }
    }
} finally { $archive.Dispose() }
$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
"$hash  $([System.IO.Path]::GetFileName($archivePath))" | Set-Content -LiteralPath ($archivePath + '.sha256') -Encoding utf8
[pscustomobject]@{ Archive = $archivePath; Files = $files.Count; Bytes = (Get-Item $archivePath).Length; SHA256 = $hash; Verified = $true }
