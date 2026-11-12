param([ValidatePattern('^emulator-\d+$')][string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
# Dedicated emulator only: the tests deliberately change this app's preferences and microphone grant.
& "$PSScriptRoot/build-android.ps1"
$gradle = if (Test-Path "$root/.tools/gradle/gradle-8.11.1/bin/gradle.bat") {
    "$root/.tools/gradle/gradle-8.11.1/bin/gradle.bat"
} else { "$root/android/gradlew.bat" }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
& $gradle -p "$root/android" :app:assembleDebugAndroidTest --console plain
if ($LASTEXITCODE -ne 0) { throw 'Microphone instrumentation build failed' }
foreach ($apk in @('apk/debug/app-debug.apk', 'apk/androidTest/debug/app-debug-androidTest.apk')) {
    & $adb -s $Device install -r "$root/android/app/build/outputs/$apk"
    if ($LASTEXITCODE -ne 0) { throw 'Microphone test APK install failed' }
}
try {
    & $adb -s $Device shell pm revoke com.pctelemetry.dashboard android.permission.RECORD_AUDIO
    $denied = & $adb -s $Device shell am instrument -w -e class `
        'com.pctelemetry.dashboard.MicrophoneInstrumentedTest#permissionDeniedDoesNotEnableCapture' `
        com.pctelemetry.dashboard.test/androidx.test.runner.AndroidJUnitRunner
    $denied | Tee-Object "$root/docs/microphone-denied-tests.txt"
    if (($denied -join "`n") -notmatch 'OK \(1 test\)') { throw 'Microphone permission denial test failed' }
    # Grant through Android's actual permission dialog, so runtime permission and AppOps agree.
    $active = & $adb -s $Device shell am instrument -w -e class `
        'com.pctelemetry.dashboard.MicrophoneInstrumentedTest#microphoneLifecycleSwitchAndSensitivity,com.pctelemetry.dashboard.MicrophoneInstrumentedTest#sensitivityReducesLoudSignalsWithoutInventingSilence' `
        com.pctelemetry.dashboard.test/androidx.test.runner.AndroidJUnitRunner
    $active | Tee-Object "$root/docs/microphone-active-tests.txt"
    if (($active -join "`n") -notmatch 'OK \(2 tests\)') { throw 'Microphone lifecycle or sensitivity test failed' }
    New-Item -ItemType Directory -Force "$root/docs/qa/microphone" | Out-Null
    foreach ($name in @('sport-microphone.png','microphone-settings.png')) {
        & $adb -s $Device pull "/sdcard/Android/data/com.pctelemetry.dashboard/files/qa/$name" "$root/docs/qa/microphone/$name"
        if ($LASTEXITCODE -ne 0) { throw 'Microphone screenshot transfer failed' }
    }
    & $adb -s $Device logcat -d -t 700 | Select-String 'MIC CHECK' | ForEach-Object { $_.Line } |
        Set-Content "$root/docs/microphone-release-checks.txt" -Encoding utf8
    'PASS Native grant/denial, recording lifecycle, persistent sensitivity and signal fixtures.'
} finally {
    & $adb -s $Device shell pm revoke com.pctelemetry.dashboard android.permission.RECORD_AUDIO
    & $adb -s $Device shell dumpsys package com.pctelemetry.dashboard | Select-String 'RECORD_AUDIO' |
        ForEach-Object { $_.Line } | Set-Content "$root/docs/microphone-final-permission.txt" -Encoding utf8
}
