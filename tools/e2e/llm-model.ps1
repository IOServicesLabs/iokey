<#
.SYNOPSIS
    Puts the small test models on the test emulator for LlmModelTest, and runs that test.

.DESCRIPTION
    Only ever talks to emulator-5554, and only ever pushes to its temporary folder, which a data wipe does not touch.
    Each test of LlmModelTest copies a file from there into the keyboard's own folder after its wipe, and skips with a
    clear message when the file is not there. So the rest of the suite neither sees them nor needs them.

    It does not install the app. As with run.ps1, install the build to test first, and build the test APKs.

    An ordinary small model will not do: the emulator runs the app's engines by translation, and that aborts on the
    half-precision cache of every attention model. make-llm-test-model.py, beside this script, writes two that run. They
    have random weights, and write word salad. Keep the files out of this repo.

.PARAMETER ModelPath
    The GGUF file for most of the tests, from make-llm-test-model.py.

.PARAMETER ResultsPath
    Where run.ps1 writes its results. Without it, the files are pushed and nothing is run.

.PARAMETER SlowModelPath
    The GGUF file from make-llm-test-model.py --slow, whose run is long enough for a test to stop it. The tests that
    stop a run skip without it.

.PARAMETER Class
    What to run, in the form run.ps1 takes for -Class.

.EXAMPLE
    pwsh -NoProfile -File tools\e2e\llm-model.ps1 -ModelPath C:\models\tiny.gguf -SlowModelPath C:\models\slow.gguf -ResultsPath C:\temp\llm.json
#>
#Requires -Version 7

param(
    [Parameter(Mandatory = $true)][string]$ModelPath,
    [string]$ResultsPath,
    [string]$SlowModelPath,
    [string[]]$Class = @('LlmModelTest')
)

$ErrorActionPreference = 'Stop'

$device = 'emulator-5554'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

function Push-Model([string]$local, [string]$remote) {
    if (-not (Test-Path -LiteralPath $local)) { throw "No model at $local" }
    & $adb -s $device push $local $remote
    if ($LASTEXITCODE -ne 0) { throw "Could not push $local" }
    & $adb -s $device shell chmod 644 $remote
}

Push-Model $ModelPath '/data/local/tmp/iokey-test-model.gguf'
if ($SlowModelPath) { Push-Model $SlowModelPath '/data/local/tmp/iokey-test-model-slow.gguf' }

if ($ResultsPath) {
    & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'run.ps1') $ResultsPath -SkipBuild -Class ($Class -join ',')
    exit $LASTEXITCODE
}
