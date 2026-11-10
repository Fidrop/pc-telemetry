param([string]$Destination = 'artifacts/PC-Telemetry-Windows-x64')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dotnet = if (Test-Path "$root/.tools/dotnet/dotnet.exe") { "$root/.tools/dotnet/dotnet.exe" } else { (Get-Command dotnet -ErrorAction Stop).Source }
if (Test-Path "$root/.tools/dotnet") {
    $env:DOTNET_ROOT = "$root/.tools/dotnet"
    $env:DOTNET_CLI_HOME = "$root/.tools/cli-home"
    $env:NUGET_PACKAGES = "$root/.tools/packages"
}
$outputPath = Join-Path $root $Destination
Push-Location $root
try {
    & $dotnet publish windows/Collector/Collector.csproj -c Release -r win-x64 --self-contained true `
        -p:NuGetLockFilePath=packages.publish.lock.json -p:RestoreLockedMode=true `
        -p:PublishSingleFile=false -p:PublishTrimmed=false -o $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'Windows portable publish failed' }
    Copy-Item "$root/docs/user-guide.md" "$outputPath/使用说明.md"
    Copy-Item "$root/docs/known-issues.md" "$outputPath/已知问题.md"
    Write-Output "Self-contained Windows x64 folder: $outputPath"
} finally { Pop-Location }
