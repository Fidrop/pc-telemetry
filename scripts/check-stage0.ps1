 $ErrorActionPreference = 'Stop'
 $root = Split-Path $PSScriptRoot -Parent
 function Assert-Condition($condition, $message) {
     if (-not $condition) { throw $message }
 }
 $baseline = Get-Content (Join-Path $root 'docs/requirements/source-message.md') -Raw -Encoding utf8
 $sections = [regex]::Matches($baseline, '(?m)^## [一二三四五六七八九十]+、').Count
 Assert-Condition ($sections -eq 15) 'Baseline must contain all 15 sections'
 foreach ($directory in @('android','windows','protocol','tests','docs','scripts')) {
     Assert-Condition (Test-Path (Join-Path $root $directory) -PathType Container) "Missing $directory"
 }
 Get-ChildItem (Join-Path $root 'protocol') -Filter '*.json' -Recurse | ForEach-Object {
     $null = Get-Content $_.FullName -Raw -Encoding utf8 | ConvertFrom-Json
 }
 $count = 0
 foreach ($file in Get-ChildItem (Join-Path $root 'protocol/examples') -Filter '*.json') {
     $snapshot = Get-Content $file.FullName -Raw -Encoding utf8 | ConvertFrom-Json
     Assert-Condition ($snapshot.mode -eq 'demo') 'Fixtures must be explicitly demo'
     Assert-Condition ($snapshot.version -eq 1 -and $snapshot.type -eq 'telemetry') 'Invalid envelope'
     Assert-Condition (@($snapshot.metrics.PSObject.Properties).Count -eq 6) 'Expected six metrics'
     foreach ($name in @('cpuUsage','gpuUsage','cpuTemperature','gpuTemperature')) {
         $metric = $snapshot.metrics.$name
         if ($metric.status -eq 'unavailable') {
             Assert-Condition ($null -eq $metric.value) 'Missing sensor must be null'
         } else {
             Assert-Condition ($null -ne $metric.value -and $null -ne $metric.sampledAt) 'Available sensor needs value and time'
             if ($name -like '*Usage') {
                 Assert-Condition ($metric.value -ge 0 -and $metric.value -le 100) 'Percent out of range'
             }
         }
     }
     foreach ($name in @('ram','vram')) {
         $metric = $snapshot.metrics.$name
         if ($metric.status -eq 'unavailable') {
             Assert-Condition ($null -eq $metric.usedBytes -and $null -eq $metric.totalBytes -and $null -eq $metric.percent) 'Unavailable memory must be null'
         } else {
             Assert-Condition ($metric.totalBytes -gt 0 -and $metric.usedBytes -ge 0 -and $metric.usedBytes -le $metric.totalBytes) 'Invalid capacity'
             $expected = 100.0 * $metric.usedBytes / $metric.totalBytes
             Assert-Condition ([math]::Abs($expected - $metric.percent) -le 0.05) 'Capacity percent mismatch'
         }
     }
     $count++
 }
 [xml]$project = Get-Content (Join-Path $root 'windows/Collector/Collector.csproj') -Raw
 [xml]$properties = Get-Content (Join-Path $root 'windows/Directory.Build.props') -Raw
 Write-Output "PASS: 15 requirement sections; 6 directories; $count DEMO fixtures; JSON syntax; fixture semantic checks; Windows XML syntax."
 Write-Output 'OUTSIDE THIS SCRIPT: full schema validation, production tests, dependency restore, APK/EXE build, hardware/performance tests. See stage-specific reports.'
