param([string]$Destination = 'artifacts/notices')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$destinationPath = Join-Path $root $Destination
New-Item -ItemType Directory -Force $destinationPath | Out-Null
$packageRoot = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES } elseif (Test-Path "$root/.tools/packages") { "$root/.tools/packages" } else { "$env:USERPROFILE/.nuget/packages" }
$assets = Get-Content "$root/windows/Collector/obj/project.assets.json" -Raw | ConvertFrom-Json
$rows = @('Windows dependency metadata (including transitive/runtime packages).',
    'LibreHardwareMonitorLib 0.9.6 is unmodified. Corresponding source: https://github.com/LibreHardwareMonitor/LibreHardwareMonitor/tree/3d331e3370efb858411f19511373eff65a218701',
    'Library licenses do not grant a license to the application source. .NET runtime notices are included in the portable folder.', '')
foreach ($entry in $assets.libraries.PSObject.Properties | Sort-Object Name) {
    if ($entry.Value.type -ne 'package') { continue }
    $packageDirectory = Join-Path $packageRoot $entry.Value.path
    $nuspecFile = Get-ChildItem -LiteralPath $packageDirectory -Filter '*.nuspec' | Select-Object -First 1
    if (!$nuspecFile) { throw "Missing metadata: $($entry.Name)" }
    [xml]$xml = Get-Content -LiteralPath $nuspecFile.FullName -Raw
    $metadata = $xml.package.metadata
    $rows += "$($entry.Name) | author: $($metadata.authors) | copyright: $($metadata.copyright) | license: $($metadata.license.InnerText) $($metadata.licenseUrl) | source: $($metadata.projectUrl) $($metadata.repository.url)"
    if ($metadata.license.type -eq 'file') {
        $licenseFile = Join-Path $packageDirectory $metadata.license.InnerText
        if (!(Test-Path -LiteralPath $licenseFile)) { throw "Missing package license: $licenseFile" }
        $rows += [IO.File]::ReadAllText($licenseFile)
    }
}
$rows | Set-Content "$destinationPath/Windows-dependencies.txt" -Encoding utf8
$gradleRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { "$root/.tools/gradle-home" }
$rows = @('Android Debug runtime dependencies from the locked debugRuntimeClasspath.',
    'AndroidX, Kotlin, kotlinx, OkHttp and Okio are distributed under their individual licenses; see below.', '')
$locks = @(Get-Content "$root/android/app/gradle.lockfile"; Get-Content "$root/android/telemetry-core/gradle.lockfile")
foreach ($line in $locks | Sort-Object -Unique) {
    if ($line -notmatch '^([^:#]+):([^:]+):([^=]+)=(.*)$') { continue }
    $group, $artifact, $version, $scopes = $Matches[1], $Matches[2], $Matches[3], $Matches[4]
    if (($scopes -split ',') -notcontains 'debugRuntimeClasspath' -and ($scopes -split ',') -notcontains 'runtimeClasspath') { continue }
    $pomDirectory = "$gradleRoot/caches/modules-2/files-2.1/$group/$artifact/$version"
    $pom = Get-ChildItem -LiteralPath $pomDirectory -Recurse -Filter '*.pom' | Select-Object -First 1
    if (!$pom) { throw "Missing Android POM: ${group}:${artifact}:$version" }
    [xml]$xml = Get-Content -LiteralPath $pom.FullName -Raw
    $licenses = @($xml.project.licenses.license | ForEach-Object { "$($_.name) $($_.url)" }) -join '; '
    if (!$licenses) { throw "Missing license declaration: ${group}:${artifact}:$version" }
    $developers = @($xml.project.developers.developer | ForEach-Object { "$($_.name) $($_.organization)" }) -join '; '
    $rows += "${group}:${artifact}:$version | license: $licenses | authors: $developers | source: $($xml.project.url)"
}
$rows | Set-Content "$destinationPath/Android-dependencies.txt" -Encoding utf8
Copy-Item "$root/docs/licenses/*" $destinationPath
foreach ($runtime in @('microsoft.netcore.app.runtime.win-x64', 'microsoft.windowsdesktop.app.runtime.win-x64', 'microsoft.aspnetcore.app.runtime.win-x64')) {
    $runtimeDirectory = Join-Path $packageRoot "$runtime/8.0.33"
    if (!(Test-Path $runtimeDirectory)) { throw "Publish the Windows package first: missing $runtimeDirectory" }
    foreach ($file in Get-ChildItem -LiteralPath $runtimeDirectory -File | Where-Object { $_.Name -match '^(LICENSE|THIRD-PARTY-NOTICES)' }) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $destinationPath "$runtime-$($file.Name)")
    }
}
Write-Output "Dependency notices collected: $destinationPath"
