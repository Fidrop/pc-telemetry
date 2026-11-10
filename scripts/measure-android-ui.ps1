param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
& "$PSScriptRoot/build-android.ps1" -SkipLint
$gradle = if (Test-Path "$root/.tools/gradle/gradle-8.11.1/bin/gradle.bat") { "$root/.tools/gradle/gradle-8.11.1/bin/gradle.bat" } else { "$root/android/gradlew.bat" }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $gradle -p "$root/android" :app:assembleDebugAndroidTest --console plain
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation build failed' }
foreach ($apk in @('apk/debug/app-debug.apk', 'apk/androidTest/debug/app-debug-androidTest.apk')) {
    & $adb -s $Device install -r "$root/android/app/build/outputs/$apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
}
$result = & $adb -s $Device shell am instrument -w -e class `
    'com.pctelemetry.dashboard.DashboardPerformanceTest' -e capturePerformance true `
    com.pctelemetry.dashboard.test/androidx.test.runner.AndroidJUnitRunner
$result | Tee-Object "$root/docs/stage-6-frame-capture.txt"
if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw 'Frame capture failed' }
New-Item -ItemType Directory -Force "$root/docs/qa/performance" | Out-Null
foreach ($theme in @('sport','hud')) {
    & $adb -s $Device pull "/sdcard/Android/data/com.pctelemetry.dashboard/files/performance/$theme-gfxinfo.txt" `
        "$root/docs/qa/performance/$theme-gfxinfo.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Frame report transfer failed' }
}
Write-Output 'Frame reports captured. Emulator timings do not establish physical phone performance.'
