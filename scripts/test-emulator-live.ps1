param([ValidatePattern('^[a-zA-Z0-9-]+$')][string]$Prefix = 'stage-5')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:DOTNET_ROOT = Join-Path $projectRoot '.tools/dotnet'
$directory = Join-Path $projectRoot ('runtime/emulator-pairing-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$env:PC_TELEMETRY_TEST_DIRECTORY = $directory
$pipeName = 'pc-telemetry-test-' + [Guid]::NewGuid().ToString('N')
$pipe = [IO.Pipes.NamedPipeServerStream]::new($pipeName, [IO.Pipes.PipeDirection]::InOut, 1,
    [IO.Pipes.PipeTransmissionMode]::Byte, [IO.Pipes.PipeOptions]::Asynchronous -bor [IO.Pipes.PipeOptions]::CurrentUserOnly)
$hostProcess = $null
try {
    $hostExe = Join-Path $projectRoot 'tests/Collector.Tests/bin/Release/net8.0-windows/Collector.Tests.exe'
    $hostProcess = Start-Process -FilePath $hostExe -WindowStyle Hidden -PassThru -ArgumentList @('--pairing-host', $pipeName) -RedirectStandardOutput "$directory/host.out.txt" -RedirectStandardError "$directory/host.err.txt"
    $connected = $pipe.WaitForConnectionAsync()
    if (-not $connected.Wait(20000)) { throw 'Pairing fixture did not connect' }
    $reader = [IO.StreamReader]::new($pipe)
    $payload = $reader.ReadLineAsync()
    if (-not $payload.Wait(10000)) { throw 'Pairing fixture information timeout' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload.Result))
    $adb = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
    & $adb -s emulator-5554 reverse tcp:47631 tcp:47631
    $result = & $adb -s emulator-5554 shell am instrument -w -e class 'com.pctelemetry.dashboard.DashboardInstrumentedTest#realCollectorAndKeystoreOnAndroid' -e livePairingBase64 $encoded com.pctelemetry.dashboard.test/androidx.test.runner.AndroidJUnitRunner
    $result | Tee-Object (Join-Path $projectRoot "docs/$Prefix-emulator-live-test.txt")
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw 'Emulator pairing or secure storage test failed' }
    $qa = Join-Path $projectRoot "docs/qa/$Prefix"
    New-Item -ItemType Directory -Force $qa | Out-Null
    foreach ($name in @('sport-live.png', 'paired-devices.png')) {
        & $adb -s emulator-5554 pull "/sdcard/Android/data/com.pctelemetry.dashboard/files/qa/$name" "$qa/$name"
        if ($LASTEXITCODE -ne 0) { throw 'Live screenshot transfer failed' }
    }
    Write-Output 'PASS Emulator pairing, real telemetry and Android Keystore-backed storage.'
} finally {
    $pipe.Dispose()
    Remove-Item Env:PC_TELEMETRY_TEST_DIRECTORY -ErrorAction SilentlyContinue
    if ($null -ne $hostProcess) {
        if (-not $hostProcess.WaitForExit(10000)) { Stop-Process -Id $hostProcess.Id }
        $hostProcess.Dispose()
    }
}
