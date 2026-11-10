param([ValidateRange(15, 14400)][int]$Samples = 35,
    [ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Prefix = 'stage-1',
    [switch]$Tray, [string]$ExecutablePath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localDotnet = Join-Path $projectRoot '.tools/dotnet'
if (Test-Path $localDotnet) { $env:DOTNET_ROOT = $localDotnet }
$executable = if ($ExecutablePath) { (Resolve-Path -LiteralPath $ExecutablePath).Path } else { Join-Path $projectRoot 'windows/Collector/bin/Release/net8.0-windows/Collector.exe' }
if (-not (Test-Path $executable)) { throw 'Run build-windows.ps1 first' }
$directory = Join-Path $projectRoot ('runtime/live-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$quotedDirectory = '"' + $directory + '"'
$trayArguments = @()
if ($Tray) { $trayArguments += '--tray' }
$collector = Start-Process -FilePath $executable -WindowStyle Hidden -PassThru -ArgumentList ($trayArguments + @(
    '--samples', "$Samples", '--data-dir', $quotedDirectory, '--output', ('"' + "$directory/snapshots.jsonl" + '"'),
    '--diagnostics', ('"' + "$directory/timings.jsonl" + '"')
)) -RedirectStandardOutput "$directory/collector.out.txt" -RedirectStandardError "$directory/collector.err.txt"
# Keep the native handle open so Windows PowerShell can read ExitCode after polling HasExited.
$null = $collector.Handle
$probe = $null
$rows = [Collections.Generic.List[object]]::new()
$previous = @{}
$watch = [Diagnostics.Stopwatch]::StartNew()
$lastSeconds = 0.0
try {
    Start-Sleep -Seconds 3
    $probe = Start-Process -FilePath $executable -WindowStyle Hidden -PassThru -ArgumentList @(
        '--probe', '8', '--data-dir', $quotedDirectory
    ) -RedirectStandardOutput "$directory/probe.jsonl" -RedirectStandardError "$directory/probe.err.txt"
    $null = $probe.Handle
    while (-not $collector.HasExited -and $watch.Elapsed.TotalSeconds -lt ($Samples + 40)) {
        $childIds = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($collector.Id)" |
            Select-Object -ExpandProperty ProcessId)
        $ids = @($collector.Id) + $childIds
        $cpuSeconds = 0.0
        $totalCpuSeconds = 0.0
        $workingSet = 0L
        $privateBytes = 0L
        foreach ($processId in $ids) {
            $item = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -eq $item) { continue }
            $totalCpu = $item.TotalProcessorTime.TotalSeconds
            if ($previous.ContainsKey($processId)) { $cpuSeconds += [math]::Max(0.0, $totalCpu - $previous[$processId]) }
            $previous[$processId] = $totalCpu
            $totalCpuSeconds += $totalCpu
            $workingSet += $item.WorkingSet64
            $privateBytes += $item.PrivateMemorySize64
            $item.Dispose()
        }
        $seconds = $watch.Elapsed.TotalSeconds
        $rows.Add([pscustomobject]@{
            ElapsedSeconds = $seconds
            MachineCpuPercent = 100 * $cpuSeconds / (($seconds - $lastSeconds) * [Environment]::ProcessorCount)
            WorkingSetBytes = $workingSet
            PrivateBytes = $privateBytes
            ProcessCount = $ids.Count
            CumulativeCpuSeconds = $totalCpuSeconds
        })
        $lastSeconds = $seconds
        Start-Sleep -Seconds 1
        $collector.Refresh()
    }
    if (-not $collector.HasExited) { throw 'Collector did not exit within deadline' }
    $collector.WaitForExit()
    if (-not $probe.WaitForExit(15000)) { throw 'Probe timed out' }
    $rows | Export-Csv "$projectRoot/docs/$Prefix-live-process.csv" -NoTypeInformation -Encoding utf8
    Copy-Item "$directory/snapshots.jsonl" "$projectRoot/docs/$Prefix-live-snapshots.jsonl"
    Copy-Item "$directory/timings.jsonl" "$projectRoot/docs/$Prefix-live-timings.jsonl"
    Copy-Item "$directory/probe.jsonl" "$projectRoot/docs/$Prefix-live-probe.txt"
    Get-Content "$directory/probe.err.txt" | Add-Content "$projectRoot/docs/$Prefix-live-probe.txt"
    $administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    [pscustomobject]@{ CollectorExit = $collector.ExitCode; ProbeExit = $probe.ExitCode;
        IsAdministrator = $administrator; LogicalProcessors = [Environment]::ProcessorCount;
        Samples = $Samples; RuntimeDirectory = $directory
    } | ConvertTo-Json | Set-Content "$projectRoot/docs/$Prefix-live-result.json" -Encoding utf8
    if ($collector.ExitCode -ne 0 -or $probe.ExitCode -ne 0) { throw 'Live verification failed' }
    Get-Content "$directory/collector.err.txt"
    Get-Content "$directory/probe.err.txt"
    Write-Output "PASS $Samples real snapshots, authenticated probe, process measurements, normal shutdown."
} finally {
    foreach ($process in @($probe, $collector)) {
        if ($null -ne $process) {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id }
            $process.Dispose()
        }
    }
}
