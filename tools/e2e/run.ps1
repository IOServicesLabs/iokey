<#
.SYNOPSIS
    Runs the keyboard's end-to-end tests on the test emulator, against whatever build of the app is installed.

.DESCRIPTION
    Only ever talks to the emulator -Device names. It installs the two test APKs and never installs, rebuilds or replaces the
    app under test, so install the build you want to test first. That build must be debuggable, because tests
    seed learned data with run-as.

    The tests change rotation, display size, navigation mode and theme while they run. The tests put these back
    themselves, and this script does so again at the end in case a run was cut short.

    A red test is a result, not a failure of the run. The exit code says what happened:
      0  the suite ran and nothing is wrong: no reds, or with -Baseline no regressions against the baseline
      1  the suite ran, and there are reds, or with -Baseline regressions, or with -FailOnShortShows a short show
      2  the suite could not run or did not finish

    A test on the unstable list below is reported whatever it does, and never decides the exit code.

.PARAMETER ResultsPath
    Where to write the results as JSON. The raw instrumentation output goes next to it as <name>.raw.txt and the
    screenshots and state saved by failing tests go in <name>-failures\. Besides each test's result, the JSON holds
    shortShows (shows that came up short and were repaired), recorded (numbers and notes tests left, by test) and
    swallowedErrors (exceptions the keyboard caught and lived through, which fail no test).

.PARAMETER AppPackage
    The installed build to test.

.PARAMETER Class
    Test classes to run, by simple name, optionally with #method, such as
    -Class InsetsTest,AutocorrectTest#retypeAfterUndo_stays. The default is the whole suite.

.PARAMETER SkipBuild
    Do not build. By default the test APKs are built when one is missing or older than anything under e2e\.

.PARAMETER Baseline
    An earlier results file to compare with. Lists what regressed, what was fixed, tests new since then and recorded
    numbers that changed. Warns when that file looks wrong for the comparison: another harness, the same app build,
    no record of either, or a run that did not finish.

.PARAMETER FailOnShortShows
    Exit 1 if any show came up short, even though the test repaired it and passed. For runs of a build that is meant
    to have no short shows at all.

.PARAMETER Device
    The emulator to talk to, as adb names it. The default is emulator-5554.

.EXAMPLE
    pwsh -NoProfile -File tools\e2e\run.ps1 C:\temp\results.json

.EXAMPLE
    pwsh -NoProfile -File tools\e2e\run.ps1 C:\temp\results.json -Class KeysTest -Baseline C:\temp\baseline.json
#>
#Requires -Version 7

param(
    [Parameter(Mandatory = $true, Position = 0)][string]$ResultsPath,
    [string]$AppPackage = 'io.ioservices.iokey.full.debug',
    [string[]]$Class,
    [switch]$SkipBuild,
    [string]$Baseline,
    [switch]$FailOnShortShows,
    [string]$Device = 'emulator-5554'
)

$ErrorActionPreference = 'Stop'

# adb hands over bytes, and the keyboard's crash log is UTF-8.
try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { Write-Verbose "Keeping the console's encoding: $_" }

$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gradle = Join-Path $repo 'gradlew.bat'
$testPackage = 'io.ioservices.iokey.e2e'
$runner = "$testPackage.test/androidx.test.runner.AndroidJUnitRunner"
$hostApk = Join-Path $repo 'e2e\build\outputs\apk\debug\e2e-debug.apk'
$testApk = Join-Path $repo 'e2e\build\outputs\apk\androidTest\debug\e2e-debug-androidTest.apk'
$deviceFiles = "/sdcard/Android/data/$testPackage/files"
$deviceFailures = "$deviceFiles/failures"
$deviceShortShows = "$deviceFiles/short-shows.txt"
$deviceRecorded = "$deviceFiles/recorded.txt"
$deviceSwallowedErrors = "$deviceFiles/swallowed-errors.txt"
$appCrashLog = 'files/last_crash.txt'
$swallowedMark = 'keyboard kept running'
$suite = 'InsetsTest', 'InsetsRecreatedWindowTest', 'MeasureCacheTest', 'EmojiRowRecreationTest', 'WindowMeasurementsTest', 'AutocorrectTest', 'KeysTest', 'OneHandedKeysTest', 'PrefsMigrationTest', 'TypingTest', 'SwipeTest', 'LearningTest', 'StorageFailureTest', 'LlmTest', 'DraftNoticeTest', 'KeyboardDialogTest', 'EditorTrackingTest', 'FieldReseedTest', 'TouchGestureTest', 'BarTest', 'PhoneNumbersTest', 'ShowTapTest'

# These have changed result between runs of the same build with no cause found. A change in one is reported as
# unstable, and is a regression only if the test goes red in repeated runs of it alone.
$unstableTests = @(
    'SwipeTest.lastLetterCounts_later'
    'SwipeTest.lastLetterCounts_there'
)

function Stop-WithError([string]$message) {
    Write-Output "e2e: $message"
    exit 2
}

function Invoke-Adb {
    & $adb -s $device @args
}

function Get-AdbText {
    (Invoke-Adb @args 2>&1 | Out-String).Trim()
}

# Puts back whatever a run cut short may have left changed. Each of these does nothing when it is already right.
function Reset-Device {
    Invoke-Adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS 2>&1 | Out-Null
    Invoke-Adb shell settings put system accelerometer_rotation 0 2>&1 | Out-Null
    Invoke-Adb shell settings put system user_rotation 0 2>&1 | Out-Null
    Invoke-Adb shell wm size reset 2>&1 | Out-Null
    Invoke-Adb shell wm density reset 2>&1 | Out-Null
    Invoke-Adb shell cmd uimode night no 2>&1 | Out-Null
    Invoke-Adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural 2>&1 | Out-Null
}

function Test-BuildNeeded {
    if (-not ((Test-Path -LiteralPath $hostApk) -and (Test-Path -LiteralPath $testApk))) { return $true }
    $built = @((Get-Item -LiteralPath $hostApk).LastWriteTimeUtc, (Get-Item -LiteralPath $testApk).LastWriteTimeUtc) |
        Sort-Object | Select-Object -First 1
    $newest = Get-ChildItem -LiteralPath (Join-Path $repo 'e2e') -Recurse -File |
        Where-Object { $_.FullName -notlike '*\build\*' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    return $newest.LastWriteTimeUtc -gt $built
}

function Get-TestClasses {
    $names = if ($Class) { $Class | ForEach-Object { $_ -split ',' } | Where-Object { $_ } } else { $suite }
    ($names | ForEach-Object { "$testPackage.$($_.Trim())" }) -join ','
}

# The instrumentation's -r stream: each test reports a start (status 1), then an end whose status is its result,
# with the failure in a stack value that runs over several lines until the next INSTRUMENTATION_ line.
function Read-InstrumentationOutput([string[]]$lines) {
    $tests = [ordered]@{}
    $current = @{}
    $inStack = $false
    $started = @{}
    $finishedNormally = $false
    $shortMessage = $null

    foreach ($line in $lines) {
        if ($line -match '^INSTRUMENTATION_STATUS: (\w+)=(.*)$') {
            $inStack = $Matches[1] -eq 'stack'
            $current[$Matches[1]] = $Matches[2]
            continue
        }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)') {
            $inStack = $false
            $code = [int]$Matches[1]
            $name = "$(($current['class'] -split '\.')[-1]).$($current['test'])"
            if ($code -eq 1) {
                $started[$name] = [DateTime]::UtcNow
            } else {
                $entry = [ordered]@{}
                switch ($code) {
                    0 { $entry.result = 'pass' }
                    -3 { $entry.result = 'ignored' }
                    -4 { $entry.result = 'ignored'; $entry.message = 'A test assumption did not hold' }
                    default {
                        $entry.result = 'fail'
                        # The message is whatever comes before the stack frames.
                        $stack = "$($current['stack'])" -split "`n"
                        $message = ($stack | Where-Object { $_ -notmatch '^\s+at ' -and $_ -notmatch '^\s*\.\.\.' -and $_.Trim() }) -join "`n"
                        $entry.message = $message.Trim()
                    }
                }
                $tests[$name] = $entry
                $started.Remove($name)
            }
            $current = @{}
            continue
        }
        if ($line -match '^INSTRUMENTATION_RESULT: shortMsg=(.*)$') { $shortMessage = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_CODE: (-?\d+)') { $finishedNormally = -not $shortMessage }
        if ($line -match '^INSTRUMENTATION_') { $inStack = $false; continue }
        if ($inStack) { $current['stack'] = "$($current['stack'])`n$line" }
    }

    # A test that started and never ended is what a crash of the test process looks like.
    foreach ($name in $started.Keys) {
        $tests[$name] = [ordered]@{ result = 'fail'; message = "The run ended during this test. $shortMessage".Trim() }
    }
    [pscustomobject]@{ Tests = $tests; Finished = $finishedNormally; ShortMessage = $shortMessage }
}

# The lines of a file the tests left on the device, or nothing when they left none.
function Get-DeviceFileLines([string]$path) {
    if ($deviceLost) { return }
    @((Get-AdbText shell "cat $path 2>/dev/null") -split '\r?\n' | Where-Object { $_.Trim() })
}

# Numbers and notes the tests left, as test, name and value separated by tabs. A passing test has no message, so
# this is the only way it can say how well it passed.
function Read-RecordedValues {
    $recorded = [ordered]@{}
    foreach ($line in Get-DeviceFileLines $deviceRecorded) {
        $test, $name, $value = $line -split "`t", 3
        if (-not $name) { continue }
        if (-not $recorded.Contains($test)) { $recorded[$test] = [ordered]@{} }
        $number = 0.0
        $isNumber = [double]::TryParse($value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number)
        $recorded[$test][$name] = if ($isNumber) { $number } else { $value }
    }
    $recorded
}

# Exceptions the keyboard caught, logged and carried on from, so that no test fails over them. The tests collect the
# entries as they go, because the wipe most of them start with erases the log, and anything still in it came later.
function Read-SwallowedErrors {
    $errors = [System.Collections.Generic.List[object]]::new()
    foreach ($line in Get-DeviceFileLines $deviceSwallowedErrors) {
        $test, $entry = $line -split "`t", 2
        $lines = "$entry" -replace '\\n', "`n"
        $errors.Add([ordered]@{ test = $test; entry = $lines })
    }
    $log = Get-AdbText shell run-as $AppPackage cat $appCrashLog
    if ($deviceLost) { return $errors }
    foreach ($entry in [regex]::Split($log, '(?m)^(?=\S{3,12} IOKey )')) {
        if ($entry.Contains($swallowedMark)) { $errors.Add([ordered]@{ test = 'after the last test'; entry = $entry.Trim() }) }
    }
    $errors
}

# Says what could make the comparison mean something other than it seems to.
function Write-BaselineWarnings($baseline, $new) {
    $warnings = [System.Collections.Generic.List[string]]::new()
    if (-not $baseline.harnessApkSha256) {
        $warnings.Add('It does not say which harness recorded it, so it may come from an older set of tests.')
    } elseif ($baseline.harnessApkSha256 -ne $harnessSha) {
        $warnings.Add("It was recorded with another harness, $($baseline.harnessApkSha256), and this run used $harnessSha. A test of the same name may now ask something else.")
    }
    if ($new.Count -gt 0) {
        $warnings.Add("$($new.Count) tests of this run are not in it, which is what a baseline from an older harness looks like.")
    }
    if (-not $baseline.appApkSha256) {
        $warnings.Add('It does not say which app build it tested.')
    } elseif ($baseline.appApkSha256 -eq $apkHash) {
        $warnings.Add("It tested this same app build, $apkHash, so any difference is the tests or chance and not the app.")
    }
    if ($baseline.completed -eq $false) {
        $warnings.Add('That run did not finish, so tests it never reached will not show as regressions.')
    }
    if ($baseline.deviceSdk -and $baseline.deviceSdk -ne $results.deviceSdk) {
        $warnings.Add("It ran on API $($baseline.deviceSdk) and this run on API $($results.deviceSdk).")
    }
    foreach ($warning in $warnings) { Write-Host "  WARNING: $warning" }
}

# Recorded numbers a pass or fail would hide, such as a count of words that landed.
function Write-RecordedChanges($baseline) {
    if (-not $baseline.recorded) { return }
    $changes = [System.Collections.Generic.List[string]]::new()
    foreach ($test in $recorded.Keys) {
        if (-not $baseline.recorded.Contains($test)) { continue }
        foreach ($name in $recorded[$test].Keys) {
            $was = $baseline.recorded[$test][$name]
            $is = $recorded[$test][$name]
            if ($null -ne $was -and $is -is [double] -and $was -ne $is) { $changes.Add("$test $name : $was to $is") }
        }
    }
    Write-Host "  Recorded numbers that changed: $($changes.Count)"
    $changes | ForEach-Object { Write-Host "    $_" }
}

# Prints how this run differs from an earlier one, and returns the number of regressions.
function Compare-WithBaseline($now, [string]$baselinePath) {
    $baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json -AsHashtable
    $before = $baseline.tests
    $changed = @($now.Keys | Where-Object { $before.Contains($_) -and $before[$_].result -ne $now[$_].result })
    $unstable = @($changed | Where-Object { $_ -in $unstableTests })
    $counted = @($changed | Where-Object { $_ -notin $unstableTests })
    $regressed = @($counted | Where-Object { $before[$_].result -eq 'pass' -and $now[$_].result -eq 'fail' })
    $fixed = @($counted | Where-Object { $before[$_].result -eq 'fail' -and $now[$_].result -eq 'pass' })
    $new = @($now.Keys | Where-Object { -not $before.Contains($_) })
    $missing = @($before.Keys | Where-Object { -not $now.Contains($_) })
    Write-Host ''
    Write-Host "Compared with $baselinePath"
    Write-Host "  Its app build: $($baseline.appApkSha256), this run's: $apkHash"
    Write-BaselineWarnings $baseline $new
    Write-Host "  Regressions, green there and red now: $($regressed.Count)"
    $regressed | ForEach-Object { Write-Host "    $_"; Write-Host "      $(($now[$_].message -split "`n")[0])" }
    Write-Host "  Fixed, red there and green now: $($fixed.Count)"
    $fixed | ForEach-Object { Write-Host "    $_" }
    Write-Host "  Unstable tests that changed, not counted either way: $($unstable.Count)"
    $unstable | ForEach-Object { Write-Host "    $_ : $($before[$_].result) to $($now[$_].result)" }
    Write-Host "  New tests: $($new.Count)"
    $new | ForEach-Object { Write-Host "    $_ : $($now[$_].result)" }
    if ($missing.Count -gt 0 -and -not $Class) {
        Write-Host "  In the baseline but not run here: $($missing.Count)"
        $missing | ForEach-Object { Write-Host "    $_" }
    }
    Write-RecordedChanges $baseline
    return $regressed.Count
}

# ---------------------------------------------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $adb)) { Stop-WithError "adb not found at $adb" }
if ((Get-AdbText get-state) -ne 'device') {
    Stop-WithError "$device is not running. Start the emulator first."
}
if ($Baseline -and -not (Test-Path -LiteralPath $Baseline)) { Stop-WithError "Baseline not found: $Baseline" }

$appPath = Get-AdbText shell pm path $AppPackage
if ($appPath -notmatch '^package:(.+)$') { Stop-WithError "$AppPackage is not installed on $device. Install the build to test first." }
$appPath = ($appPath -split "`n")[0].Substring('package:'.Length).Trim()
if ((Get-AdbText shell run-as $AppPackage id) -notmatch '^uid=') { Stop-WithError "$AppPackage is not debuggable, and tests need run-as to seed its data." }

if (-not $SkipBuild -and (Test-BuildNeeded)) {
    Write-Output 'Building the test APKs...'
    & $gradle -p $repo ':e2e:assembleDebug' ':e2e:assembleDebugAndroidTest'
    if ($LASTEXITCODE -ne 0) { Stop-WithError 'Building the test APKs failed.' }
}
if (-not ((Test-Path -LiteralPath $hostApk) -and (Test-Path -LiteralPath $testApk))) { Stop-WithError 'The test APKs are not built.' }

Write-Output 'Installing the test APKs...'
foreach ($apk in $hostApk, $testApk) {
    $out = Get-AdbText install -r -t $apk
    if ($out -notmatch 'Success') { Stop-WithError "Could not install ${apk}: $out" }
}
Invoke-Adb shell rm -rf $deviceFailures $deviceShortShows $deviceRecorded $deviceSwallowedErrors 2>&1 | Out-Null
# What an earlier run or a hand on the device left in the log is not this run's to report.
Invoke-Adb shell run-as $AppPackage rm -f $appCrashLog 2>&1 | Out-Null
$harnessSha = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()

$resultsFull = [IO.Path]::GetFullPath($ResultsPath)
$resultsDir = Split-Path -Parent $resultsFull
$resultsBase = Join-Path $resultsDir ([IO.Path]::GetFileNameWithoutExtension($resultsFull))
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$rawPath = "$resultsBase.raw.txt"
$failuresDir = "$resultsBase-failures"

$classes = Get-TestClasses
$started = Get-Date
Write-Output "Testing $AppPackage on $device"
Write-Output "Classes: $classes"

$rawLines = [System.Collections.Generic.List[string]]::new()
try {
    # Progress as it goes, because a full run takes the better part of an hour.
    Invoke-Adb shell am instrument -w -r -e imePackage $AppPackage -e class $classes $runner 2>&1 | ForEach-Object {
        $line = "$_"
        $rawLines.Add($line)
        if ($line -match '^INSTRUMENTATION_STATUS: test=(.*)$') { $script:lastTest = $Matches[1] }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)' -and [int]$Matches[1] -ne 1) {
            $mark = if ([int]$Matches[1] -eq 0) { 'pass' } else { 'FAIL' }
            Write-Output ("  {0}  {1}" -f $mark, $script:lastTest)
        }
    }
} finally {
    $rawLines | Set-Content -LiteralPath $rawPath -Encoding utf8
    Reset-Device
}
$finished = Get-Date

# An emulator can die during a run. What its tests said until then is still worth having, so go on without it.
$deviceLost = (Get-AdbText get-state) -ne 'device'
if ($deviceLost) { Write-Output "$device went away during the run. The results stop where it did." }

$parsed = Read-InstrumentationOutput $rawLines
$tests = $parsed.Tests
$counts = [ordered]@{
    total   = $tests.Count
    passed  = @($tests.Values | Where-Object { $_.result -eq 'pass' }).Count
    failed  = @($tests.Values | Where-Object { $_.result -eq 'fail' }).Count
    ignored = @($tests.Values | Where-Object { $_.result -eq 'ignored' }).Count
}

# Tests that are not about the window repair a short one and carry on, so only this says it happened.
$shortShows = @(Get-DeviceFileLines $deviceShortShows)
$recorded = Read-RecordedValues
$swallowedErrors = @(Read-SwallowedErrors)

$apkHash = if ($deviceLost) { $null } else { ((Get-AdbText shell sha256sum $appPath) -split '\s+')[0] }
$versionLine = Get-AdbText shell dumpsys package $AppPackage | Select-String -Pattern 'versionName=(\S+)' | Select-Object -First 1
$results = [ordered]@{
    appPackage = $AppPackage
    appVersion = if ($versionLine) { $versionLine.Matches[0].Groups[1].Value } else { $null }
    appApkSha256 = $apkHash
    harnessApkSha256 = $harnessSha
    device = $device
    deviceSdk = if ($deviceLost) { $null } else { Get-AdbText shell getprop ro.build.version.sdk }
    classes = $classes
    startedAt = $started.ToString('o')
    finishedAt = $finished.ToString('o')
    minutes = [math]::Round(($finished - $started).TotalMinutes, 1)
    completed = $parsed.Finished
    summary = $counts
    shortShows = $shortShows
    recorded = $recorded
    swallowedErrors = $swallowedErrors
    tests = $tests
}
$results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resultsFull -Encoding utf8

if (Test-Path -LiteralPath $failuresDir) { Remove-Item -LiteralPath $failuresDir -Recurse -Force }
if ($counts.failed -gt 0) {
    New-Item -ItemType Directory -Force -Path $failuresDir | Out-Null
    Invoke-Adb pull "$deviceFailures/." $failuresDir 2>&1 | Out-Null
}

Write-Output ''
Write-Output "$($counts.total) tests: $($counts.passed) passed, $($counts.failed) failed, $($counts.ignored) ignored, in $($results.minutes) minutes"
Write-Output "Results: $resultsFull"
if ($counts.failed -gt 0) { Write-Output "Failure evidence: $failuresDir" }
if ($shortShows.Count -gt 0) {
    Write-Output "The keyboard came up short with no trigger $($shortShows.Count) time(s), and those tests repaired it and went on:"
    $shortShows | ForEach-Object { Write-Output "  $_" }
}
if ($swallowedErrors.Count -gt 0) {
    Write-Output "The keyboard caught and lived through $($swallowedErrors.Count) exception(s), which fail no test:"
    foreach ($swallowed in $swallowedErrors) {
        Write-Output "  during $($swallowed.test)"
        $swallowed.entry -split "`n" | ForEach-Object { Write-Output "    $_" }
    }
}
$reds = @($tests.Keys | Where-Object { $tests[$_].result -eq 'fail' })
$unstableReds = @($reds | Where-Object { $_ -in $unstableTests })
if ($unstableReds.Count -gt 0) { Write-Output "Red and known to be unstable, so not counted: $($unstableReds -join ', ')" }

$regressions = if ($Baseline) { Compare-WithBaseline $tests $Baseline } else { 0 }

if (-not $parsed.Finished) {
    Write-Output "The run did not finish: $($parsed.ShortMessage)"
    exit 2
}
if ($FailOnShortShows -and $shortShows.Count -gt 0) {
    Write-Output "Failing the run because of -FailOnShortShows: $($shortShows.Count) short show(s)."
    exit 1
}
if ($Baseline) { exit $(if ($regressions -gt 0) { 1 } else { 0 }) }
exit $(if ($reds.Count -gt $unstableReds.Count) { 1 } else { 0 })
