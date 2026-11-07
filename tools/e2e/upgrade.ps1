<#
.SYNOPSIS
    Runs the upgrade from an older build to the new one on the test emulator, with real data and no wipe in between.

.DESCRIPTION
    Only ever talks to emulator-5554, and holds the emulator queue for the whole run. In order:

      1. Installs the new build on cleared data and records where it draws its keys with nothing stored.
      2. Installs the older build on cleared data. UpgradeSeedTest types through the keyboard so that build writes its
         real encrypted store, and seeds the settings it would hold. Saves the store and settings as they then are.
      3. Installs the new build over it, without clearing, with the older build running as it would on a phone.
         With -KillDuringFirstStart, kills the new process as it comes up, several times. That is a process being killed, which
         is not the phone losing power: the kernel keeps what the process had written, so a power loss stays untested. After
         each kill it first waits to see whether the system restarts the process, then taps the field as a person would.
      4. Reads the store and settings after the first start, kills the process, and reads them again, with nothing
         typed in between. Nothing may differ, or something ran twice.
      5. UpgradeVerifyTest asks what a person would see. Then the store is read a last time.
      6. UpgradeControlTest types the same words on cleared data with no upgrade in the way, so that a word that does not
         complete after the upgrade can be told from one that would not have anyway.

    The encrypted store is read here too, not only through the keyboard: the passphrase comes from a heap dump of the
    keyboard's process, and an embedded Python script decrypts a copy of the store on this machine. That needs Python
    with pycryptodome. All text in the store is invented by the tests. The dump is deleted once the key is found.

    Build the APKs first. This script never builds, so that it can never get ahead of a queue around the build.

    Outputs in -OutDir: seed.json and verify.json from tools/e2e/run.ps1, one folder per read (before, first-start,
    after-kill, end), each holding the files and store.json, and upgrade-report.txt. -OutDir must not hold an earlier run,
    because a rerun would read that run's kill copies as its own.

    At the end the new build stays installed with its data cleared, unless -KeepData, and the emulator's CPUs are
    all given back.

    Exit codes: 0 every check held. 1 a check failed. 2 it could not run. 3 the emulator queue stayed busy. 4 the emulator
    was down and -EmuStart could not start it.

.PARAMETER OldApk
    The older build to upgrade from. It must be debuggable, signed like the new one, and of the same package.

.PARAMETER NewApk
    The build under test. By default the full debug APK in this repo's build folder.

.PARAMETER EmulatorCpus
    Limit the emulator to this many of the host's CPUs from just before the new build goes on. The first show after a
    new process is a race that CPU contention brings out, so a limit makes that check count for more.

.PARAMETER KillDuringFirstStart
    After the new build goes on, kill its process once for each of -KillDelays. Everything after that is asked as usual.
    A delay is seconds after the process shows, or the moment one of the steps of a first start touches its file: prefs
    when the settings are rewritten, shm when the store is opened, wal when the store's write-ahead log changes, and main
    when the main store file moves, which is the fold after the migration's commit. A step can take seconds to wait after
    that, as in wal+0.01, which is the way to land between the migration and the one-time step that follows it. Those are
    skipped once the first start has finished. kill-series.json holds what each kill left and what brought the process back.

.PARAMETER Required
    Kill steps that must land or the run fails, added to prefs, shm and wal on a grown store. Only main needs this. On a
    grown store the migration's commit is what folds the log in, so it lands. On a small store nothing moves the main file
    until the keyboard hides, which no kill series does, so main counts as not landed and fails nothing unless named here.

.PARAMETER NoHelpSeconds
    After a kill, how long to leave the system to restart the process by itself. Then the field is tapped, then if that
    does nothing the selection is changed. A step with a wait of its own gets 15 seconds, as the wait is not its point.

.PARAMETER ControlOnly
    Only step 6, on -ControlApk, by default the new build. -NoControl leaves it out.

.PARAMETER VerifyClass
    What step 5 runs, in the form tools\e2e\run.ps1 takes for -Class. By default all of UpgradeVerifyTest.

.PARAMETER Shape
    Where the older build leaves the store's rows. Small is the seed alone, a small store wholly in the log. B has grown rows
    in the main file and the last typed words only in the log. A has everything already folded into the main file. A and B
    grow the store through the older build's own text expansion (UpgradeGrowTest), which takes the emulator 15 minutes or so.

.PARAMETER StopAfterSeed
    End after the store has been read on the older build and its checks made, with no upgrade.

.PARAMETER GrowRowFloor
    On A and B, the fewest rows the older build may have written.

.PARAMETER EmuStart
    Path of a script that starts the emulator. Given, the script restarts an emulator that has grown too large and starts one
    that is down, inside the queue's lock. Not given, an emulator that is down stops the run.

.PARAMETER SelfCheck
    A saved snapshot folder. Needs no device: cuts a copy of its store so that it cannot be read, and exits 0 only if the
    read of that copy gave a failing check. -OldApk is still mandatory, and any text will do.

.PARAMETER CompareWith
    Another run's -OutDir. Needs no device: compares the rows of both runs' first starts and fails on any row gone, new or
    changed, apart from lastUsed and the words -TypeDuringFirstStart types, and on two builds. It refuses a run against
    itself. -OldApk is still mandatory, and any text will do.

.PARAMETER KillWatchSeconds
    How long each kill's watcher waits for its file to move before it kills anyway and says it did not land. About 300 on a
    grown store, where the steps of a first start come much later.

.PARAMETER TypeDuringFirstStart
    Type while the first start is still going, as a person does right after an update. Just before the first show on a plain
    run, and straight after the last kill's bring back on a kill run. Once the store is quiet the same keys are typed again,
    as a baseline under the same CPU limit. A kill that landed at main ends the first start's commit before the typing, so on
    that run the words are not typed during a first start, and the plain run is the one that asks it.

.PARAMETER BringBackRoutes
    With -KillDuringFirstStart: what to try, in order, for kills at 0 or prefs after -NoHelpSeconds. focus taps the single
    field, appswitch goes home and back with no tap, and selection changes the selected keyboard. Each route gets its own
    kill, and only 30 s later comes the tap and then the selection. kill-series.json records what brought the process back.

.EXAMPLE
    pwsh -NoProfile -File tools\e2e\upgrade.ps1 -OutDir C:\temp\upgrade -OldApk C:\temp\old.apk -EmulatorCpus 4

.EXAMPLE
    pwsh -NoProfile -File tools\e2e\upgrade.ps1 -OutDir C:\temp\grown -OldApk C:\temp\old.apk -Shape A -KillDuringFirstStart -NoControl -NoHelpSeconds 20 -KillWatchSeconds 300 -TypeDuringFirstStart -EmulatorCpus 4 -KillDelays 0,prefs,shm,wal,wal+1,wal+3,wal+8,wal+20
#>
#Requires -Version 7

param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$OldApk,
    [string]$OldSha256,
    [string]$NewApk,
    [string]$AppPackage = 'io.ioservices.iokey.full.debug',
    [switch]$KillDuringFirstStart,
    [switch]$ControlOnly,
    [switch]$NoControl,
    [string]$VerifyClass = 'UpgradeVerifyTest',
    [string]$ControlApk,
    [string[]]$KillDelays = @('0', 'prefs', 'shm', 'wal', 'wal+0.01', 'wal+0.03'),
    [int]$NoHelpSeconds = 120,
    [int]$EmulatorCpus = 0,
    [int]$WaitMinutes = 25,
    [switch]$KeepData,
    [string]$Python = 'python',
    [ValidateSet('Small', 'A', 'B')][string]$Shape = 'Small',
    [switch]$StopAfterSeed,
    [int]$GrowRowFloor = 90000,
    [string]$EmuStart,
    [string]$SelfCheck,
    [string]$CompareWith,
    [int]$KillWatchSeconds = 30,
    [switch]$TypeDuringFirstStart,
    [string[]]$BringBackRoutes = @(),
    [string[]]$Required = @()
)

try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { Write-Verbose "Keeping the console's encoding: $_" }

$device = 'emulator-5554'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $NewApk) { $NewApk = Join-Path $repo 'app\build\outputs\apk\full\debug\app-full-debug.apk' }
$runner = Join-Path $PSScriptRoot 'run.ps1'
$storeDb = 'databases/localboard.db'
$prefsFile = 'shared_prefs/localboard_prefs.xml'
$keystorePrefs = 'shared_prefs/localboard_keystore.xml'
$crashLog = 'files/last_crash.txt'
$survivedMark = 'keyboard kept running'
# How many times the keys typed during a first start may take what they take at rest before a run fails. Wide on purpose: one
# stall of the emulator must not go red, and every key held up by the first start must. A key takes about 240 ms at rest on the
# emulator under 4 CPUs, so this asks for about 1.4 s on every key, or two stalls of 5 s would pass. The figures are in the report.
$typingLagLimit = 6
# How much a word may grow through UpgradeVerifyTest. It has added 8 or 9 on every saved run, and a count of 7 or more that
# doubled on top of that comes out past this.
$mostGrowthThroughVerify = 14
$remoteHprof = '/data/local/tmp/iokey-upgrade.hprof'
$remoteWatch = '/data/local/tmp/iokey-killwatch.sh'
$remotePoll = '/data/local/tmp/iokey-storepoll.sh'
$remotePollStop = '/data/local/tmp/iokey-storepoll.stop'
$keyboardService = 'com.localboard.keyboard.ime.LLMKeyboardService'
$hostActivity = 'io.ioservices.iokey.e2e/.HostActivity'

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$reportPath = Join-Path $OutDir 'upgrade-report.txt'
$readStore = Join-Path $OutDir 'read_store.py'
$passFile = Join-Path $OutDir 'store-key.hex'
$checks = [System.Collections.Generic.List[object]]::new()
$deathsSeen = @{}
$killedPids = @{}
$apkHashes = @{}
$Required = @($Required | ForEach-Object { $_ -split ',' } | Where-Object { $_ })

function Say([string]$text) {
    $line = "$(Get-Date -Format 'HH:mm:ss') $text"
    Write-Host $line
    Add-Content -LiteralPath $reportPath -Value $line -Encoding utf8
}

function Note-Check([string]$name, [bool]$passed, [string]$evidence) {
    $checks.Add([ordered]@{ check = $name; passed = $passed; evidence = $evidence })
    Say ("{0} {1}: {2}" -f $(if ($passed) { 'HELD' } else { 'FAILED' }), $name, $evidence)
}

function Invoke-Adb { & $adb -s $device @args }

function Get-AdbText { (Invoke-Adb @args 2>&1 | Out-String).Trim() }

function Invoke-RunAs([string]$script) { Get-AdbText shell "run-as $AppPackage sh -c '$script'" }

function Get-KeyboardPid { (Get-AdbText shell "pidof $AppPackage") -replace '\s.*$', '' }

# Limits the emulator process to the first $count logical CPUs. 0 gives it all of them back.
function Set-EmulatorCpus([int]$count) {
    $qemu = Get-Process -Name 'qemu-system-x86_64*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $qemu) { return }
    $cpus = if ($count -gt 0) { $count } else { [Environment]::ProcessorCount }
    $qemu.ProcessorAffinity = [IntPtr]((([int64]1) -shl $cpus) - 1)
}

# The report names each build by the hash it had when the run started, so a file that changed since would make it name the wrong one.
function Assert-BuildUnchanged([string]$apk) {
    if (-not $script:apkHashes.ContainsKey($apk)) { return }
    $now = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($now -ne $script:apkHashes[$apk]) { throw "$apk changed during this run: it was $($script:apkHashes[$apk]) and is now $now." }
}

function Install-Build([string]$apk, [string[]]$flags) {
    Assert-BuildUnchanged $apk
    $out = Get-AdbText install @flags $apk
    if ($out -notmatch 'Success') { throw "Could not install ${apk}: $out" }
    Say "Installed $(Split-Path -Leaf $apk) with $($flags -join ' ')"
}

function Clear-App {
    $out = Get-AdbText shell pm clear $AppPackage
    if ($out -notmatch 'Success') { throw "pm clear failed: $out" }
}

# One run of the test runner. Nothing pipes it, because an adb server forked mid-run once held a pipe open and hung it.
function Invoke-Tests([string]$name, [string]$classes) {
    $json = Join-Path $OutDir "$name.json"
    $out = Join-Path $OutDir "$name.out.txt"
    $arguments = @('-NoProfile', '-File', "`"$runner`"", "`"$json`"", '-SkipBuild', '-AppPackage', $AppPackage, '-Class', $classes)
    $process = Start-Process -FilePath 'pwsh' -ArgumentList $arguments -RedirectStandardOutput $out -RedirectStandardError "$out.err" -NoNewWindow -PassThru -Wait
    Say "$name ($classes): runner exit code $($process.ExitCode)"
    Get-Content -LiteralPath $out | Select-Object -Last 25 | ForEach-Object { Say "  $_" }
    return $process.ExitCode
}

# Bytes as they are on the device. The redirect is the child's own handle, so nothing here can alter them.
function Save-DeviceFile([string]$remote, [string]$local) {
    Start-Process -FilePath $adb -ArgumentList @('-s', $device, 'exec-out', 'run-as', $AppPackage, 'cat', $remote) `
        -RedirectStandardOutput $local -RedirectStandardError "$local.err" -NoNewWindow -Wait
    Remove-Item -LiteralPath "$local.err" -ErrorAction SilentlyContinue
    $item = Get-Item -LiteralPath $local -ErrorAction SilentlyContinue
    if ($item -and $item.Length -lt 200 -and ((Get-Content -LiteralPath $local -Raw) -match '^(cat|run-as): ')) { Remove-Item -LiteralPath $local }
}

function Read-Store([string]$dir) {
    if (-not (Test-Path -LiteralPath $passFile)) { return $null }
    Remove-Item -LiteralPath (Join-Path $dir 'store.json') -ErrorAction SilentlyContinue
    $output = & $Python $readStore read --dir $dir --keyfile $passFile 2>&1 | Out-String
    Set-Content -LiteralPath (Join-Path $dir 'store.txt') -Value $output -Encoding utf8
    if (Test-Path -LiteralPath (Join-Path $dir 'store.json')) { return Get-Content -LiteralPath (Join-Path $dir 'store.json') -Raw | ConvertFrom-Json -AsHashtable }
    Say "Could not read the store in ${dir}: $output"
    return $null
}

# A read that fails must fail a check. Without that, every check that needs the store would vanish and the run pass.
function Read-StoreOrFail([string]$dir, [string]$what) {
    $store = Read-Store $dir
    if (-not $store) { Note-Check "The store could be read $what" $false "see the report, and store.txt in $dir" }
    return $store
}

function Get-MainOnly([string]$dir) {
    $output = & $Python $readStore read --main-only --dir $dir --keyfile $passFile 2>&1 | Out-String
    Say "Main file of $(Split-Path -Leaf $dir): $($output.Trim())"
    $file = Join-Path $dir 'main-only.json'
    if (Test-Path -LiteralPath $file) { return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json -AsHashtable }
    return $null
}

# A row list as evidence, cut short, because a grown store can hold hundreds of rows for one word.
function Show-Rows($rows) {
    $all = @($rows)
    if ($all.Count -le 12) { return ($all | ConvertTo-Json -Compress) }
    return "$(($all | Select-Object -First 12) | ConvertTo-Json -Compress) and $($all.Count - 12) more"
}

# The copies of a grown store are large, and the store.json and rows.tsv read from them hold what the checks need.
function Remove-StoreCopies([string]$dir) {
    if ($Shape -eq 'Small') { return }
    foreach ($leaf in 'localboard.db', 'localboard.db-wal', 'localboard.db-shm') { Remove-Item -LiteralPath (Join-Path $dir $leaf) -ErrorAction SilentlyContinue }
}

function Get-StoreListing { Invoke-RunAs 'stat -c %n:inode=%i:size=%s:mtime=%y databases/* shared_prefs/* files/* 2>&1' }

# A copy of the store and settings as they are now. Only reads, so it changes nothing about what follows. A store that
# moved during the copy is not one state of the store, so it goes once more and then says so.
function Save-Snapshot([string]$name) {
    $dir = Join-Path $OutDir $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    for ($try = 1; $try -le 2; $try++) {
        $listing = Get-StoreListing
        Set-Content -LiteralPath (Join-Path $dir 'listing.txt') -Value $listing -Encoding utf8
        foreach ($file in $storeDb, "$storeDb-wal", "$storeDb-shm") { Save-DeviceFile $file (Join-Path $dir (Split-Path -Leaf $file)) }
        foreach ($file in $prefsFile, "$prefsFile.bak", $keystorePrefs, $crashLog) { Save-DeviceFile $file (Join-Path $dir (Split-Path -Leaf $file)) }
        $store = @(($listing -split "`r?`n") | Where-Object { $_ -match '^databases/' }) -join "`n"
        $after = @(((Get-StoreListing) -split "`r?`n") | Where-Object { $_ -match '^databases/' }) -join "`n"
        if ($store -eq $after) { return $dir }
        Say "The store's files moved while snapshot $name was being copied, try $try."
    }
    Set-Content -LiteralPath (Join-Path $dir 'torn.txt') -Value 'The store files moved during both copies.' -Encoding utf8
    Say "Snapshot $name is torn: the store's files moved during both copies."
    return $dir
}

# The keyboard keeps the store's key in memory for its whole life, and on a debuggable app a heap dump can reach it.
function Find-StoreKey([string]$dir) {
    if (Test-Path -LiteralPath $passFile) { return }
    if (-not (Get-KeyboardPid)) { Say 'No keyboard process to take the store key from yet.'; return }
    Invoke-Adb shell am dumpheap $AppPackage $remoteHprof 2>&1 | Out-Null
    $hprof = Join-Path $OutDir 'keyboard.hprof'
    Invoke-Adb pull $remoteHprof $hprof 2>&1 | Out-Null
    Invoke-Adb shell rm -f $remoteHprof 2>&1 | Out-Null
    $output = & $Python $readStore findkey --hprof $hprof --dir $dir --keyfile $passFile 2>&1 | Out-String
    Remove-Item -LiteralPath $hprof -ErrorAction SilentlyContinue
    Say "Store key: $($output.Trim())"
}

# The lowest free commit seen during the hold, on a thread of its own, because this script spends most of a run blocked
# on the test runner. The desktop's memory guard stops the emulator when it falls under 0.5 GB.
function Start-CommitWatch {
    if (-not ('IOKeyCommitWatch' -as [type])) {
        Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices; using System.Threading;
public static class IOKeyCommitWatch {
    [StructLayout(LayoutKind.Sequential)] struct Status { public uint Length, Load; public ulong Phys, AvailPhys, Page, AvailPage, Virt, AvailVirt, AvailExt; }
    [DllImport("kernel32.dll")] static extern bool GlobalMemoryStatusEx(ref Status s);
    static Timer timer;
    public static long LowestBytes = long.MaxValue;
    public static void Start() { timer = new Timer(_ => Sample(), null, 0, 5000); }
    static void Sample() {
        var s = new Status(); s.Length = (uint)Marshal.SizeOf(s);
        if (GlobalMemoryStatusEx(ref s) && (long)s.AvailPage < LowestBytes) LowestBytes = (long)s.AvailPage;
    }
}
'@
    }
    [IOKeyCommitWatch]::Start()
}

function Say-HoldSummary($holding) {
    if ($holding) { Say ("Held the emulator queue for {0:N1} minutes." -f $holding.Elapsed.TotalMinutes) }
    if (('IOKeyCommitWatch' -as [type]) -and [IOKeyCommitWatch]::LowestBytes -lt [long]::MaxValue) { Say ("Lowest free commit during the hold: {0:N1} GB" -f ([IOKeyCommitWatch]::LowestBytes / 1GB)) }
    $bytes = (Get-ChildItem -LiteralPath $OutDir -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    Say ("This run's folder holds {0:N0} MB." -f ($bytes / 1MB))
}

function Get-DeviceClock { Get-AdbText shell 'date "+%Y-%m-%d %H:%M:%S"' }

# Whoever ends the process, the system writes down how. The script and the tests end it by kill -9, by force-stop and by
# installing over it. Any other way is something a person would have met, so it must fail the run, and it would pass today.
function Get-ProcessDeaths {
    $text = Get-AdbText shell dumpsys activity exit-info $AppPackage
    $entries = [regex]::Matches($text, '(?s)timestamp=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ pid=(\d+).*?reason=(\d+) \(([^)]*)\) subreason=(\d+) \(([^)]*)\) status=(-?\d+).*?description=(.*?) state=')
    # The system keeps only the last few for a package, so what each look finds is kept for the next.
    foreach ($m in $entries) {
        if ($m.Groups[1].Value -lt $script:deathsSince) { continue }
        $script:deathsSeen["$($m.Groups[1].Value)|$($m.Groups[2].Value)"] = [ordered]@{ at = $m.Groups[1].Value; pid = $m.Groups[2].Value; reason = $m.Groups[4].Value; subreason = $m.Groups[6].Value; status = $m.Groups[7].Value; description = $m.Groups[8].Value }
    }
    return , @($script:deathsSeen.Values)
}

function Note-ProcessDeaths([string]$when) {
    $deaths = Get-ProcessDeaths
    $tally = ($deaths | Group-Object { "$($_.reason)/$($_.subreason)" } | ForEach-Object { "$($_.Name) x$($_.Count)" }) -join ', '
    Say "How the process has ended since the start of this run, $when`: $(if ($tally) { $tally } else { 'it has not' })"
    # A process killed before it reached the system is written down some seconds later as a start that timed out, under its
    # own number. That is still the script's kill.
    $foreign = @($deaths | Where-Object { -not ($_.reason -eq 'INITIALIZATION FAILURE' -and $script:killedPids.ContainsKey($_.pid)) -and ($_.reason -notin 'SIGNALED', 'USER REQUESTED', 'PACKAGE UPDATED' -or ($_.reason -eq 'SIGNALED' -and $_.status -ne '9')) })
    Note-Check "Nothing but the script and the tests ended the process, $when" ($foreign.Count -eq 0) ($(if ($foreign.Count) { $foreign | ConvertTo-Json -Compress -Depth 3 } else { $tally }))
}

function Get-CpuTicks([string]$process) {
    $rest = ((Get-AdbText shell "cat /proc/$process/stat 2>/dev/null") -replace '^.*\)\s*', '') -split ' '
    if ($rest.Count -lt 13) { return -1 }
    return [int64]$rest[11] + [int64]$rest[12]
}

# True once the process has used next to no CPU for three seconds running.
function Wait-CpuIdle([int]$seconds) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $flat = 0
    $last = -1
    while ($flat -lt 3 -and $clock.Elapsed.TotalSeconds -lt $seconds) {
        Start-Sleep -Seconds 1
        $process = Get-KeyboardPid
        if (-not $process) { $flat = 0; $last = -1; continue }
        $now = Get-CpuTicks $process
        $flat = if ($now -ge 0 -and $last -ge 0 -and $now - $last -le 5) { $flat + 1 } else { 0 }
        $last = $now
    }
    return $flat -ge 3
}

function Save-CrashBuffer([string]$name) {
    $text = Get-AdbText logcat -d -b crash
    Set-Content -LiteralPath (Join-Path $OutDir "$name-logcat-crash.txt") -Value $text -Encoding utf8
    return ($text -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($AppPackage) -or $_ -match 'com\.localboard' })
}

function Save-KeyboardLog([string]$name) {
    $lines = (Get-AdbText logcat -d -t 20000) -split "`r?`n"
    $keep = $lines | Where-Object { $_ -match 'ContextEngine|SQLite|sqlcipher|Room|AndroidRuntime|localboard|iokey' }
    Set-Content -LiteralPath (Join-Path $OutDir "$name-logcat.txt") -Value $keep -Encoding utf8
    $worrying = @($keep | Where-Object { $_ -match 'store would not open|store failed|SQLiteException|Migration|FATAL|ANR in' })
    # The runtime logs how long it took to verify a slow method, and that method's signature can name the exception.
    $timing = @($worrying | Where-Object { $_ -match ': Verification of ' })
    if ($timing.Count -gt 0) { Say "The log filter set aside $($timing.Count) of the runtime's own timing lines at $name, for example: $($timing[0])" } else { Say "The log filter had no timing line to set aside at $name." }
    return @($worrying | Where-Object { $_ -notmatch ': Verification of ' })
}

function Wait-ForKeyboardProcess([string]$other, [int]$seconds) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt $seconds) {
        $now = Get-KeyboardPid
        if ($now -and $now -ne $other) { return $now }
        Start-Sleep -Milliseconds 200
    }
    return ''
}

# Selecting what is already selected does nothing, so this is the only thing short of a field that starts the keyboard.
function Select-KeyboardAfresh {
    $id = "$AppPackage/$keyboardService"
    Invoke-Adb shell ime enable $id 2>&1 | Out-Null
    $other = (Get-AdbText shell ime list -s) -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -ne $id } | Select-Object -First 1
    if ($other) {
        Invoke-Adb shell ime set $other 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
    Invoke-Adb shell ime set $id 2>&1 | Out-Null
}

# What the system holds about the keyboard while it has no process, for when it does not come back by itself.
function Save-BindingState([string]$name) {
    $services = Get-AdbText shell "dumpsys activity services $AppPackage"
    $methods = Get-AdbText shell dumpsys input_method
    Set-Content -LiteralPath (Join-Path $OutDir "$name.txt") -Value "$services`n`n$methods" -Encoding utf8
}

# Where a person would tap: the test's window opens with a tall field at its top.
function Invoke-TapOnField {
    $size = [regex]::Match((Get-AdbText shell wm size), '(\d+)x(\d+)')
    $density = [int][regex]::Match((Get-AdbText shell wm density), '(\d+)\s*$').Groups[1].Value
    $x = [int]([int]$size.Groups[1].Value / 2)
    $y = [int](80 * $density / 160)
    Invoke-Adb shell input tap $x $y 2>&1 | Out-Null
}

function Test-KeyboardBack([string]$last, $watch) {
    if ($watch -and $watch.HasExited) { return $true }
    $now = Get-KeyboardPid
    return [bool]($now -and $now -ne $last)
}

function Wait-KeyboardBack([string]$last, $watch, [double]$seconds, $clock) {
    while ($clock.Elapsed.TotalSeconds -lt $seconds) {
        if (Test-KeyboardBack $last $watch) { return $true }
        Start-Sleep -Milliseconds 250
    }
    return (Test-KeyboardBack $last $watch)
}

# The other field in the test's window, found the way a person finds it, by where it is on screen.
function Invoke-TapOnOtherField {
    $dump = '/data/local/tmp/iokey-window.xml'
    Invoke-Adb shell uiautomator dump $dump 2>&1 | Out-Null
    $xml = Get-AdbText shell cat $dump
    Invoke-Adb shell rm -f $dump 2>&1 | Out-Null
    $at = [regex]::Match($xml, 'content-desc="single"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $at.Success) { return $false }
    $x = [int](([int]$at.Groups[1].Value + [int]$at.Groups[3].Value) / 2)
    $y = [int](([int]$at.Groups[2].Value + [int]$at.Groups[4].Value) / 2)
    Invoke-Adb shell input tap $x $y 2>&1 | Out-Null
    return $true
}

function Invoke-Route([string]$route) {
    switch ($route) {
        'focus' { if (-not (Invoke-TapOnOtherField)) { Say 'The other field could not be found on screen, so that route was not tried.' } }
        'appswitch' {
            Invoke-Adb shell input keyevent KEYCODE_HOME 2>&1 | Out-Null
            Start-Sleep -Seconds 1
            Invoke-Adb shell am start -n "$hostActivity" 2>&1 | Out-Null
        }
        'selection' { Select-KeyboardAfresh }
        default { throw "There is no bring-back route called '$route'." }
    }
}

# After a kill: nothing at first, to see what the system does by itself, and then what a person would do. $watch is the
# watcher of the next kill, which ends once it has killed that process, or nothing when no kill is left. A route is tried
# first, alone for 30 s, so that what brought the process back can be told apart. Says what brought it back.
function Invoke-BringBack([string]$last, $watch, [int]$noHelp, $sinceKill, [string]$name, [string]$route = '') {
    Save-BindingState "$name-soon-after"
    if (Wait-KeyboardBack $last $watch $noHelp $sinceKill) { return 'the system by itself' }
    Save-BindingState "$name-no-help"
    $tried = $noHelp
    if ($route) {
        Say ("{0}: no process {1:N0} s after the kill, with a field open and nothing else done, so trying the route '{2}'." -f $name, $sinceKill.Elapsed.TotalSeconds, $route)
        Invoke-Route $route
        $tried += 30
        if (Wait-KeyboardBack $last $watch $tried $sinceKill) { return "the route '$route'" }
        Say "${name}: 30 s after the route '$route' there is no process, so tapping the field."
    } else {
        Say ("{0}: no process {1:N0} s after the kill, with a field open and nothing else done, so tapping the field." -f $name, $sinceKill.Elapsed.TotalSeconds)
    }
    Invoke-TapOnField
    if (Wait-KeyboardBack $last $watch ($tried + 15) $sinceKill) { return 'a tap in the field' }
    Say "${name}: a tap in the field brought nothing, so changing the selection."
    Save-BindingState "$name-after-tap"
    Select-KeyboardAfresh
    if (Wait-KeyboardBack $last $watch ($tried + 45) $sinceKill) { return 'a change of selection' }
    Save-BindingState "$name-nothing"
    return 'nothing'
}

function Get-TestReport([string]$json) {
    if (-not (Test-Path -LiteralPath $json)) { return 'no results file' }
    $results = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable
    $lines = foreach ($name in $results.tests.Keys) { "$name $($results.tests[$name].result) $($results.tests[$name].message)" }
    return "$($lines -join ' | ') recorded $($results.recorded | ConvertTo-Json -Compress -Depth 4) shortShows $($results.shortShows | ConvertTo-Json -Compress)"
}

# The runner reads the app's hash off the device, so this ties what a step really tested to the build the report names. The
# harness is printed too, because its APKs come from the build folder and not from a parameter.
function Note-BuildTested([string]$step, [string]$expected, [string]$which) {
    $json = Join-Path $OutDir "$step.json"
    $results = if (Test-Path -LiteralPath $json) { Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable } else { @{} }
    Note-Check "The step $step ran on the $which build" ($results.appApkSha256 -eq $expected) "app '$($results.appApkSha256)', $which build '$expected', harness '$($results.harnessApkSha256)'"
}

# The keyboard lives through some exceptions and a test that is not about the window repairs a short one, so no test goes red
# over either. Only the results show them.
function Note-StepClean([string]$step) {
    $json = Join-Path $OutDir "$step.json"
    if (-not (Test-Path -LiteralPath $json)) {
        Note-Check "The step $step left its results" $false 'no results file'
        return
    }
    $results = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable
    $swallowed = @($results.swallowedErrors | Where-Object { $_ })
    $short = @($results.shortShows | Where-Object { $_ })
    Note-Check "The keyboard lived through no error during the step $step" ($swallowed.Count -eq 0) "$($swallowed.Count) of them $(if ($swallowed.Count) { $swallowed | ConvertTo-Json -Compress -Depth 3 })"
    Note-Check "No show came up short during the step $step" ($short.Count -eq 0) "$($short.Count) of them $($short -join ' | ')"
}

# The entries of a saved crash log that say the keyboard lived through an error. A real crash is the crash buffer's and exit-info's.
function Get-SurvivedEntries([string]$file) {
    $text = if (Test-Path -LiteralPath $file) { Get-Content -LiteralPath $file -Raw }
    if (-not $text) { return , @() }
    return , @([regex]::Split($text, '(?m)^(?=\S{3,12} IOKey )') | Where-Object { $_.Contains($survivedMark) } | ForEach-Object { $_.Trim() })
}

# A copy can hold what the older build wrote, which says nothing about this one, so what the older build left is set aside.
function Note-CrashLog([string]$name, [string]$file) {
    $older = Get-SurvivedEntries (Join-Path $OutDir 'before\last_crash.txt')
    $new = @((Get-SurvivedEntries $file) | Where-Object { $_ -notin $older })
    Note-Check $name ($new.Count -eq 0) $(if ($new.Count) { ($new | Select-Object -First 3) -join ' | ' } elseif (Test-Path -LiteralPath $file) { 'none in the file' } else { 'no file' })
}

# The nightly job's constraints, one entry for each job that asks for a charger. The older build asks for a charger and a battery
# that is not low, and this build asks for an idle phone as well. A keyboard's start replaces the job through WorkManager's update
# policy, which a test on a fresh install cannot show, because there no older job exists to replace.
function Get-NightlyJobs {
    $dump = @(Invoke-Adb shell dumpsys jobscheduler $AppPackage 2>&1) -join "`n"
    return @([regex]::Split($dump, '(?=JOB #)') | Where-Object { $_ -match '^JOB #' } | ForEach-Object {
            [regex]::Match($_, 'Required constraints:([^\[\n]*)').Groups[1].Value.Trim()
        } | Where-Object { $_ -match '\bCHARGING\b' })
}

# WorkManager enqueues on its own threads after the process starts, so this waits for the wanted state and returns what it last saw.
function Wait-NightlyJobs([bool]$wantIdle, [int]$seconds) {
    $until = (Get-Date).AddSeconds($seconds)
    do {
        $jobs = @(Get-NightlyJobs)
        $idle = @($jobs | Where-Object { $_ -match '\bIDLE\b' })
        if ($jobs.Count -gt 0 -and ($(if ($wantIdle) { $idle.Count -eq $jobs.Count } else { $idle.Count -eq 0 }))) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $until)
    return , $jobs
}

# The runner empties the keyboard's crash log as a step starts, and the harness empties it as each test ends. So what the process
# has written before a step is read here first, or it would be gone unread.
function Invoke-TestsReadingTheLogFirst([string]$name, [string]$classes) {
    $saved = Join-Path $OutDir "crash-log-before-$name.txt"
    Save-DeviceFile $crashLog $saved
    Note-CrashLog "Before the step $name the crash log holds no error the keyboard lived through" $saved
    return (Invoke-Tests $name $classes)
}

# Two reads' rows through read_store.py. The checks go by its summary, so a compare that could not run fails them.
function Get-RowDiff([string]$a, [string]$b, [string[]]$options) {
    $file = Join-Path $OutDir 'diff-summary.json'
    Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    Say (& $Python $readStore diff --a $a --b $b --summary $file @options 2>&1 | Out-String)
    if (Test-Path -LiteralPath $file) { return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json -AsHashtable }
    return $null
}

function Note-RowsKept([string]$name, $diff) {
    if (-not $diff) {
        Note-Check $name $false 'the rows could not be compared, see the report'
        return
    }
    Note-Check $name (($diff.rows_lost -eq 0) -and ($diff.counts_lowered -eq 0)) "$($diff.rows_a) rows to $($diff.rows_b), $($diff.rows_lost) lost $($diff.rows_lost_named | ConvertTo-Json -Compress), $($diff.counts_lowered) with a lower count $($diff.counts_lowered_named | ConvertTo-Json -Compress)"
}

# Each key's time in a typing step's results, in the order they were typed.
function Get-KeyTimes([string]$json) {
    if (-not (Test-Path -LiteralPath $json)) { return , @() }
    $results = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable
    $times = foreach ($test in $results.recorded.Keys) {
        foreach ($name in $results.recorded[$test].Keys) {
            if ($name -match '-key-ms$') { "$($results.recorded[$test][$name])" -split ',' | ForEach-Object { [int64]$_ } }
        }
    }
    return , @($times)
}

# A key held up by the first start holds up the tap, so it shows here and in no other check. These are emulator times: the harness
# and the emulator take most of them, which is why they are only ever read against each other.
function Note-TypingLag([string]$cpusLabel) {
    $during = Get-KeyTimes (Join-Path $OutDir 'first-start-typing.json')
    $rest = Get-KeyTimes (Join-Path $OutDir 'typing-at-rest.json')
    if ($during.Count -eq 0 -or $during.Count -ne $rest.Count) {
        Note-Check 'Typing during the first start could be set against typing at rest' $false "$($during.Count) taps during it, $($rest.Count) at rest"
        return
    }
    $duringMs = ($during | Measure-Object -Sum).Sum
    $restMs = ($rest | Measure-Object -Sum).Sum
    $slowest = ($during | Measure-Object -Maximum).Maximum
    $slowestAtRest = ($rest | Measure-Object -Maximum).Maximum
    $when = if ($script:mainKillLanded) { ', typed after a kill during the fold, so after the first start''s commit' } else { '' }
    Note-Check "Typing during the first start took under $typingLagLimit times what it takes at rest" ($duringMs -lt $typingLagLimit * $restMs) ("{0} taps took {1} ms against {2} ms at rest, {3:N2} times, slowest key {4} ms against {5} ms, emulator, translated, {6} CPUs{7}" -f $during.Count, $duringMs, $restMs, ($duringMs / [math]::Max($restMs, 1)), $slowest, $slowestAtRest, $cpusLabel, $when)
}

# At the old version nothing has been committed, so whatever differs from before is damage, whatever else came through whole.
function Note-OldStoreUnchanged([int]$i, $seen, $before) {
    $tables = @(if ($before) { (@($before.tables.Keys) + @($seen.tables.Keys)) | Sort-Object -Unique | Where-Object { $before.tables[$_] -ne $seen.tables[$_] } })
    Note-Check "After kill $i the store at the old version holds what it held before" ($before -and ($seen.rows_total -eq $before.rows_total) -and ($seen.rows_digest -eq $before.rows_digest) -and ($tables.Count -eq 0)) "rows $($seen.rows_total) against $($before.rows_total), digest $(if ($seen.rows_digest -eq $before.rows_digest) { 'the same' } else { 'different' }), tables that differ: '$($tables -join ', ')'"
}

# A kill during the fold comes after the commit, so that store is at the new version, and it can come before the one-time step,
# which then takes the struck words. So apart from those, and any word typed after it, it must already hold the final rows.
function Note-FoldKillRows($series, [string]$firstDir, $first) {
    for ($i = 0; $i -lt $series.Count; $i++) {
        $entry = $series[$i]
        if ($entry.step -notmatch '^main' -or $entry.landed -ne 'yes' -or $entry.torn) { continue }
        $diff = Get-RowDiff (Join-Path $OutDir "kill-$i") $firstDir @('--allow', "plimvet,grondux,$($first.style_stats.ac_migration_struck_words)")
        if (-not $diff) {
            Note-Check "Kill $i, during the fold, already held the final rows" $false 'the rows could not be compared, see the report'
            continue
        }
        Note-Check "Kill $i, during the fold, already held the final rows" (($diff.gone + $diff.new + $diff.changed) -eq 0) "$($diff.rows_a) rows then and $($diff.rows_b) after the first start, $($diff.gone) gone, $($diff.new) new, $($diff.changed) changed, $($diff.expected_differences) rows of struck or typed words set aside"
    }
}

# The comma keeps a single row from being unrolled into its columns on the way out of the function.
function Get-Rows($store, [string]$word) { , @($store.ngrams | Where-Object { $_[2] -eq $word }) }

function Get-ContextRows($store, [string]$word) { , @($store.ngrams | Where-Object { $_[0] -eq $word -or $_[1] -eq $word }) }

# The store's files in bytes, from a snapshot's listing.
function Get-StoreBytes([string]$listing, [string]$suffix) {
    [int64][regex]::Match($listing, "databases/localboard\.db$([regex]::Escape($suffix)):inode=\d+:size=(\d+)").Groups[1].Value
}

function Get-Unigram($store, [string]$word) {
    foreach ($row in (Get-Rows $store $word)) { if ($row[0] -eq '' -and $row[1] -eq '') { return [int]$row[3] } }
    return 0
}

# Stops as soon as a new process shows, then kills it at the step asked for. On the device, because every round trip adds
# time, and the looking and the kill are inside one run-as, because a run-as for each took long enough for the step to end.
$killWatch = @'
#!/system/bin/sh
pkg=$1; old=$2; step=$3; limit=$4; watchfor=$5; copy=$6
pause=0
case "$step" in
  *+*) pause=${step#*+}; step=${step%+*} ;;
esac
case "$step" in
  prefs) file=shared_prefs/localboard_prefs.xml ;;
  shm) file=databases/localboard.db-shm ;;
  wal) file=databases/localboard.db-wal ;;
  main) file=databases/localboard.db ;;
  *) file= ;;
esac
[ -n "$file" ] && before=$(run-as "$pkg" stat -c %i:%s:%y "$file" 2>/dev/null)
start=$(date +%s)
while :; do
  p=$(pidof "$pkg")
  if [ -n "$p" ] && [ "$p" != "$old" ]; then break; fi
  now=$(date +%s)
  if [ $((now - start)) -ge "$limit" ]; then echo "no new process within $limit s"; exit 3; fi
done
seen=$(date +%s.%N)
run-as "$pkg" sh -c '
  p=$1; file=$2; before=$3; step=$4; pause=$5; seen=$6; watchfor=$7; copy=$8
  looks=0; landed=no
  if [ -n "$file" ]; then
    while [ $SECONDS -lt "$watchfor" ]; do
      now=$(stat -c %i:%s:%y "$file" 2>/dev/null)
      if [ "$now" != "$before" ]; then landed=yes; break; fi
      looks=$((looks+1))
    done
  elif [ "$step" != "0" ]; then
    sleep "$step"
  fi
  [ "$pause" != "0" ] && sleep "$pause"
  alive=no
  kill -9 $p 2>/dev/null && alive=yes
  killed=$(date +%s.%N)
  if [ "$copy" != "-" ]; then
    rm -rf "$copy"; mkdir -p "$copy"
    stat -c %n:inode=%i:size=%s:mtime=%y databases/* shared_prefs/* files/* > "$copy/listing.txt" 2>&1
    cp -f databases/localboard.db databases/localboard.db-wal databases/localboard.db-shm shared_prefs/localboard_prefs.xml shared_prefs/localboard_prefs.xml.bak shared_prefs/localboard_keystore.xml files/last_crash.txt "$copy/" 2>/dev/null
    stat -c %n:inode=%i:size=%s:mtime=%y databases/* > "$copy/listing-after.txt" 2>&1
  fi
  echo "pid=$p seen=$seen killed=$killed alive=$alive landed=$landed looks=$looks file=$file from=[$before] to=[$now]"
' iokey-killwatch "$p" "$file" "$before" "$step" "$pause" "$seen" "$watchfor" "$copy"
'@

# Once a second, the time, the process, its CPU so far, and the size and time of the store's files. Stat only, so that it
# can run through a first start and change nothing about it. Reading the store each time would, and a read cannot see the
# largest the files got anyway, because the one big migration transaction goes through the log, which is cut back later.
$storePoll = @'
#!/system/bin/sh
pkg=$1; limit=$2; stop=$3
start=$(date +%s)
while [ ! -e "$stop" ]; do
  now=$(date +%s.%N)
  p=$(pidof "$pkg")
  ticks=0
  if [ -n "$p" ]; then
    stat=$(cat /proc/$p/stat 2>/dev/null)
    set -- ${stat##*) }
    ticks=$(( ${12:-0} + ${13:-0} ))
  fi
  files=$(run-as "$pkg" stat -c %s:%y databases/localboard.db databases/localboard.db-wal 2>/dev/null | tr " \n" "_;")
  echo "$now pid=$p ticks=$ticks files=$files"
  [ $(( ${now%.*} - start )) -ge "$limit" ] && break
  sleep 1
done
'@

function Start-StorePoll {
    $local = Join-Path $OutDir 'store-poll.sh'
    [IO.File]::WriteAllText($local, $storePoll.Replace("`r`n", "`n"))
    Invoke-Adb push $local $remotePoll 2>&1 | Out-Null
    Invoke-Adb shell rm -f $remotePollStop 2>&1 | Out-Null
    $script:pollFile = Join-Path $OutDir 'store-poll.txt'
    $script:pollProcess = Start-Process -FilePath $adb -ArgumentList @('-s', $device, 'shell', 'sh', $remotePoll, $AppPackage, '3600', $remotePollStop) -RedirectStandardOutput $script:pollFile -RedirectStandardError "$($script:pollFile).err" -NoNewWindow -PassThru
}

function Stop-StorePoll {
    if (-not $script:pollProcess) { return }
    Invoke-Adb shell touch $remotePollStop 2>&1 | Out-Null
    if (-not $script:pollProcess.WaitForExit(5000)) { Stop-Process -Id $script:pollProcess.Id -Force -ErrorAction SilentlyContinue }
    Invoke-Adb shell rm -f $remotePoll $remotePollStop 2>&1 | Out-Null
    $script:pollProcess = $null
}

# A watcher left armed kills the next new process it sees, which could be the next agent's keyboard. Its first part runs as the
# shell and the part that kills as the app, so each has to be stopped from its own side.
function Stop-KillWatch {
    if ($script:watchProcess -and -not $script:watchProcess.HasExited) { Stop-Process -Id $script:watchProcess.Id -Force -ErrorAction SilentlyContinue }
    $script:watchProcess = $null
    Invoke-Adb shell pkill -f iokey-killwatch 2>&1 | Out-Null
    Invoke-RunAs 'pkill -f iokey-killwatch' | Out-Null
}

# What a script that was killed outright could not clear up after itself.
function Clear-Leftovers {
    Stop-KillWatch
    Invoke-Adb shell pkill -f iokey-storepoll 2>&1 | Out-Null
    Invoke-Adb shell rm -f $remoteHprof $remoteWatch $remotePoll $remotePollStop 2>&1 | Out-Null
}

function Read-StorePoll {
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($line in (Get-Content -LiteralPath $script:pollFile -ErrorAction SilentlyContinue)) {
        if ($line -notmatch '^(\d+\.\d+) pid=(\d*) ticks=(\d+) files=(\S*)\s*$') { continue }
        $bytes = [int64]0
        foreach ($file in ($Matches[4] -split ';')) { if ($file) { $bytes += [int64]($file -split ':', 2)[0] } }
        $rows.Add([pscustomobject]@{ at = [double]$Matches[1]; process = $Matches[2]; ticks = [int64]$Matches[3]; files = $Matches[4]; bytes = $bytes })
    }
    return , $rows
}

function Get-DeviceEpoch { [double](Get-AdbText shell date +%s.%N) }

# Waits for a process that has stopped working: its files unchanged and its CPU flat for 15 s. Says when that process
# started and when it last did anything, as the device saw them.
function Wait-StoreQuiet([double]$since, [int]$limitSeconds) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Seconds 3
        $rows = @((Read-StorePoll) | Where-Object { $_.at -ge $since })
        if ($rows.Count -lt 2 -or -not $rows[-1].process) { continue }
        $k = $rows.Count - 1
        while ($k -gt 0 -and $rows[$k - 1].process -eq $rows[$k].process -and $rows[$k - 1].files -eq $rows[$k].files -and ($rows[$k].ticks - $rows[$k - 1].ticks) -le 5) { $k-- }
        if ($rows[-1].at - $rows[$k].at -ge 15) {
            $born = $k
            while ($born -gt 0 -and $rows[$born - 1].process -eq $rows[$k].process) { $born-- }
            return [ordered]@{ quiet = $true; process = $rows[$k].process; bornAt = $rows[$born].at; lastChangeAt = $rows[$k].at; secondsToQuiet = [math]::Round($rows[$k].at - $rows[$born].at, 1) }
        }
    } while ($clock.Elapsed.TotalSeconds -lt $limitSeconds)
    return [ordered]@{ quiet = $false; process = ''; bornAt = 0; lastChangeAt = 0; secondsToQuiet = -1 }
}

# Where each kill's copy goes, read later: the store can be moving again by the time a snapshot could be taken, because
# the system restarts the process by itself and the migration starts over.
function Save-KillSnapshot([string]$name, [string]$copy) {
    if ($copy -eq '-') { return Save-Snapshot $name }
    $dir = Join-Path $OutDir $name
    if (Test-Path -LiteralPath (Join-Path $dir 'listing.txt')) { return $dir }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($leaf in 'localboard.db', 'localboard.db-wal', 'localboard.db-shm', 'localboard_prefs.xml', 'localboard_prefs.xml.bak', 'localboard_keystore.xml', 'last_crash.txt', 'listing.txt', 'listing-after.txt') {
        Save-DeviceFile "$copy/$leaf" (Join-Path $dir $leaf)
    }
    $listed = @((Get-Content -LiteralPath (Join-Path $dir 'listing.txt') -ErrorAction SilentlyContinue) | Where-Object { $_ -match '^databases/' }) -join "`n"
    $after = (Get-Content -LiteralPath (Join-Path $dir 'listing-after.txt') -ErrorAction SilentlyContinue) -join "`n"
    if ($listed -ne $after) {
        Set-Content -LiteralPath (Join-Path $dir 'torn.txt') -Value 'The store files moved while the watcher copied them.' -Encoding utf8
        Say "Snapshot $name is torn: the process was back and the store moving before the copy ended."
    }
    Invoke-RunAs "rm -rf $copy" | Out-Null
    return $dir
}

# One read for each kill's copy, because it cannot change, and a grown store takes some seconds to read.
function Read-KillStore([string]$dir) {
    $json = Join-Path $dir 'store.json'
    if ($Shape -ne 'Small' -and (Test-Path -LiteralPath $json)) { return Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable }
    return Read-Store $dir
}

# Decrypts a copy of the store. SQLCipher 4 defaults: PBKDF2-HMAC-SHA512, 256000 rounds, 4096-byte pages, 80 reserved.
$readStorePy = @'
import argparse, hashlib, json, os, re, sqlite3, struct, tempfile
from concurrent.futures import ThreadPoolExecutor
from Crypto.Cipher import AES

PAGE, RESERVE = 4096, 80
FRAGMENTS = ["isn", "doesn", "didn", "wouldn", "couldn", "shouldn", "wasn", "weren", "hasn", "hadn", "aren", "ain", "mustn", "needn", "mightn", "ve", "ll", "re"]
ENDINGS = ["t", "s", "m", "d"]
FRAGMENT_SHAPED = ["don", "haven"]
WON_LIMIT = 3
WATCH = ["xylqor", "zorblax", "qwibbit", "drumwick", "flumbo", "grentix", "velvet", "harbor", "mirble", "zyxquor", "blorgum", "tizzle", "marble", "don", "haven", "won", "plimvet", "grondux"]
# A grown store holds thousands of these, and the report only needs to see that there are some.
MOST_LISTED = 40
GROWN_WORD = re.compile(r"^qj[a-z]{3}$")


def derive(passphrase, salt):
    return hashlib.pbkdf2_hmac("sha512", passphrase, salt, 256000, 32)


def first_page(folder):
    db = os.path.join(folder, "localboard.db")
    wal = db + "-wal"
    if os.path.exists(db) and os.path.getsize(db) >= PAGE:
        with open(db, "rb") as f:
            return f.read(PAGE)
    if not os.path.exists(wal):
        raise SystemExit("no first page in the store: the main file is short and there is no log")
    with open(wal, "rb") as f:
        w = f.read()
    off = 32
    while off + 24 + PAGE <= len(w):
        if struct.unpack(">I", w[off:off + 4])[0] == 1:
            return w[off + 24:off + 24 + PAGE]
        off += 24 + PAGE
    raise SystemExit("no first page in the store")


def opens(key, head):
    plain = AES.new(key, AES.MODE_CBC, head[PAGE - RESERVE:PAGE - RESERVE + 16]).decrypt(head[16:PAGE - RESERVE])
    return plain[:2] == b"\x10\x00" and plain[4] == RESERVE and plain[5:8] == b"\x40\x20\x20"


def findkey(args):
    head = first_page(args.dir)
    salt = head[:16]
    with open(args.hprof, "rb") as f:
        data = f.read()
    # A byte array of length 32 in the dump: tag, object id, stack serial, count, element type, then the bytes.
    seen = set()
    for m in re.finditer(b"\x23.{8}\x00\x00\x00\x20\x08", data, re.S):
        cand = data[m.end():m.end() + 32]
        if len(set(cand)) >= 18:
            seen.add(cand)

    def attempt(cand):
        return cand if opens(derive(cand, salt), head) else None

    with ThreadPoolExecutor(8) as pool:
        for found in pool.map(attempt, seen):
            if found:
                with open(args.keyfile, "w") as out:
                    out.write(found.hex())
                print("found among %d candidates" % len(seen))
                return
    raise SystemExit("not found among %d candidates" % len(seen))


def checksum(data, s0, s1, big):
    words = struct.unpack(("%s%dI" % (">" if big else "<", len(data) // 4)), data)
    for i in range(0, len(words), 2):
        s0 = (s0 + words[i] + s1) & 0xFFFFFFFF
        s1 = (s1 + words[i + 1] + s0) & 0xFFFFFFFF
    return s0, s1


def read_wal(path):
    info = {"wal_bytes": 0, "wal_frames_valid": 0, "wal_frames_committed": 0, "wal_frames_uncommitted": 0}
    pages, size = {}, None
    if not os.path.exists(path) or os.path.getsize(path) < 32:
        return pages, size, info
    with open(path, "rb") as f:
        w = f.read()
    info["wal_bytes"] = len(w)
    magic, _, page_size, _, salt1, salt2, c1, c2 = struct.unpack(">8I", w[:32])
    big = magic == 0x377F0683
    s0, s1 = checksum(w[:24], 0, 0, big)
    if (s0, s1) != (c1, c2):
        info["wal_header"] = "bad checksum"
        return pages, size, info
    # A big transaction overwrites frames it has already spilled and repairs their checksums only
    # when it commits, so the chain breaks at the first of them. Every later frame with this log's
    # salts is still a frame past the last commit, and leaving those out would call a kill in the
    # middle of a migration a kill with nothing in flight.
    off, pending, frames, salted, chain = 32, {}, 0, 0, True
    while off + 24 + page_size <= len(w):
        pg, commit, f1, f2, k1, k2 = struct.unpack(">6I", w[off:off + 24])
        if (f1, f2) != (salt1, salt2):
            break
        salted += 1
        if chain:
            body = w[off + 24:off + 24 + page_size]
            s0, s1 = checksum(w[off:off + 8] + body, s0, s1, big)
            chain = (s0, s1) == (k1, k2)
        if chain:
            pending[pg] = body
            frames += 1
            if commit:
                pages.update(pending)
                pending = {}
                size = commit
                info["wal_frames_committed"] = frames
        off += 24 + page_size
    info["wal_frames_valid"] = frames
    info["wal_frames_uncommitted"] = salted - info["wal_frames_committed"]
    return pages, size, info


def rebuild(folder, key):
    path = os.path.join(folder, "localboard.db")
    data = b""
    if os.path.exists(path):
        with open(path, "rb") as f:
            data = f.read()
    pages = {i + 1: data[i * PAGE:(i + 1) * PAGE] for i in range(len(data) // PAGE)}
    from_wal, size, info = read_wal(path + "-wal")
    info["db_bytes"] = len(data)
    pages.update(from_wal)
    size = size or len(pages)
    out = bytearray()
    for n in range(1, size + 1):
        raw = pages.get(n)
        if raw is None:
            raise SystemExit("page %d is missing" % n)
        skip = 16 if n == 1 else 0
        plain = AES.new(key, AES.MODE_CBC, raw[PAGE - RESERVE:PAGE - RESERVE + 16]).decrypt(raw[skip:PAGE - RESERVE])
        out += (b"SQLite format 3\x00" if n == 1 else b"") + plain + b"\x00" * RESERVE
    out[18] = 1
    out[19] = 1
    return bytes(out), info


def read_rows(con, folder):
    """Every row goes to rows.tsv and through the digest, and only the rows the checks ask about stay in memory."""
    watch, frag, ends = set(WATCH), set(FRAGMENTS), set(FRAGMENTS + ENDINGS)
    kept, fragments, contexts = [], [], []
    fragments_total = contexts_total = total = 0
    orders = {"unigram": 0, "bigram": 0, "trigram": 0, "malformed": 0}
    by_word = {w: [0, 0, 0] for w in WATCH}
    digest = hashlib.sha256()
    with open(os.path.join(folder, "rows.tsv"), "w", encoding="utf-8", newline="\n") as tsv:
        for p1, p2, word, count, last in con.execute("SELECT prev1, prev2, word, count, lastUsed FROM ngrams ORDER BY prev1, prev2, word"):
            total += 1
            tsv.write("%s\t%s\t%s\t%s\t%s\n" % (p1, p2, word, count, last))
            digest.update(("%s|%s|%s|%s\n" % (p1, p2, word, count)).encode("utf-8"))
            order = "unigram" if not p1 and not p2 else "bigram" if p1 and not p2 else "trigram" if p1 and p2 else "malformed"
            orders[order] += 1
            row = [p1, p2, word, count, last]
            if word in watch:
                by_word[word][{"unigram": 0, "bigram": 1}.get(order, 2)] += count
            if word in watch or p1 in watch or p2 in watch:
                kept.append(row)
            if word in frag:
                fragments_total += 1
                if len(fragments) < MOST_LISTED:
                    fragments.append(row)
            if p1 in ends or p2 in ends:
                contexts_total += 1
                if len(contexts) < MOST_LISTED:
                    contexts.append(row)
    return {
        "rows_total": total, "rows_digest": digest.hexdigest(), "ngrams": kept, "orders": orders,
        "fragments": fragments, "fragments_total": fragments_total,
        "fragment_contexts": contexts, "fragment_contexts_total": contexts_total,
    }, by_word


def read(args):
    folder = args.dir
    with open(args.keyfile) as f:
        passphrase = bytes.fromhex(f.read().strip())
    if args.main_only:
        return main_only(folder, passphrase)
    head = first_page(folder)
    key = derive(passphrase, head[:16])
    if not opens(key, head):
        raise SystemExit("this key does not open the store")
    plain, info = rebuild(folder, key)
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        path = os.path.join(tmp, "plain.db")
        with open(path, "wb") as f:
            f.write(plain)
        del plain
        con = sqlite3.connect(path)
        result = {"salt": head[:16].hex(), "file": info}
        result["user_version"] = con.execute("PRAGMA user_version").fetchone()[0]
        result["integrity"] = con.execute("PRAGMA integrity_check").fetchone()[0]
        tables = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        result["tables"] = {t: con.execute('SELECT COUNT(*) FROM "%s"' % t).fetchone()[0] for t in tables}
        result["identity_hash"] = con.execute("SELECT identity_hash FROM room_master_table").fetchone()[0]
        rows, by_word = read_rows(con, folder)
        result.update(rows)
        result["style_stats"] = {k: v[:400] for k, v in con.execute("SELECT key, value FROM style_stats")}
        result["usage_stats"] = {k: v for k, v in con.execute("SELECT * FROM usage_stats")} if "usage_stats" in tables else {}
        con.close()
    with open(os.path.join(folder, "store.json"), "w", encoding="utf-8") as out:
        json.dump(result, out, ensure_ascii=False, indent=0)
    print("version", result["user_version"], "integrity", result["integrity"], "tables", result["tables"], "orders", result["orders"], "file", info)
    print("salt", result["salt"], "identity", result["identity_hash"], "rows", result["rows_total"], "digest", result["rows_digest"], "fragments", result["fragments_total"], "fragment contexts", result["fragment_contexts_total"])
    for word in WATCH:
        uni, bi, tri = by_word[word]
        print("%-10s uni=%s bi=%s tri=%s" % (word, uni, bi, tri))
    print("style_stats keys", sorted(result["style_stats"]))
    for k in ("ac_migration", "ac_migration_struck_words", "typo_strikes", "typo_suppressions", "confirmed_words"):
        print(k, "=", repr(result["style_stats"].get(k)))


def main_only(folder, passphrase):
    """What the main file holds with no log, which is the only way to tell where the rows sit: a frame stays valid in the log after it has been folded in."""
    path = os.path.join(folder, "localboard.db")
    out = {"readable": False, "why": "", "db_bytes": 0, "rows_in_main": 0, "watch_in_main": []}
    try:
        with open(path, "rb") as f:
            data = f.read()
        out["db_bytes"] = len(data)
        if len(data) < PAGE:
            raise ValueError("the main file is %d bytes, under one page" % len(data))
        key = derive(passphrase, data[:16])
        if not opens(key, data[:PAGE]):
            raise ValueError("this key does not open the main file")
        plain = bytearray()
        for n in range(len(data) // PAGE):
            raw = data[n * PAGE:(n + 1) * PAGE]
            skip = 16 if n == 0 else 0
            body = AES.new(key, AES.MODE_CBC, raw[PAGE - RESERVE:PAGE - RESERVE + 16]).decrypt(raw[skip:PAGE - RESERVE])
            plain += (b"SQLite format 3\x00" if n == 0 else b"") + body + b"\x00" * RESERVE
        plain[18] = 1
        plain[19] = 1
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
            copy = os.path.join(tmp, "main.db")
            with open(copy, "wb") as f:
                f.write(plain)
            con = sqlite3.connect(copy)
            try:
                tables = [r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")]
                if "ngrams" in tables:
                    out["rows_in_main"] = con.execute("SELECT COUNT(*) FROM ngrams").fetchone()[0]
                    marks = ",".join("?" * len(WATCH))
                    found = set()
                    for column in ("word", "prev1", "prev2"):
                        found.update(r[0] for r in con.execute("SELECT DISTINCT %s FROM ngrams WHERE %s IN (%s)" % (column, column, marks), WATCH))
                    out["watch_in_main"] = sorted(found)
            finally:
                con.close()
        out["readable"] = True
    except (OSError, ValueError, sqlite3.DatabaseError) as e:
        out["why"] = str(e)
    with open(os.path.join(folder, "main-only.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=0)
    if out["readable"]:
        print("main only: rows_in_main", out["rows_in_main"], "watch words present", out["watch_in_main"], "main bytes", out["db_bytes"])
    else:
        print("main only: unreadable,", out["why"])


def load_rows(folder):
    rows = {}
    with open(os.path.join(folder, "rows.tsv"), encoding="utf-8", newline="\n") as f:
        for line in f:
            p1, p2, word, count, _ = line.rstrip("\n").split("\t")
            rows[(p1, p2, word)] = int(count)
    return rows


def diff(args):
    with open(os.path.join(args.a, "store.json"), encoding="utf-8") as f:
        a = json.load(f)
    with open(os.path.join(args.b, "store.json"), encoding="utf-8") as f:
        b = json.load(f)
    x = load_rows(args.a)
    y = load_rows(args.b)
    allowed = set(w for w in (args.allow or "").split(",") if w)
    expected = lambda k: any(w in allowed for w in k)
    grown = lambda k: any(GROWN_WORD.match(w) for w in k)
    gone = [k for k in x if k not in y]
    new = [k for k in y if k not in x]
    changed = [k for k in x if k in y and x[k] != y[k]]
    print("rows %d to %d, gone %d, new %d, count changed %d" % (len(x), len(y), len(gone), len(new), len(changed)))
    print("user_version %s to %s, salt same %s" % (a["user_version"], b["user_version"], a["salt"] == b["salt"]))
    for k in changed[:40]:
        print("changed", k, x[k], "to", y[k])
    for k in new[:40]:
        print("new", k, y[k])
    for k in gone[:40]:
        print("gone", k, x[k])
    stats = sorted(k for k in set(a["style_stats"]) | set(b["style_stats"]) if a["style_stats"].get(k) != b["style_stats"].get(k))
    print("style_stats that differ:", stats)
    if args.summary:
        every = gone + new + changed
        left = [k for k in every if not expected(k)]
        summary = {
            "rows_a": len(x), "rows_b": len(y), "gone": len([k for k in gone if not expected(k)]), "new": len([k for k in new if not expected(k)]),
            "changed": len([k for k in changed if not expected(k)]), "expected_differences": len(every) - len(left),
            "in_grown_rows": len([k for k in left if grown(k)]), "in_other_rows": len([k for k in left if not grown(k)]),
            "other_rows_named": sorted(set(w for k in left if not grown(k) for w in k if w))[:60],
        }
        # What no start may do to a row. A first start does some of it by design: MIGRATION_5_TO_6 in NGramRows.kt takes the
        # pieces of contractions and rows of count 0 or less and holds won to 3, and the one-time step takes the struck words.
        taken = set()
        if args.first_start:
            taken = set(FRAGMENTS + ENDINGS + FRAGMENT_SHAPED) | set(w for w in (b["style_stats"].get("ac_migration_struck_words") or "").split(",") if w)
        spared = lambda k: expected(k) or any(w in taken for w in k)
        lost = [k for k in gone if not spared(k) and not (args.first_start and x[k] <= 0)]
        lowered = [k for k in changed if y[k] < x[k] and not spared(k) and not (args.first_start and k[2] == "won" and y[k] == WON_LIMIT)]
        summary["rows_lost"] = len(lost)
        summary["rows_lost_named"] = [["|".join(k), x[k]] for k in lost[:20]]
        summary["counts_lowered"] = len(lowered)
        summary["counts_lowered_named"] = [["|".join(k), x[k], y[k]] for k in lowered[:20]]
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=0)
        print("apart from what was typed on purpose:", summary)


parser = argparse.ArgumentParser()
sub = parser.add_subparsers(dest="command", required=True)
p = sub.add_parser("findkey")
p.add_argument("--hprof", required=True)
p.add_argument("--dir", required=True)
p.add_argument("--keyfile", required=True)
p.set_defaults(run=findkey)
p = sub.add_parser("read")
p.add_argument("--dir", required=True)
p.add_argument("--keyfile", required=True)
p.add_argument("--main-only", action="store_true")
p.set_defaults(run=read)
p = sub.add_parser("diff")
p.add_argument("--a", required=True)
p.add_argument("--b", required=True)
p.add_argument("--allow")
p.add_argument("--summary")
p.add_argument("--first-start", action="store_true", help="--a is the store before its first start and --b after it")
p.set_defaults(run=diff)
args = parser.parse_args()
args.run(args)
'@

function Invoke-SeedStep {
    $seedCode = Invoke-Tests 'seed' 'UpgradeSeedTest'
    Note-Check 'The older build took the seed' ($seedCode -eq 0) "runner exit code $seedCode, see seed.json"
}

# Nothing else types while a first start runs, though that is what a person does right after an update.
function Invoke-TypingStep {
    $typingCode = Invoke-TestsReadingTheLogFirst 'first-start-typing' 'UpgradeFirstStartTypingTest'
    $json = Join-Path $OutDir 'first-start-typing.json'
    Note-Check 'The letters typed landed, on a show that fits' ($typingCode -eq 0) (Get-TestReport $json)
    Note-BuildTested 'first-start-typing' $newHash 'new'
    Note-StepClean 'first-start-typing'
    $moving = ''
    $mainAfter = ''
    if (Test-Path -LiteralPath $json) {
        $results = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable
        foreach ($test in $results.recorded.Keys) {
            if ($results.recorded[$test].Contains('store-moving-during-typing')) { $moving = "$($results.recorded[$test]['store-moving-during-typing'])" }
            if ($results.recorded[$test].Contains('main-file-after-typing')) { $mainAfter = "$($results.recorded[$test]['main-file-after-typing'])" }
        }
    }
    # A first start's commit is folded into the main file, so while that file is as the update left it the first start has
    # not ended. If it has changed, nothing here was typed during one. The log says less: it can stay still for half a
    # minute while a migration scans the rows, so it is only reported. A kill at main has already passed that point on purpose.
    if ($script:mainKillLanded) {
        Say "These words came after a kill during the fold, so after the first start's commit: main file '$mainAfter' after the last word, and '$($script:mainAtUpgrade)' at the update."
    } elseif ($Shape -eq 'Small') {
        # Nothing folds here until a hide, so an unmoved main file would say the first start was running when it had ended long before.
        Say "On a small store this cannot say whether a first start was running: main file '$mainAfter' after the last word, and '$($script:mainAtUpgrade)' at the update."
    } else {
        Note-Check 'The first start was still running when those words were typed' ($mainAfter -and ($mainAfter -eq $script:mainAtUpgrade)) "main file '$($script:mainAtUpgrade)' at the update, '$mainAfter' after the last word; the log moved meanwhile: '$moving'"
    }
}

# Under the same CPU limit as the typing during the first start, or the two could not be set against each other.
function Invoke-TypingAtRestStep([string]$cpusLabel) {
    $restCode = Invoke-TestsReadingTheLogFirst 'typing-at-rest' 'UpgradeTypingAtRestTest'
    Note-Check 'The same keys could be typed at rest' ($restCode -eq 0) (Get-TestReport (Join-Path $OutDir 'typing-at-rest.json'))
    Note-StepClean 'typing-at-rest'
    Note-TypingLag $cpusLabel
}

function Invoke-GrowStep([string]$name, [string[]]$methods) {
    $list = ($methods | ForEach-Object { "UpgradeGrowTest#$_" }) -join ','
    $growCode = Invoke-Tests $name $list
    Note-Check "The older build took the grow step $name" ($growCode -eq 0) "runner exit code $growCode, see $name.json"
    $json = Join-Path $OutDir "$name.json"
    if (Test-Path -LiteralPath $json) {
        $results = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable
        Say "What the shots took, emulator, translated, all CPUs: $($results.recorded | ConvertTo-Json -Compress -Depth 4)"
    }
    Note-ProcessDeaths "by the end of grow step $name"
}

# On A and B the rows must be what the shape says, or the run would go on to prove something else.
function Test-GrownStore($before, [string]$dir, [string]$prefs) {
    if ($before) {
        $share = if ($before.rows_total -gt 0) { [math]::Round(100.0 * $before.orders.trigram / $before.rows_total, 1) } else { 0 }
        Note-Check "The older build wrote at least $GrowRowFloor rows" ($before.rows_total -ge $GrowRowFloor) "$($before.rows_total) rows"
        Note-Check 'At least 95 percent of the rows are three-word rows' ($share -ge 95) "$share percent, rows by order $($before.orders | ConvertTo-Json -Compress)"
    }
    $main = Get-MainOnly $dir
    if ($main -and $main.readable) {
        Note-Check 'At least 70000 rows are in the main file' ($main.rows_in_main -ge 70000) "$($main.rows_in_main) rows in $($main.db_bytes) bytes"
        $seedWords = 'xylqor', 'zorblax', 'qwibbit', 'drumwick', 'flumbo', 'grentix', 'velvet', 'harbor', 'mirble'
        $inMain = @($seedWords | Where-Object { $main.watch_in_main -contains $_ })
        if ($Shape -eq 'B') {
            Note-Check 'No word of the seed is in the main file, so they sit only in the log' ($inMain.Count -eq 0) "in the main file: $($inMain -join ', ')"
        } else {
            Note-Check 'Every word of the seed is in the main file, so everything is folded in' ($inMain.Count -eq $seedWords.Count) "in the main file: $($inMain -join ', ')"
        }
    } else {
        Note-Check 'The main file could be read alone' $false "$(if ($main) { $main.why } else { 'no answer, see the report' })"
    }
    $triggers = 'lantern', 'walnut', 'teapot', 'pebble', 'thimble', 'saddle', 'kettle', 'pumpkin', 'blanket'
    $left = @($triggers | Where-Object { $prefs -match "(?m)(^|>)$_`t" })
    Note-Check 'The grow text is gone from the settings' (($prefs.Length -lt 20480) -and ($left.Count -eq 0)) "$($prefs.Length) characters, trigger lines left: $($left -join ', ')"
}

function Close-Checks {
    if (-not $StopAfterSeed -and $checks.Count -lt 60) { Note-Check 'A full run made at least 60 checks' $false "$($checks.Count) checks, so some never ran" }
    $failed = @($checks | Where-Object { -not $_.passed })
    $checks | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutDir 'upgrade-checks.json') -Encoding utf8
    Say "$($checks.Count) checks, $($failed.Count) failed"
    $failed | ForEach-Object { Say "  FAILED $($_.check): $($_.evidence)" }
    $script:code = if ($failed.Count -gt 0) { 1 } else { 0 }
}

function Invoke-Control {
    $apk = if ($ControlApk) { $ControlApk } else { $NewApk }
    Install-Build $apk @('-r', '-d', '-g')
    Clear-App
    $controlCode = Invoke-Tests 'control' 'UpgradeControlTest'
    Say "Control on $(Split-Path -Leaf $apk), runner exit code ${controlCode}: $(Get-TestReport (Join-Path $OutDir 'control.json'))"
    if ($apk -ne $NewApk) { Install-Build $NewApk @('-r', '-d', '-g') }
}

# What each kill's copy shows. These are read once the series is over, because the reads take a while on a grown store and the
# first start goes on meanwhile. It adds what it finds to each kill's entry in the series.
function Read-KillSeries($series, $dropped, $before) {
    for ($i = 0; $i -lt $series.Count; $i++) {
        $dir = Join-Path $OutDir "kill-$i"
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $seen = Read-KillStore $dir
        Note-CrashLog "After kill $i the crash log holds no error the keyboard lived through" (Join-Path $dir 'last_crash.txt')
        # A copy the store moved under is no one state of the store, so it says nothing either way. It counts for no kill, and
        # by itself it fails nothing, because a failure here can cost a rerun of the whole hold.
        $series[$i].torn = Test-Path -LiteralPath (Join-Path $dir 'torn.txt')
        if ($series[$i].torn) {
            Say "Kill $i's copy is torn, so nothing is judged from it and it counts for no kill."
            Remove-StoreCopies $dir
            continue
        }
        if ($seen) {
            Say "After kill ${i}: version $($seen.user_version), ac_migration '$($seen.style_stats.ac_migration)', fragments $($seen.fragments_total), integrity $($seen.integrity), wal $($seen.file | ConvertTo-Json -Compress)"
            $series[$i].version = $seen.user_version
            $series[$i].marker = "$($seen.style_stats.ac_migration)"
            $series[$i].fragments = $seen.fragments_total
            $series[$i].rows = $seen.rows_total
            $series[$i].integrity = $seen.integrity
            $series[$i].file = $seen.file
            Note-Check "After kill $i the store is whole and the same store" (($seen.integrity -eq 'ok') -and $before -and ($seen.salt -eq $before.salt)) "integrity $($seen.integrity), salt $($seen.salt), version $($seen.user_version), marker '$($seen.style_stats.ac_migration)', rows $($seen.rows_total), fragments $($seen.fragments_total)"
            # A kill after the commit differs from before by design, so this asks only of a store still at the old version.
            if ($seen.user_version -eq 5) { Note-OldStoreUnchanged $i $seen $before }
            if ($series[$i].step -match '^main' -and $series[$i].landed -eq 'yes') {
                Note-Check "Kill $i, during the fold, left a whole store at version 6" (($seen.integrity -eq 'ok') -and ($seen.user_version -eq 6)) "integrity $($seen.integrity), version $($seen.user_version), marker '$($seen.style_stats.ac_migration)', rows $($seen.rows_total), file $($seen.file | ConvertTo-Json -Compress)"
            }
            # The test of a commit: what it left half-written must count for nothing. Frames past the last commit can
            # be left over from an attempt before this one, so it also takes this attempt's own write to the log, and a
            # store still at the old version.
            $series[$i].insideTheCommit = ($series[$i].step -match '^wal') -and ($series[$i].landed -eq 'yes') -and ($seen.user_version -eq 5) -and ($seen.file.wal_frames_uncommitted -gt 0)
            if ($series[$i].insideTheCommit) {
                Note-Check "Kill $i landed inside the commit, and the store rolled back whole" (($seen.integrity -eq 'ok') -and ($seen.user_version -eq 5) -and $before -and ($seen.rows_total -eq $before.rows_total) -and ($seen.rows_digest -eq $before.rows_digest)) "$($seen.file.wal_frames_uncommitted) frames not committed, version $($seen.user_version), integrity $($seen.integrity), rows $($seen.rows_total) against $($before.rows_total) before, digest $(if ($seen.rows_digest -eq $before.rows_digest) { 'the same' } else { 'different' })"
            }
            Remove-StoreCopies $dir
        } else {
            Note-Check "After kill $i the store is whole and the same store" $false 'it could not be read, see the report'
        }
        # A settings write renames the file to .bak first, and the system reads that back if it finds one. So that is what the file now holds.
        $bak = Join-Path $dir 'localboard_prefs.xml.bak'
        $holdsBak = Test-Path -LiteralPath $bak
        $prefsThen = Get-Content -LiteralPath $(if ($holdsBak) { $bak } else { Join-Path $dir 'localboard_prefs.xml' }) -Raw -ErrorAction SilentlyContinue
        $stamped = $prefsThen -match 'name="geometry_schema" value="2"'
        $fit = $prefsThen -match 'name="key_layout_fit"'
        $series[$i].settingsStamped = $stamped
        $series[$i].fitStillThere = $fit
        $series[$i].backupFile = $holdsBak
        Note-Check "After kill $i the settings are wholly old or wholly repaired" ($stamped -ne $fit) "stamped $stamped, fit still there $fit, backup file $holdsBak"
    }
    # A kill that did not land did not test anything, however well the store came through it. Nor did one whose copy was torn.
    $mustLand = @($(if ($Shape -ne 'Small') { 'prefs', 'shm', 'wal' }) + $Required | Select-Object -Unique)
    foreach ($needed in $mustLand) {
        $landedThere = @($series | Where-Object { $_.step -eq $needed -and $_.alive -eq 'yes' -and $_.landed -eq 'yes' -and -not $_.torn })
        Note-Check "A kill landed at '$needed'$(if ($Shape -ne 'Small') { ' on the grown store' })" ($landedThere.Count -gt 0) "$($landedThere.Count) of them"
    }
    if ($Shape -ne 'Small') {
        $inCommit = @($series | Where-Object { $_.insideTheCommit })
        Note-Check 'At least one kill landed inside the commit' ($inCommit.Count -gt 0) "at steps: $(($inCommit | ForEach-Object { "$($_.step) with $($_.file.wal_frames_uncommitted) frames" }) -join ', '); dropped: $(($dropped | ForEach-Object { $_.step }) -join ', ')"
    }
}

function Invoke-Upgrade {
    # 1. Where the new build draws its keys with nothing stored.
    Install-Build $NewApk @('-r', '-g')
    Clear-App
    if ((Invoke-Tests 'nominal' 'UpgradeNominalKeysTest') -ne 0) { throw 'Could not record where the keys belong.' }

    # 2. The older build, from cleared data.
    Install-Build $OldApk @('-r', '-d', '-g')
    $script:oldBuildOn = $true
    Clear-App
    Invoke-Adb logcat -b all -c 2>&1 | Out-Null
    $allShots = @(1..9 | ForEach-Object { 'shot{0:d2}' -f $_ })
    switch ($Shape) {
        'B' {
            Invoke-GrowStep 'grow' (@('a_prepare') + $allShots + 'z_blank')
            Invoke-SeedStep
        }
        'A' {
            Invoke-GrowStep 'grow' (@('a_prepare') + $allShots[0..7] + 'z_blank')
            Invoke-SeedStep
            Invoke-GrowStep 'grow-last' @('a_prepare', 'shot09', 'z_blank')
        }
        default { Invoke-SeedStep }
    }
    if ($Shape -ne 'Small') {
        # A process that has just started has a small heap, and the whole dump of it is read into memory here. The older
        # build stays running and selected, as it is on a phone at the moment of an update.
        Invoke-Adb shell am force-stop $AppPackage 2>&1 | Out-Null
        Select-KeyboardAfresh
        Wait-ForKeyboardProcess '' 60 | Out-Null
        if (-not (Wait-CpuIdle 60)) { Say 'The older build was still busy 60 s after it started, so the store key is taken anyway.' }
    } elseif (-not (Get-KeyboardPid)) {
        Select-KeyboardAfresh
        Wait-ForKeyboardProcess '' 30 | Out-Null
    }

    $beforeDir = Save-Snapshot 'before'
    Find-StoreKey $beforeDir
    if ($Shape -ne 'Small' -and -not (Test-Path -LiteralPath $passFile)) {
        Start-Sleep -Seconds 15
        Find-StoreKey $beforeDir
        if (-not (Test-Path -LiteralPath $passFile)) {
            Say 'Still no store key, so opening a field as a person would, which makes any build open its store.'
            Invoke-Adb shell am start -n "$hostActivity" 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            Invoke-TapOnField
            Wait-CpuIdle 60 | Out-Null
            Find-StoreKey $beforeDir
        }
    }
    $before = Read-StoreOrFail $beforeDir 'before the upgrade'
    $oldJobs = @(Wait-NightlyJobs $false 30)
    Note-Check 'The older build has its nightly job, and it does not wait for an idle phone' (($oldJobs.Count -gt 0) -and (@($oldJobs | Where-Object { $_ -match '\bIDLE\b' }).Count -eq 0)) "constraints: $($oldJobs -join ' | ')"
    $oldListing = Get-Content -LiteralPath (Join-Path $beforeDir 'listing.txt') -Raw
    $oldInode = [regex]::Match($oldListing, 'databases/localboard\.db:inode=(\d+)').Groups[1].Value
    $oldPrefs = Get-Content -LiteralPath (Join-Path $beforeDir 'localboard_prefs.xml') -Raw
    Say "Store files before the upgrade:`n$oldListing"
    if ($Shape -eq 'Small' -or $oldPrefs.Length -lt 20480) { Say "Settings before the upgrade:`n$oldPrefs" }
    if ($before) {
        Say "Store before the upgrade: $((Get-Content -LiteralPath (Join-Path $beforeDir 'store.txt') -Raw))"
        Note-Check 'The older build wrote a real version 5 store' (($before.user_version -eq 5) -and ($before.orders.trigram -gt 10)) `
            "version $($before.user_version), rows by order $($before.orders | ConvertTo-Json -Compress), file $($before.file | ConvertTo-Json -Compress), salt $($before.salt)"
        Note-Check 'The old fragments exist before the upgrade' ($before.fragments_total -gt 0) "$($before.fragments_total) rows, the first of them $($before.fragments | ConvertTo-Json -Compress)"
    }
    $shapeFailures = 0
    if ($Shape -ne 'Small') {
        $failedSoFar = @($checks | Where-Object { -not $_.passed }).Count
        Test-GrownStore $before $beforeDir $oldPrefs
        $shapeFailures = @($checks | Where-Object { -not $_.passed }).Count - $failedSoFar
    }
    foreach ($needed in 'name="key_layout_fit"', 'name="key_offset_x"', 'name="key_offset_y"', 'name="tap_model_data"', 'name="nav_strip_mode" value="3"', 'name="swipe_bias_data"', 'name="llm_suggestions" value="true"', 'name="llm_suggestions_reset_v2"') {
        Note-Check "Before the upgrade the file holds $needed" ($oldPrefs -match [regex]::Escape($needed)) ''
    }
    $oldKeystore = Get-Content -LiteralPath (Join-Path $beforeDir 'localboard_keystore.xml') -Raw
    Note-ProcessDeaths 'on the older build'
    Note-BuildTested 'nominal' $newHash 'new'
    Note-BuildTested 'seed' $oldHash 'older'
    if ($Shape -ne 'Small') { Note-BuildTested 'grow' $oldHash 'older' }
    Remove-StoreCopies $beforeDir
    if ($StopAfterSeed) {
        Close-Checks
        return
    }
    # An upgrade of the wrong store would hold the queue for half an hour to prove something else.
    if ($shapeFailures -gt 0) {
        Say 'This store is not the shape asked for, so the upgrade is not run on it.'
        Close-Checks
        return
    }

    # 3. The new build over it, with the older build running and selected, as on a phone.
    Set-EmulatorCpus $EmulatorCpus
    Invoke-Adb logcat -b all -c 2>&1 | Out-Null
    $oldPid = Get-KeyboardPid
    $imeBefore = Get-AdbText shell settings get secure default_input_method
    Say "Upgrading over process $oldPid, selected keyboard $imeBefore, emulator CPUs $EmulatorCpus (0 means all)"

    $newPid = ''
    $cpusLabel = if ($EmulatorCpus -gt 0) { "$EmulatorCpus" } else { 'all' }
    Start-StorePoll
    $firstStartSince = Get-DeviceEpoch
    $script:mainAtUpgrade = Invoke-RunAs 'stat -c %s:%y databases/localboard.db 2>&1'
    if ($KillDuringFirstStart) {
        $watchLocal = Join-Path $OutDir 'killwatch.sh'
        [IO.File]::WriteAllText($watchLocal, $killWatch.Replace("`r`n", "`n"))
        Invoke-Adb push $watchLocal $remoteWatch 2>&1 | Out-Null
        $last = if ($oldPid) { $oldPid } else { 'none' }
        $delays = @($KillDelays | ForEach-Object { $_ -split "," })
        $routes = [System.Collections.Generic.Queue[string]]::new([string[]]@($BringBackRoutes | ForEach-Object { $_ -split "," } | Where-Object { $_ }))
        $series = [System.Collections.Generic.List[object]]::new()
        $dropped = [System.Collections.Generic.List[object]]::new()
        $sinceKill = $null
        $killedAt = $null
        $said = ''
        for ($i = 0; $i -lt $delays.Count; $i++) {
            $step = $delays[$i]
            $patience = if ($step -match '\+') { 15 } else { $NoHelpSeconds }
            $log = Join-Path $OutDir "kill-$i.txt"
            $limit = if ($i -eq 0) { 100 } else { $patience + 90 }
            $copy = if ($Shape -eq 'Small') { '-' } else { "cache/kill-copy-$i" }
            $watch = Start-Process -FilePath $adb -ArgumentList @('-s', $device, 'shell', 'sh', $remoteWatch, $AppPackage, $last, $step, "$limit", "$KillWatchSeconds", $copy) -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -PassThru
            $script:watchProcess = $watch
            $broughtBy = 'the update and a field opening'
            if ($i -eq 0) {
                Install-Build $NewApk @('-r', '-g')
                $script:oldBuildOn = $false
                # Nothing starts the new build until a field wants a keyboard, on this emulator at least.
                Invoke-Adb shell am start -n "$hostActivity" 2>&1 | Out-Null
                if (-not $watch.WaitForExit(8000)) {
                    Say 'Eight seconds after a field opened there is still no process, so selecting the keyboard afresh.'
                    $broughtBy = 'a change of selection'
                    Select-KeyboardAfresh
                }
            } else {
                # What the kill before this one left, read while the process is gone.
                Save-KillSnapshot "kill-$($i - 1)" $previousCopy | Out-Null
                # The steps before the store opens are the ones that have left a process with nobody to restart it.
                $route = if ($delays[$i - 1] -in '0', 'prefs' -and $routes.Count -gt 0) { $routes.Dequeue() } else { '' }
                $series[$i - 1].routeTried = $route
                $broughtBy = Invoke-BringBack $last $watch $patience $sinceKill "kill-$($i - 1)" $route
                $series[$i - 1].broughtBackAfterwardsBy = $broughtBy
            }
            $previousCopy = $copy
            $watch | Wait-Process
            $sinceKill = [Diagnostics.Stopwatch]::StartNew()
            $said = (Get-Content -LiteralPath $log -Raw).Trim()
            Say "Kill $i, at '$step': $said"
            $entry = [ordered]@{ step = $step; broughtBackBy = $broughtBy; said = $said }
            if ($said -notmatch 'pid=(\d+) seen=([\d.]+) killed=([\d.]+) alive=(\w+) landed=(\w+)') {
                Say 'No process to kill, so the series ends here.'
                $series.Add($entry)
                Note-Check "Kill $i, at '$step', landed" $false "$said; what was to bring it back: $broughtBy"
                break
            }
            $last = $Matches[1]
            $seenAt = [double]$Matches[2]
            $thisKilledAt = [double]$Matches[3]
            $entry.alive = $Matches[4]
            $entry.landed = $Matches[5]
            $entry.pid = $last
            if ($entry.alive -eq 'yes') { $script:killedPids[$last] = $true }
            if ($step -match '^main' -and $entry.landed -eq 'yes') { $script:mainKillLanded = $true }
            $entry.secondsAliveBeforeKill = [math]::Round($thisKilledAt - $seenAt, 3)
            if ($killedAt) {
                $entry.secondsFromLastKill = [math]::Round($seenAt - $killedAt, 3)
                $series[$i - 1].secondsUntilBackAfterwards = $entry.secondsFromLastKill
                Say ("Back {0:N1} s after kill {1}, thanks to {2}." -f $entry.secondsFromLastKill, ($i - 1), $broughtBy)
            }
            $killedAt = $thisKilledAt
            $landedHere = ($entry.alive -eq 'yes') -and (($step -eq '0') -or ($entry.landed -eq 'yes'))
            # Only a log past the checkpoint's limit folds at the commit, so the main file need not move at all on this store.
            $mayMiss = ($step -match '^main') -and ($Required -notcontains $step)
            Note-Check "Kill $i, at '$step', landed" ($landedHere -or $mayMiss) "$said; brought back by $broughtBy$(if ($mayMiss -and -not $landedHere) { '; the main file did not move in time, so this kill counts as not landed, which is allowed unless -Required names it' })"
            $series.Add($entry)
            if ($i -lt $delays.Count - 1 -and $delays[$i + 1] -match '\+') {
                # A step with a wait only looks for what the ones before it missed.
                $peek = Save-KillSnapshot "kill-$i" $copy
                $seen = Read-KillStore $peek
                if ($seen -and $seen.user_version -eq 6 -and $seen.style_stats.ac_migration) {
                    Say "The first start finished before kill $i landed, or with it, so nothing is left to interrupt."
                    for ($j = $i + 1; $j -lt $delays.Count; $j++) {
                        $dropped.Add([ordered]@{ step = $delays[$j]; dropped = $true; why = "the first start had finished by kill $i" })
                        Say "Kill at '$($delays[$j])' dropped: the first start had finished by kill $i."
                    }
                    break
                }
            }
        }
        $finalKill = $series.Count - 1
        if ($said -match 'pid=') { Save-KillSnapshot "kill-$finalKill" $copy | Out-Null }
        $route = if ($delays[$finalKill] -in '0', 'prefs' -and $routes.Count -gt 0) { $routes.Dequeue() } else { '' }
        $series[$finalKill].routeTried = $route
        $series[$finalKill].broughtBackAfterwardsBy = Invoke-BringBack $last $null $NoHelpSeconds $sinceKill "kill-$finalKill" $route
        $series[$finalKill].secondsUntilBackAfterwards = [math]::Round($sinceKill.Elapsed.TotalSeconds, 1)
        Say "After the last kill, back $($series[$finalKill].secondsUntilBackAfterwards) s on, thanks to $($series[$finalKill].broughtBackAfterwardsBy)."
        Note-Check 'After the last kill something brought the process back' ($series[$finalKill].broughtBackAfterwardsBy -ne 'nothing') $series[$finalKill].broughtBackAfterwardsBy
        # A process is not yet a keyboard. A person's next step is to tap the field and expect the keys.
        Invoke-TapOnField
        $shown = $false
        $showClock = [Diagnostics.Stopwatch]::StartNew()
        while (-not $shown -and $showClock.Elapsed.TotalSeconds -lt 20) {
            $shown = (Get-AdbText shell dumpsys input_method) -match 'mInputShown=true'
            if (-not $shown) { Start-Sleep -Milliseconds 300 }
        }
        Note-Check 'After the last bring back a tap in the field shows the keyboard' $shown ("{0:N1} s after the tap, mInputShown={1}" -f $showClock.Elapsed.TotalSeconds, $shown)
        if ($TypeDuringFirstStart) { Invoke-TypingStep }
        Read-KillSeries $series $dropped $before
        ConvertTo-Json -InputObject (@($series) + @($dropped)) -Depth 5 | Set-Content -LiteralPath (Join-Path $OutDir 'kill-series.json') -Encoding utf8
        Note-ProcessDeaths 'through the kills'
        $newPid = Get-KeyboardPid
    } else {
        Install-Build $NewApk @('-r', '-g')
        $script:oldBuildOn = $false
    }
    $imeAfter = Get-AdbText shell settings get secure default_input_method
    Note-Check 'The update left the keyboard selected' ($imeAfter -eq $imeBefore) "selected keyboard $imeAfter, process now '$(Get-KeyboardPid)'"

    # 4. On a phone the first show is what starts the new build. Only a plain run with no typing asks that: there this test is
    # the first thing to show the keyboard after the update. Kills and typing show it earlier, so the report says this is a later
    # show. Then the files. The kill and the second read have nothing typed between them.
    if ($TypeDuringFirstStart -and -not $KillDuringFirstStart) { Invoke-TypingStep }
    $shownEarlierBy = @(
        if ($KillDuringFirstStart) { 'the tap after the last kill' }
        if ($TypeDuringFirstStart) { 'the typing' }
    )
    $firstShowName = if ($shownEarlierBy.Count) { "A later show came up right, and it is not the first show: $($shownEarlierBy -join ' and ') showed the keyboard before it" } else { 'The first show after the update came up right, with nothing showing the keyboard before it' }
    $firstShowCode = Invoke-TestsReadingTheLogFirst 'first-show' 'UpgradeVerifyTest#a_theFirstShow_fitsWithTheKeysAtNominal'
    Note-Check $firstShowName ($firstShowCode -eq 0) (Get-TestReport (Join-Path $OutDir 'first-show.json'))
    Note-BuildTested 'first-show' $newHash 'new'
    Note-StepClean 'first-show'
    # A read of the store in a loop would not do: the kill after it could land mid-migration and pass for a second start.
    $settled = Wait-StoreQuiet $firstStartSince 600
    $firstDir = Save-Snapshot 'first-start'
    $first = Read-StoreOrFail $firstDir 'after the first start'
    $newJobs = @(Wait-NightlyJobs $true 30)
    Note-Check 'The nightly job now waits for an idle phone, and the older job is gone' (($newJobs.Count -eq 1) -and ($newJobs[0] -match '\bIDLE\b')) "constraints: $($newJobs -join ' | ')"
    Note-Check 'The first start went quiet within 10 minutes' $settled.quiet $(if ($settled.quiet) { "process $($settled.process), $($settled.secondsToQuiet) s from its start to its last change, emulator, translated, $cpusLabel CPUs" } else { 'its files or its CPU were still moving' })
    Note-Check 'The first start finished, at version 6 with its marker' ($first -and $first.user_version -eq 6 -and $first.style_stats.ac_migration -eq '1') "version $($first.user_version), marker '$($first.style_stats.ac_migration)'"
    $windowRows = @((Read-StorePoll) | Where-Object { $_.at -ge $firstStartSince })
    $peakBytes = ($windowRows | Measure-Object -Property bytes -Maximum).Maximum
    $beforeBytes = (Get-StoreBytes $oldListing '') + (Get-StoreBytes $oldListing '-wal')
    Say ("Peak main plus log through the first start: {0:N1} MB, against {1:N1} MB before, {2:N2} times that, emulator, translated, {3} CPUs." -f ($peakBytes / 1MB), ($beforeBytes / 1MB), ($peakBytes / [math]::Max($beforeBytes, 1)), $cpusLabel)
    Say "Store after the first start: $((Get-Content -LiteralPath (Join-Path $firstDir 'store.txt') -Raw))"
    # The floor on the rows below is there to prove the doubling, and it would let a tenth of them vanish. This is what guards them.
    Note-RowsKept 'Every row the first start has no reason to touch is still there, with a count not lower' (Get-RowDiff $beforeDir $firstDir @('--first-start', '--allow', 'plimvet,grondux'))
    if ($KillDuringFirstStart) { Note-FoldKillRows $series $firstDir $first }
    $firstListing = Get-Content -LiteralPath (Join-Path $firstDir 'listing.txt') -Raw
    Say "Store files after the first start:`n$firstListing"
    $newInode = [regex]::Match($firstListing, 'databases/localboard\.db:inode=(\d+)').Groups[1].Value
    $firstPrefs = Get-Content -LiteralPath (Join-Path $firstDir 'localboard_prefs.xml') -Raw
    Say "Settings after the first start:`n$firstPrefs"

    $crashFile = Join-Path $firstDir 'last_crash.txt'
    Note-Check 'Nothing has reached the crash log since the first-show step' (-not (Test-Path -LiteralPath $crashFile) -or (Get-Item -LiteralPath $crashFile).Length -eq 0) $(if (Test-Path -LiteralPath $crashFile) { Get-Content -LiteralPath $crashFile -Raw } else { 'no file' })
    $crashed = @(Save-CrashBuffer 'first-start')
    Note-Check 'logcat -b crash has nothing of this app after the first start' ($crashed.Count -eq 0) ($crashed -join ' | ')
    $worrying = @(Save-KeyboardLog 'first-start')
    Note-Check 'The log has no store failure after the first start' ($worrying.Count -eq 0) (($worrying | Select-Object -First 8) -join ' | ')
    Note-Check 'The key to the store did not change' ($oldKeystore -eq (Get-Content -LiteralPath (Join-Path $firstDir 'localboard_keystore.xml') -Raw)) ''
    Note-Check 'The store file is the same file' ($oldInode -and $oldInode -eq $newInode) "inode $oldInode before, $newInode after"

    if ($first) {
        Note-Check 'The store is whole after the first start' ($first.integrity -eq 'ok') "integrity $($first.integrity)"
    }
    if ($TypeDuringFirstStart -and $first) {
        foreach ($word in 'plimvet', 'grondux') {
            Note-Check "'$word', typed during the first start, reached the store" ((Get-Rows $first $word).Count -gt 0) (Show-Rows (Get-Rows $first $word))
        }
    }
    if ($before -and $first) {
        Note-Check 'The store was not recreated: its random salt is unchanged' ($before.salt -eq $first.salt) "$($before.salt) to $($first.salt)"
        Note-Check 'The store is now at version 6' ($first.user_version -eq 6) "version $($first.user_version), integrity $($first.integrity)"
        Note-Check 'The schema identity did not change' ($before.identity_hash -eq $first.identity_hash) "$($before.identity_hash) to $($first.identity_hash)"
        Note-Check 'The fragments are gone from the store' ($first.fragments_total -eq 0) "$($first.fragments_total) rows, the first of them $($first.fragments | ConvertTo-Json -Compress)"
        if ($Shape -ne 'Small') {
            # Under this the doubling was never exercised, whatever else passed.
            $grew = [math]::Round($first.rows_total / [math]::Max($before.rows_total, 1), 2)
            Note-Check 'The first start at least 1.8 times the rows' ($first.rows_total -ge 1.8 * $before.rows_total) "$($before.rows_total) rows to $($first.rows_total), $grew times, rows by order $($first.orders | ConvertTo-Json -Compress)"
        }
        foreach ($word in 'xylqor', 'zorblax', 'qwibbit', 'drumwick', 'flumbo', 'grentix', 'velvet', 'harbor') {
            $count = Get-Unigram $first $word
            Note-Check "'$word' has its word row" ($count -gt 1) "count $count, rows before $(Show-Rows (Get-Rows $before $word)), rows after $(Show-Rows (Get-Rows $first $word))"
        }
        Note-Check 'The struck word left with every row' ((Get-Rows $first 'mirble').Count -eq 0) "rows before $(Show-Rows (Get-Rows $before 'mirble')), rows after $(Show-Rows (Get-Rows $first 'mirble'))"
        Note-Check 'The one-time verdict step ran' ($first.style_stats.ac_migration -eq '1' -and -not $first.style_stats.typo_strikes -and -not $first.style_stats.typo_suppressions) "ac_migration '$($first.style_stats.ac_migration)', strikes before '$($before.style_stats.typo_strikes)', after '$($first.style_stats.typo_strikes)', suppressions before '$($before.style_stats.typo_suppressions)', after '$($first.style_stats.typo_suppressions)'"
        foreach ($table in 'style_stats', 'app_context', 'usage_stats', 'typing_log', 'distillations') {
            $bothHold = $before.tables.Contains($table) -and $first.tables.Contains($table)
            Note-Check "Table $table lost nothing" ($bothHold -and ($first.tables[$table] -ge $before.tables[$table])) "$($before.tables[$table]) rows to $($first.tables[$table])$(if (-not $bothHold) { ', and a table that is not there cannot be said to have kept anything' } elseif ($before.tables[$table] -eq 0) { ', so it had nothing to lose and this asks nothing of it' })"
        }

        # The next ones mean something only if the older build wrote what they ask about.
        $wonBefore = (Get-Rows $before 'won')
        $wonTotal = ($wonBefore | ForEach-Object { $_[3] } | Measure-Object -Sum).Sum
        Note-Check 'Before the upgrade the store holds rows of don and haven, and won more than 3 times' (((Get-Rows $before 'don').Count -gt 0) -and ((Get-Rows $before 'haven').Count -gt 0) -and ($wonTotal -gt 3)) "don $(Show-Rows (Get-Rows $before 'don')), haven $(Show-Rows (Get-Rows $before 'haven')), won $(Show-Rows $wonBefore)"
        Note-Check 'Before the upgrade some rows have a piece of a contraction as their context' ($before.fragment_contexts_total -gt 0) "$($before.fragment_contexts_total) rows, the first of them $($before.fragment_contexts | ConvertTo-Json -Compress)"
        Note-Check 'Before the upgrade some rows have the struck word as their context' ((Get-ContextRows $before 'mirble').Count -gt 0) (Show-Rows (Get-ContextRows $before 'mirble'))
        Note-Check 'No row has a piece of a contraction as its context' ($first.fragment_contexts_total -eq 0) "$($before.fragment_contexts_total) before, now $($first.fragment_contexts_total), the first of them $($first.fragment_contexts | ConvertTo-Json -Compress)"
        Note-Check 'No row has the struck word as its context' ((Get-ContextRows $first 'mirble').Count -eq 0) "$((Get-ContextRows $before 'mirble').Count) before, now $(Show-Rows (Get-ContextRows $first 'mirble'))"
        Note-Check 'The list of struck words is kept in its own row' ("$($first.style_stats.ac_migration_struck_words)" -match '(^|,)mirble(,|$)') "ac_migration_struck_words '$($first.style_stats.ac_migration_struck_words)'"
        foreach ($word in 'don', 'haven') {
            $rows = @((Get-Rows $first $word)) + @((Get-ContextRows $first $word))
            Note-Check "$word has no rows, as a word or as context" ($rows.Count -eq 0) "$((Get-Rows $before $word).Count) rows as a word before, now $(Show-Rows $rows)"
        }
        $wonRows = (Get-Rows $first 'won')
        Note-Check 'won is at 3 or less in every row' (@($wonRows | Where-Object { $_[3] -gt 3 }).Count -eq 0) "before $(Show-Rows $wonBefore), now $(Show-Rows $wonRows)"

        $oldBytes = Get-StoreBytes $oldListing ''
        $oldWal = Get-StoreBytes $oldListing '-wal'
        $firstBytes = Get-StoreBytes $firstListing ''
        $firstWal = Get-StoreBytes $firstListing '-wal'
        Say "Store on disk before the first start: main $oldBytes bytes, log $oldWal bytes, $($before.rows_total) rows, file $($before.file | ConvertTo-Json -Compress). After it: main $firstBytes bytes, log $firstWal bytes, $($first.rows_total) rows, file $($first.file | ConvertTo-Json -Compress)."
        # Only on the small store. On a grown one the automatic checkpoint would pass this even if the hide's had regressed.
        if ($Shape -eq 'Small') {
            Note-Check 'The main store file grew once the keyboard had hidden' ($firstBytes -gt $oldBytes) "$oldBytes bytes on the older build after all its hides, $firstBytes after one hide on the new build"
        }
    } else {
        Note-Check 'The store checks of the first start ran' $false "read before the upgrade: $([bool]$before), read after the first start: $([bool]$first)"
    }
    Remove-StoreCopies $firstDir
    Note-ProcessDeaths 'through the first start'
    foreach ($gone in 'key_layout_fit', 'tap_model_data', 'key_offset_x', 'key_offset_y', 'swipe_bias_data', 'llm_suggestions_reset_v2') {
        Note-Check "After the first start the file no longer holds $gone" ($firstPrefs -notmatch "name=`"$gone`"") ''
    }
    Note-Check 'After the first start nav_strip_mode is 0' ($firstPrefs -match 'name="nav_strip_mode" value="0"') ''
    Note-Check 'After the first start the stamp is on disk' ($firstPrefs -match 'name="geometry_schema" value="2"') ''
    Note-Check 'After the first start the choice of llm_suggestions stands' ($firstPrefs -match 'name="llm_suggestions" value="true"') ''

    # After the first start's reads, so its rows and settings hold none of this. The second start's compare sets these words aside.
    $prefsBeforeSecondStart = $firstPrefs
    if ($TypeDuringFirstStart) {
        Invoke-TypingAtRestStep $cpusLabel
        $prefsCopy = Join-Path $OutDir 'prefs-after-typing-at-rest.xml'
        Save-DeviceFile $prefsFile $prefsCopy
        $prefsBeforeSecondStart = Get-Content -LiteralPath $prefsCopy -Raw
    }

    # The limit is for the race at a first show and the typing set against the same limit. The rest asks about other things and
    # takes half the time without it.
    Set-EmulatorCpus 0

    # Second start, nothing typed since the last read or the typing at rest.
    $pidNow = Get-KeyboardPid
    $secondStartSince = Get-DeviceEpoch
    $killSaid = Invoke-RunAs "kill -9 $pidNow && echo killed"
    $again = Wait-ForKeyboardProcess $pidNow 120
    if (-not $again) {
        Say "Killed $pidNow, and no process two minutes on, so changing the selection."
        Select-KeyboardAfresh
        $again = Wait-ForKeyboardProcess $pidNow 45
    }
    Say "Killed $pidNow, and now it is process $again"
    $secondSettled = Wait-StoreQuiet $secondStartSince 600
    Stop-StorePoll
    Say $(if ($secondSettled.quiet) { "Second start: $($secondSettled.secondsToQuiet) s from the process's start to idle, with $(if ($first) { $first.rows_total } else { '?' }) rows, emulator, translated, all CPUs." } else { 'Second start: not idle within 10 minutes.' })
    Note-Check 'The second start went quiet within 10 minutes' $secondSettled.quiet "process $($secondSettled.process), $($secondSettled.secondsToQuiet) s, emulator, translated, all CPUs"
    # Without this the checks below could compare the store with itself and pass with no second start.
    Note-Check 'The second start really was a new process' (($killSaid -match 'killed') -and $again -and ($again -ne $pidNow) -and ($secondSettled.process -ne $pidNow)) "killed '$pidNow' ('$killSaid'), then process '$again', and the one that went quiet was '$($secondSettled.process)'"
    $killDir = Save-Snapshot 'after-kill'
    $afterKill = Read-StoreOrFail $killDir 'after a second start'
    $secondDiff = Get-RowDiff $firstDir $killDir @('--allow', 'plimvet,grondux')
    $killPrefs = Get-Content -LiteralPath (Join-Path $killDir 'localboard_prefs.xml') -Raw
    Note-Check 'A second start left the settings file as it was' ($killPrefs -eq $prefsBeforeSecondStart) $(if ($killPrefs -eq $prefsBeforeSecondStart) { 'identical bytes' } else { "now:`n$killPrefs" })
    if ($afterKill) {
        Note-Check 'The store is whole after a second start' ($afterKill.integrity -eq 'ok') "integrity $($afterKill.integrity)"
    }
    if ($first -and $afterKill) {
        # On a run that typed at rest those words sit between the two reads, so there the digest must differ by them and nothing else.
        $sameRows = ($first.rows_digest -eq $afterKill.rows_digest) -or ($TypeDuringFirstStart -and $secondDiff -and (($secondDiff.gone + $secondDiff.new + $secondDiff.changed) -eq 0))
        Note-Check 'A second start changed no row and doubled no count' $sameRows "$($first.rows_total) rows to $($afterKill.rows_total), digest $($first.rows_digest) to $($afterKill.rows_digest)$(if ($TypeDuringFirstStart -and $secondDiff) { ", $($secondDiff.gone) gone, $($secondDiff.new) new and $($secondDiff.changed) changed apart from the $($secondDiff.expected_differences) rows of the words typed at rest" })"
        Note-Check 'A second start kept the marker and the version' (($afterKill.style_stats.ac_migration -eq '1') -and ($afterKill.user_version -eq 6)) "ac_migration '$($afterKill.style_stats.ac_migration)', version $($afterKill.user_version)"
    } else {
        Note-Check 'The store checks of a second start ran' $false "read after the first start: $([bool]$first), read after a second start: $([bool]$afterKill)"
    }
    Remove-StoreCopies $killDir

    # 5. What a person would see.
    $verifyCode = Invoke-TestsReadingTheLogFirst 'verify' $VerifyClass
    Note-Check 'Every step of UpgradeVerifyTest held' ($verifyCode -eq 0) "runner exit code $verifyCode, see verify.json"
    Note-BuildTested 'verify' $newHash 'new'
    Note-StepClean 'verify'
    $verifyJson = Join-Path $OutDir 'verify.json'
    if (Test-Path -LiteralPath $verifyJson) {
        $verify = Get-Content -LiteralPath $verifyJson -Raw | ConvertFrom-Json -AsHashtable
        foreach ($name in $verify.tests.Keys) {
            Say ("  {0} {1}{2}" -f $verify.tests[$name].result, $name, $(if ($verify.tests[$name].message) { "`n    $($verify.tests[$name].message)" } else { '' }))
        }
        Say "  recorded: $($verify.recorded | ConvertTo-Json -Depth 4)"
        Say "  shortShows: $($verify.shortShows | ConvertTo-Json -Compress)"
        Say "  swallowedErrors: $($verify.swallowedErrors | ConvertTo-Json -Compress -Depth 4)"
    }
    Start-Sleep -Seconds 4
    $endDir = Save-Snapshot 'end'
    $end = Read-StoreOrFail $endDir 'at the end'
    Say "Store at the end: $((Get-Content -LiteralPath (Join-Path $endDir 'store.txt') -Raw))"
    Note-CrashLog 'At the end the crash log holds no error the keyboard lived through' (Join-Path $endDir 'last_crash.txt')
    Note-RowsKept 'From the second start to the end no row went and no count fell' (Get-RowDiff $killDir $endDir @())
    if ($end) {
        Note-Check 'The store is whole at the end' ($end.integrity -eq 'ok') "integrity $($end.integrity)"
    }
    if ($first -and $end) {
        # Only the whole class types these words.
        if ($VerifyClass -eq 'UpgradeVerifyTest') {
            Note-Check 'What was typed after the upgrade reached the store' ((Get-Unigram $end 'zyxquor') -gt 0) (Show-Rows (Get-Rows $end 'zyxquor'))
            Note-Check 'The struck word, learned again, stayed through the kills' ((Get-Rows $end 'mirble').Count -gt 0) (Show-Rows (Get-Rows $end 'mirble'))
        }
        foreach ($word in 'xylqor', 'zorblax', 'qwibbit') {
            $was = Get-Unigram $first $word
            $is = Get-Unigram $end $word
            Note-Check "'$word' grew only by what was typed since" (($is -ge $was) -and ($is -le ($was + $mostGrowthThroughVerify))) "count $was after the migration, $is at the end"
        }
        Note-Check 'No fragment came back' ($end.fragments_total -eq 0) "$($end.fragments_total) rows, the first of them $($end.fragments | ConvertTo-Json -Compress)"
        Note-Check 'No row came back with a piece of a contraction as its context' ($end.fragment_contexts_total -eq 0) "$($end.fragment_contexts_total) rows, the first of them $($end.fragment_contexts | ConvertTo-Json -Compress)"
        foreach ($word in 'don', 'haven') {
            Note-Check "$word stayed out after the contractions were typed again" ((Get-Rows $end $word).Count -eq 0) (Show-Rows (Get-Rows $end $word))
        }
        $endListing = Get-Content -LiteralPath (Join-Path $endDir 'listing.txt') -Raw
        Say "Store on disk at the end: main $(Get-StoreBytes $endListing '') bytes, log $(Get-StoreBytes $endListing '-wal') bytes, $($end.rows_total) rows, file $($end.file | ConvertTo-Json -Compress)."
    } else {
        Note-Check 'The store checks at the end ran' $false "read after the first start: $([bool]$first), read at the end: $([bool]$end)"
    }
    Remove-StoreCopies $endDir
    Note-ProcessDeaths 'by the end'
    $crashed = @(Save-CrashBuffer 'end')
    Note-Check 'logcat -b crash has nothing of this app at the end' ($crashed.Count -eq 0) ($crashed -join ' | ')
    $worrying = @(Save-KeyboardLog 'end')
    Note-Check 'The log has no store failure at the end' ($worrying.Count -eq 0) (($worrying | Select-Object -First 8) -join ' | ')

    Close-Checks
}

# ---------------------------------------------------------------------------------------------------------------

# Neither of these needs a device, an APK or the queue.
if ($SelfCheck) {
    [IO.File]::WriteAllText($readStore, $readStorePy.Replace("`r`n", "`n"))
    Set-Content -LiteralPath $reportPath -Value "Self check of the store read, on a copy of $SelfCheck" -Encoding utf8
    $copy = Join-Path $OutDir 'self-check'
    Remove-Item -LiteralPath $copy -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $SelfCheck -Destination $copy -Recurse
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $SelfCheck) 'store-key.hex') -Destination $passFile -Force
    # A read that fails before the cut proves nothing about the cut: a folder with no store or no key would pass.
    if (-not (Read-Store $copy)) {
        Write-Output 'Self check FAILED: this copy could not be read even before it was cut, so a failing read would prove nothing.'
        exit 1
    }
    $mainCopy = Join-Path $copy 'localboard.db'
    [IO.File]::WriteAllBytes($mainCopy, [IO.File]::ReadAllBytes($mainCopy)[0..99])
    Remove-Item -LiteralPath "$mainCopy-wal" -ErrorAction SilentlyContinue
    $cut = Read-StoreOrFail $copy 'after its main file was cut short and its log removed'
    $sawFailed = @($checks | Where-Object { -not $_.passed }).Count -gt 0
    Write-Output $(if ($sawFailed -and -not $cut) { 'Self check held: the cut store gave a FAILED check.' } else { 'Self check FAILED: the cut store gave no failing check.' })
    exit $(if ($sawFailed -and -not $cut) { 0 } else { 1 })
}

if ($CompareWith) {
    $otherRun = (Resolve-Path -LiteralPath $CompareWith -ErrorAction SilentlyContinue).Path
    if (-not $otherRun) { Write-Output "There is no run at $CompareWith. Not compared."; exit 2 }
    # Any run holds the same rows as itself, so this would pass and prove nothing.
    if ($otherRun.TrimEnd('\', '/') -eq $OutDir.TrimEnd('\', '/')) { Write-Output "-CompareWith names this run's own folder, $OutDir. Not compared."; exit 2 }
    # Its own report, because this is usually asked of a run long finished, whose report is the evidence of that run.
    $reportPath = Join-Path $OutDir 'compare-report.txt'
    Set-Content -LiteralPath $reportPath -Value "The first start of $OutDir against the first start of $CompareWith" -Encoding utf8
    [IO.File]::WriteAllText($readStore, $readStorePy.Replace("`r`n", "`n"))
    # Two runs of two builds matching says nothing about either. A run from before there was such a file has no hash to compare.
    $builds = @(foreach ($run in $OutDir, $CompareWith) {
        $json = Join-Path $run 'first-show.json'
        if (Test-Path -LiteralPath $json) { "$((Get-Content -LiteralPath $json -Raw | ConvertFrom-Json -AsHashtable).appApkSha256)" } else { '' }
    })
    if ($builds[0] -and $builds[1]) {
        Note-Check 'Both runs tested the same build' ($builds[0] -eq $builds[1]) "$($builds[0]) and $($builds[1])"
    } else {
        Say "Whether both runs tested the same build cannot be said: this run's first-show.json gives '$($builds[0])' and the other's gives '$($builds[1])'."
    }
    $sides = foreach ($run in $OutDir, $CompareWith) {
        $side = Join-Path $run 'first-start'
        if (-not (Test-Path -LiteralPath (Join-Path $side 'rows.tsv'))) {
            # A run from before there was such a file, whose copy of the store is still there to read it from.
            Say (& $Python $readStore read --dir $side --keyfile (Join-Path $run 'store-key.hex') 2>&1 | Select-Object -First 2 | Out-String)
        }
        $side
    }
    $summaryFile = Join-Path $OutDir 'compare-summary.json'
    Remove-Item -LiteralPath $summaryFile -ErrorAction SilentlyContinue
    # What -TypeDuringFirstStart types is the one thing allowed to differ, with its context rows.
    Say (& $Python $readStore diff --a $sides[0] --b $sides[1] --allow 'plimvet,grondux' --summary $summaryFile 2>&1 | Out-String)
    if (Test-Path -LiteralPath $summaryFile) {
        $summary = Get-Content -LiteralPath $summaryFile -Raw | ConvertFrom-Json -AsHashtable
        Say "This run: build '$($builds[0])', $($summary.rows_a) rows. The other: build '$($builds[1])', $($summary.rows_b) rows."
        Say "Rows typed during a first start, expected to differ: $($summary.expected_differences)"
        Note-Check 'No grown row differs between the runs' ($summary.in_grown_rows -eq 0) "$($summary.in_grown_rows) rows"
        Note-Check 'No other row differs between the runs' ($summary.in_other_rows -eq 0) "$($summary.in_other_rows) rows, naming these words: $($summary.other_rows_named -join ', ')"
        Note-Check 'Both runs hold the same rows, apart from lastUsed' (($summary.gone + $summary.new + $summary.changed) -eq 0) "$($summary.rows_a) and $($summary.rows_b) rows, $($summary.gone) gone, $($summary.new) new, $($summary.changed) changed"
    } else {
        Note-Check 'The rows of both runs could be compared' $false 'see the report'
    }
    exit $(if (@($checks | Where-Object { -not $_.passed }).Count -gt 0) { 1 } else { 0 })
}

# An earlier run's kill copies would be read as this run's, and a run that stopped early would leave another run's first start to
# compare. A log the caller sends here is no reason to stop, so this goes by what a run leaves.
if (-not $ControlOnly) {
    $earlier = @(Get-ChildItem -LiteralPath $OutDir -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -in 'upgrade-report.txt', 'read_store.py', 'store-key.hex', 'before', 'first-start', 'after-kill', 'end' -or $_.Name -match '^kill-' -or $_.Extension -eq '.json' })
    if ($earlier.Count -gt 0) { Write-Output "$OutDir holds an earlier run ($(($earlier | Select-Object -First 5).Name -join ', ')). Give this run a folder of its own. Not run."; exit 2 }
}
if (-not (Test-Path -LiteralPath $adb)) { Write-Output "adb not found at $adb"; exit 2 }
foreach ($apk in $OldApk, $NewApk) {
    if (-not (Test-Path -LiteralPath $apk)) { Write-Output "No APK at $apk"; exit 2 }
}
$oldHash = (Get-FileHash -LiteralPath $OldApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($OldSha256 -and $oldHash -ne $OldSha256.ToLowerInvariant()) { Write-Output "The older build is $oldHash, not $OldSha256"; exit 2 }
$newHash = (Get-FileHash -LiteralPath $NewApk -Algorithm SHA256).Hash.ToLowerInvariant()
$apkHashes[$OldApk] = $oldHash
$apkHashes[$NewApk] = $newHash
if ($ControlApk -and (Test-Path -LiteralPath $ControlApk)) { $apkHashes[$ControlApk] = (Get-FileHash -LiteralPath $ControlApk -Algorithm SHA256).Hash.ToLowerInvariant() }
[IO.File]::WriteAllText($readStore, $readStorePy.Replace("`r`n", "`n"))
Set-Content -LiteralPath $reportPath -Value "Upgrade run, older build $oldHash, new build $newHash" -Encoding utf8
Remove-Item -LiteralPath $passFile -ErrorAction SilentlyContinue

$mutex = New-Object System.Threading.Mutex($false, 'Local\IOKeyEmulator')
$held = $false
$code = 2
try {
    $wait = [Diagnostics.Stopwatch]::StartNew()
    try { $held = $mutex.WaitOne([TimeSpan]::FromMinutes($WaitMinutes)) }
    catch [System.Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) {
        Write-Output "The emulator stayed busy for $WaitMinutes minutes. Not run."
        $code = 3
    } else {
        Say ("Waited {0:N0}s for the emulator." -f $wait.Elapsed.TotalSeconds)
        $holding = [Diagnostics.Stopwatch]::StartNew()
        # The emulator's memory grows with the hours and at 9.5 GB it has died when the desktop ran short, so it starts each
        # long hold small. This holds the queue, so a restart cuts across nobody's test.
        $qemu = Get-Process -Name 'qemu-system-x86_64*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($EmuStart -and $qemu -and $qemu.PrivateMemorySize64 -gt 5.5GB) {
            Say ("The emulator has grown to {0:N1} GB. Restarting it before this run." -f ($qemu.PrivateMemorySize64 / 1GB))
            Invoke-Adb emu kill 2>&1 | Out-Null
            for ($i = 0; $i -lt 30 -and (Get-Process -Id $qemu.Id -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Seconds 2 }
            Stop-Process -Id $qemu.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 3
        }
        $emulatorUp = (Get-AdbText get-state) -eq 'device'
        if (-not $emulatorUp) {
            if (-not $EmuStart) { throw "$device is not running." }
            Say 'The emulator is down. Trying to start it.'
            & pwsh -NoProfile -File $EmuStart | ForEach-Object { Say "  $_" }
            if ($LASTEXITCODE -ne 0) { Say 'Could not start the emulator. Not run.'; $code = 4 } else { $emulatorUp = $true }
        }
        if ($emulatorUp) {
            $qemu = Get-Process -Name 'qemu-system-x86_64*' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($qemu) { Say ("Emulator memory at the start of this hold: {0:N1} GB" -f ($qemu.PrivateMemorySize64 / 1GB)) }
            Start-CommitWatch
            Set-EmulatorCpus 0
            Clear-Leftovers
            # This can come long after the file was hashed, because the queue can take most of an hour.
            Assert-BuildUnchanged $NewApk
            # Builds from another machine carry another debug key, and nothing installs over those. Every run here starts by clearing
            # the app's data and the runner installs the test APKs each step, so removing them loses nothing. This is only for the
            # start of a hold: later in a run a key that differs is a real failure, and it has to stay one.
            if ((Get-AdbText install -r -d -g $NewApk) -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
                Say 'The test installs on the device carry another debug key, so they go first.'
                foreach ($package in $AppPackage, 'io.ioservices.iokey.e2e', 'io.ioservices.iokey.e2e.test') {
                    Invoke-Adb uninstall $package 2>&1 | Out-Null
                }
            }
            $script:deathsSince = Get-DeviceClock
            if ($ControlOnly) { $code = 0 } else { Invoke-Upgrade }
            if (-not $NoControl -and -not $StopAfterSeed) { Invoke-Control }
        }
    }
} catch {
    Say "Stopped: $($_.Exception.Message)"
    Say "$($_.ScriptStackTrace)"
    $code = 2
} finally {
    if ($held) {
        Set-EmulatorCpus 0
        Stop-KillWatch
        Stop-StorePoll
        # Whoever runs next may run with -NoBuild, and would otherwise test the older build.
        if ($script:oldBuildOn) {
            try {
                Install-Build $NewApk @('-r', '-d', '-g')
                $script:oldBuildOn = $false
            } catch { Say "The new build did not go back on: $($_.Exception.Message)" }
        }
        Invoke-Adb shell rm -f $remoteHprof $remoteWatch 2>&1 | Out-Null
        if (-not $KeepData) {
            # What was typed here would look learned to any test that does not start by wiping.
            Invoke-Adb shell pm clear $AppPackage 2>&1 | Out-Null
        }
        Say-HoldSummary $holding
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}
exit $code
