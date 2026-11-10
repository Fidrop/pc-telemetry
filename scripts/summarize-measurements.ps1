$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$frames = @(Get-Content "$projectRoot/docs/stage-1-live-snapshots.jsonl" | ForEach-Object { $_ | ConvertFrom-Json })
$intervals = @(for ($i = 1; $i -lt $frames.Count; $i++) {
    ([DateTimeOffset]$frames[$i].sampledAt - [DateTimeOffset]$frames[$i - 1].sampledAt).TotalMilliseconds
})
$timings = @(Get-Content "$projectRoot/docs/stage-1-live-timings.jsonl" | ForEach-Object { ($_ | ConvertFrom-Json).timings })
$processRows = @(Import-Csv "$projectRoot/docs/stage-1-live-process.csv" | Where-Object { [double]$_.ElapsedSeconds -ge 5 })
function Statistics($values) {
    $numbers = @($values | ForEach-Object { [double]$_ } | Sort-Object)
    if ($numbers.Count -eq 0) { throw 'Missing measurement values' }
    $summary = $numbers | Measure-Object -Minimum -Maximum -Average
    return [ordered]@{
        Count = $numbers.Count
        Minimum = $summary.Minimum
        Maximum = $summary.Maximum
        Average = $summary.Average
        P50 = $numbers[[math]::Ceiling($numbers.Count * 0.50) - 1]
        P95 = $numbers[[math]::Ceiling($numbers.Count * 0.95) - 1]
        P99 = $numbers[[math]::Ceiling($numbers.Count * 0.99) - 1]
    }
}
$measurement = [ordered]@{
    Samples = $frames.Count
    IntervalMilliseconds = Statistics $intervals
    GpuStates = @($frames | Group-Object { $_.metrics.gpuUsage.status } | Select-Object Name,Count)
    MachineCpuPercent = Statistics $processRows.MachineCpuPercent
    WorkingSetMiB = Statistics @($processRows | ForEach-Object { [double]$_.WorkingSetBytes / 1MB })
    SensorUpdateMilliseconds = @($timings | Group-Object id | ForEach-Object {
        [ordered]@{ Group = $_.Name; Statistics = Statistics $_.Group.milliseconds }
    })
    ApplicationJsonBytes = (Get-Item "$projectRoot/docs/stage-1-live-snapshots.jsonl").Length
    RestartCount = (@(Get-Content "$projectRoot/docs/stage-1-live-timings.jsonl" |
        ForEach-Object { ($_ | ConvertFrom-Json).restartCount }) | Measure-Object -Maximum).Maximum
    Scope = 'Short diagnostic run, 5s process warmup excluded, Collector + worker + console host, probe excluded. No game baseline or wire traffic measurement.'
}
$measurement | ConvertTo-Json -Depth 8 | Set-Content "$projectRoot/docs/stage-1-measurements.json" -Encoding utf8
$measurement | ConvertTo-Json -Depth 8
