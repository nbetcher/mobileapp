<#
.SYNOPSIS
    Runs the on-watch automation tests (button presses, touch, app close, time sync, ...).

.DESCRIPTION
    Builds the debug Pebble app and its test APK, installs both over the existing install with
    `adb install -r` (watch pairings and app data are kept), then runs WatchControlDeviceTest inside
    the Pebble app against the connected watch.

    Needs: one phone on adb with the debug Pebble app installed and a paired watch nearby.
    The tests press buttons on the watch, briefly toggle Quiet Time, and launch and close an app.
    A factory reset is never run. A watch reboot runs only with -Reboot.

    DEFERRED means the watch cannot run that test (older firmware, no touch screen) or its outcome
    could not be observed automatically; check it by hand.

.EXAMPLE
    ./tools/watch-device-tests/run.ps1
.EXAMPLE
    ./tools/watch-device-tests/run.ps1 -Device R5CT1234 -Watch C111ABCD -Reboot
#>
param(
    [string]$Device,     # adb serial, when several phones are attached
    [string]$Watch,      # watch serial or address, when several watches are connected
    [string]$AppUuid,    # watchapp for the launch/close test (default: first launchable locker app)
    [switch]$Reboot,     # also reboot the watch and wait for it to reconnect
    [switch]$SkipBuild   # reuse the last built APKs
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/../..").Path
$testClass = 'coredevices.coreapp.automation.WatchControlDeviceTest'
$runner = 'coredevices.coreapp.test/androidx.test.runner.AndroidJUnitRunner'
$adbTarget = if ($Device) { @('-s', $Device) } else { @() }

function Invoke-Adb { & adb @adbTarget @args }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'adb is not on PATH (Android SDK platform-tools).' }
$devices = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if (-not $Device -and $devices.Count -ne 1) { throw "Expected exactly one phone on adb, found $($devices.Count); pass -Device <serial>." }

if (-not $SkipBuild) {
    $gradlew = if ($IsWindows -or $env:OS -eq 'Windows_NT') { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
    & $gradlew -p $root ':androidApp:assembleDebug' ':androidApp:assembleDebugAndroidTest'
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}

function Install-Apk([string]$Pattern, [string]$What) {
    $apk = Get-ChildItem $Pattern | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $apk) { throw "No $What APK at $Pattern; run without -SkipBuild." }
    $out = (Invoke-Adb install -r -t $apk.FullName 2>&1 | Out-String)
    if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        throw "The installed Pebble app is signed with a different key (e.g. a store build). Replacing it means uninstalling, which removes watch pairings; do that yourself if you accept it, then pair again and rerun."
    }
    if ($out -notmatch 'Success') { throw "Installing the $What APK failed:`n$out" }
    Write-Host "Installed $What APK: $($apk.Name)"
}
Install-Apk (Join-Path $root 'androidApp/build/outputs/apk/debug/*.apk') 'app'
Install-Apk (Join-Path $root 'androidApp/build/outputs/apk/androidTest/debug/*.apk') 'test'

$instrumentArgs = @('-e', 'class', $testClass)
if ($Watch) { $instrumentArgs += @('-e', 'watch', $Watch) }
if ($AppUuid) { $instrumentArgs += @('-e', 'app_uuid', $AppUuid) }
if ($Reboot) { $instrumentArgs += @('-e', 'reboot', 'true') }

Write-Host 'Running on the watch (the Pebble app restarts and reconnects first)...'
$raw = @(Invoke-Adb shell am instrument -w -r @instrumentArgs $runner 2>&1 | ForEach-Object { "$_" })
$logFile = Join-Path $root 'build/watch-device-tests.log'
New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null
$raw | Set-Content $logFile

# `am instrument -r` prints key=value status blocks, each closed by a status code:
# 1 started, 0 passed, -1 error, -2 failed, -3 ignored, -4 assumption failed (deferred).
$results = [System.Collections.Generic.List[object]]::new()
$fields = @{}; $lastKey = $null
foreach ($line in $raw) {
    if ($line -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') { $fields[$Matches[1]] = $Matches[2]; $lastKey = $Matches[1] }
    elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)') {
        $code = [int]$Matches[1]
        if ($code -ne 1 -and $fields.test) {
            $outcome = switch ($code) { 0 { 'PASS' } -3 { 'DEFERRED' } -4 { 'DEFERRED' } default { 'FAIL' } }
            $reason = ''
            if ($fields.stack) { $reason = (($fields.stack -split "`n")[0] -replace '^[\w.$]+(Exception|Error): ', '').Trim() }
            $results.Add([pscustomobject]@{ Test = $fields.test; Result = $outcome; Detail = $reason })
        }
        $fields = @{}; $lastKey = $null
    }
    elseif ($line -notmatch '^INSTRUMENTATION_' -and $lastKey) { $fields[$lastKey] += "`n$line" }
}

$crash = $raw | Where-Object { $_ -match 'INSTRUMENTATION_RESULT: shortMsg=' -or $_ -match 'INSTRUMENTATION_FAILED' }
$results | Format-Table -AutoSize -Wrap | Out-String -Width 200 | Write-Host
$pass = @($results | Where-Object Result -eq 'PASS').Count
$fail = @($results | Where-Object Result -eq 'FAIL').Count
$deferred = @($results | Where-Object Result -eq 'DEFERRED').Count
Write-Host "Passed $pass, failed $fail, deferred $deferred. Full output: $logFile"
if ($crash) { Write-Host "Run aborted: $($crash -join ' ')" -ForegroundColor Red; exit 2 }
if ($results.Count -eq 0) { Write-Host 'No test results; see the log.' -ForegroundColor Red; exit 2 }
if ($fail -gt 0) { exit 1 }
