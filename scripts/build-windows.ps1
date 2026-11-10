param([switch]$Test)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localDotnet = Join-Path $projectRoot '.tools/dotnet/dotnet.exe'
$dotnetCommand = if (Test-Path $localDotnet) { $localDotnet } else { (Get-Command dotnet -ErrorAction Stop).Source }
if (Test-Path $localDotnet) {
    $env:DOTNET_ROOT = Split-Path $localDotnet -Parent
    $env:DOTNET_CLI_HOME = Join-Path $projectRoot '.tools/cli-home'
    $env:NUGET_PACKAGES = Join-Path $projectRoot '.tools/packages'
}
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
Push-Location $projectRoot
try {
    & $dotnetCommand restore windows/Collector/Collector.csproj --locked-mode
    if ($LASTEXITCODE -ne 0) { throw 'Collector dependency restore failed' }
    & $dotnetCommand build windows/Collector/Collector.csproj -c Release --no-restore
    if ($LASTEXITCODE -ne 0) { throw 'Collector build failed' }
    if ($Test) {
        & $dotnetCommand restore tests/Collector.Tests/Collector.Tests.csproj --locked-mode
        if ($LASTEXITCODE -ne 0) { throw 'Test dependency restore failed' }
        & $dotnetCommand run --project tests/Collector.Tests/Collector.Tests.csproj -c Release --no-restore -- $projectRoot
        if ($LASTEXITCODE -ne 0) { throw 'Collector tests failed' }
    }
} finally { Pop-Location }
