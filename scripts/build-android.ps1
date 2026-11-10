param([switch]$LiveTests, [switch]$SkipLint)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$localJdk = Get-ChildItem (Join-Path $projectRoot '.tools/jdk17') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $localJdk) { $env:JAVA_HOME = $localJdk.FullName }
$localSdk = Join-Path $projectRoot '.tools/android-sdk'
if (Test-Path $localSdk) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
    "sdk.dir=$($localSdk.Replace([IO.Path]::DirectorySeparatorChar, [char]'/'))" |
        Set-Content (Join-Path $projectRoot 'android/local.properties') -Encoding ascii
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
$localGradle = Join-Path $projectRoot '.tools/gradle/gradle-8.11.1/bin/gradle.bat'
$gradle = if (Test-Path $localGradle) { $localGradle } else { Join-Path $projectRoot 'android/gradlew.bat' }
$arguments = @('-p', (Join-Path $projectRoot 'android'), '--console', 'plain')
# Honor the user's existing system proxy without changing persistent settings.
$proxy = Get-ItemProperty 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Internet Settings' -ErrorAction SilentlyContinue
if ($proxy.ProxyEnable -eq 1 -and $proxy.ProxyServer -match '^([^:;=]+):(\d+)$') {
    $arguments += @("-Dhttps.proxyHost=$($Matches[1])", "-Dhttps.proxyPort=$($Matches[2])",
        "-Dhttp.proxyHost=$($Matches[1])", "-Dhttp.proxyPort=$($Matches[2])")
}
if ($LiveTests) {
    if ([string]::IsNullOrWhiteSpace($env:PC_TELEMETRY_TEST_PROFILE)) { throw 'Use test-android-live.ps1 to supply an in-memory test profile' }
    $arguments += @(':telemetry-core:test', '--tests', '*LiveIntegrationTest', '--rerun-tasks')
} else {
    $arguments += @(':telemetry-core:test', ':app:assembleDebug')
    if (-not $SkipLint) { $arguments += ':app:lintDebug' }
}
& $gradle @arguments
if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed' }
