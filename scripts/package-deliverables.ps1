param([string]$WindowsDirectory = 'artifacts/PC-Telemetry-Windows-x64')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Split-Path $PSScriptRoot -Parent
$windowsPath = Join-Path $root $WindowsDirectory
$apk = Join-Path $root 'android/app/build/outputs/apk/debug/app-debug.apk'
if (!(Test-Path "$windowsPath/Collector.exe") -or !(Test-Path $apk)) { throw 'Build Windows portable and Android Debug first' }
$androidVersion = [regex]::Match([IO.File]::ReadAllText("$root/android/app/build.gradle.kts"),
    'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if ($androidVersion -notmatch '^[a-zA-Z0-9.-]+$') { throw 'Cannot read safe Android version name' }
& "$PSScriptRoot/collect-notices.ps1"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$folder = Join-Path $root "artifacts/PC-Telemetry-preview-$stamp"
New-Item -ItemType Directory -Path $folder | Out-Null
Copy-Item -LiteralPath $windowsPath -Destination "$folder/Windows-x64" -Recurse
Copy-Item -LiteralPath $apk -Destination "$folder/PC-Telemetry-$androidVersion-debug.apk"
Copy-Item "$root/artifacts/notices" "$folder/notices" -Recurse
Copy-Item "$root/docs/user-guide.md" "$folder/使用说明.md"
Copy-Item "$root/docs/known-issues.md" "$folder/已知问题.md"
Copy-Item "$root/docs/stage-6-report.md" "$folder/验证报告.md"
if (Test-Path "$root/docs/visual-revision-report.md") {
    Copy-Item "$root/docs/visual-revision-report.md" "$folder/界面更新说明.md"
}
if (Test-Path "$root/docs/clock-battery-report.md") {
    Copy-Item "$root/docs/clock-battery-report.md" "$folder/时间与电量更新说明.md"
}
if (Test-Path "$root/docs/sport-battery-bar-report.md") {
    Copy-Item "$root/docs/sport-battery-bar-report.md" "$folder/SPORT电量灯条说明.md"
}
$sourceCommit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source commit' }
[ordered]@{ SourceCommit = $sourceCommit; BuiltAt = (Get-Date).ToString('o');
    Windows = 'win-x64 self-contained, .NET 8.0.33'; Android = "$androidVersion Debug, min API29";
    Acceptance = 'Preview; hardware, memory target, game impact and long-run acceptance remain open'
} | ConvertTo-Json | Set-Content "$folder/build-info.json" -Encoding utf8
$files = @(Get-ChildItem -LiteralPath $folder -File -Recurse)
$hashes = foreach ($file in $files) {
    [pscustomobject]@{ Path = $file.FullName.Substring($folder.Length + 1).Replace('\','/');
        SHA256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
}
$hashes | ConvertTo-Json | Set-Content "$folder/file-hashes.json" -Encoding utf8
$zip = "$folder.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($folder, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
$archive = [IO.Compression.ZipFile]::OpenRead($zip)
try {
    if ($archive.Entries.Count -ne ($files.Count + 1)) { throw 'Delivery archive file count mismatch' }
    foreach ($entry in $archive.Entries) {
        $expected = (Get-FileHash -LiteralPath (Join-Path $folder $entry.FullName) -Algorithm SHA256).Hash
        $stream = $entry.Open()
        $hasher = [Security.Cryptography.SHA256]::Create()
        try {
            $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-','')
            if ($expected -ne $actual) { throw "Delivery archive content mismatch: $($entry.FullName)" }
        } finally { $stream.Dispose(); $hasher.Dispose() }
    }
} finally { $archive.Dispose() }
$hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
"$hash  $([IO.Path]::GetFileName($zip))" | Set-Content "$zip.sha256" -Encoding utf8
[pscustomobject]@{ Archive = $zip; Bytes = (Get-Item $zip).Length; SHA256 = $hash; Verified = $true }
