#!/usr/bin/env pwsh
#
# run_tests.ps1 — Build the transpiler, then run all integration tests.
#
# Each subdirectory under tests/ is one test. All .kt files in the directory
# are transpiled together. Adding a new directory = adding a new test.
#
# Usage:
#   .\run_tests.ps1                        # Run all tests
#   .\run_tests.ps1 -Skip unit             # Skip unit tests, only run integration
#   .\run_tests.ps1 -Run HashMapTest       # Transpile, compile & run a single test
#   .\run_tests.ps1 -Run game -MemTrack    # Run single test with --mem-track
#   .\run_tests.ps1 -Run game -Ast         # Run single test with --ast
#   .\run_tests.ps1 -Run game -MemTrack -TranspilerArgs "--other"  # Combined
#   .\run_tests.ps1 -Compiler clang        # Use clang instead of auto-detected gcc
#   .\run_tests.ps1 -CCArgs "-j14 -O2"     # Pass flags to the C compiler
#   .\run_tests.ps1 -Build Jar             # Build fat JAR (default)
#   .\run_tests.ps1 -Build Gradle          # Build using gradle "run"
#   .\run_tests.ps1 -Build Proguard        # Build and use the ProGuard-optimized JAR
#
param(
    [string]$Skip = "",
    [string]$Run  = "",
    [string]$TranspilerArgs = "",
    [string]$Compiler = "",
    [string]$CCArgs = "",
    [switch]$Help,
    [switch]$MemTrack,
    [switch]$Ast,
    [string]$Build = "Jar"
)

if ($Help) {
    $inUsage = $false
    Get-Content $PSCommandPath | ForEach-Object {
        $line = $_.TrimStart()
        if ($line -match '^# Usage:') { $inUsage = $true }
        elseif ($inUsage -and $line -notmatch '^#') { $inUsage = $false; return }
        if ($inUsage) { Write-Host ($line -replace '^# ?', '') }
    }
    exit 0
}

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$jar        = "$root\build\libs\KotlinToC-1.0-SNAPSHOT.jar"
$releaseJar = "$root\build\libs\KotlinToC-1.0-SNAPSHOT-release.jar"
$outDir = "$root\test_out"
$testsDir = "$root\tests"

# ── Colors ──────────────────────────────────────────────────────
function Write-Pass($msg) { Write-Host "  PASS " -ForegroundColor Green -NoNewline; Write-Host $msg }
function Write-Fail($msg) { Write-Host "  FAIL " -ForegroundColor Red   -NoNewline; Write-Host $msg }
function Write-Info($msg) { Write-Host "  ---- " -ForegroundColor Cyan  -NoNewline; Write-Host $msg }
function Write-Section($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Yellow }
function Write-Cmd($msg) { Write-Host "  `$ " -ForegroundColor DarkYellow -NoNewline; Write-Host $msg -ForegroundColor White }

# ── Detect C compiler ───────────────────────────────────────────
$CC = if ($Compiler -ne "") { $Compiler } else { $null }
if (-not $CC) {
    foreach ($candidate in @("gcc", "clang", "cl")) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) {
            $CC = $candidate
            break
        }
    }
}
if (-not $CC) {
    Write-Host "ERROR: No C compiler found (tried gcc, clang, cl). Install one and add to PATH." -ForegroundColor Red
    exit 1
}

# ── Helper: transpile, compile, run one test directory ──────────
# Returns $true on success, $false on failure.
# When $Verbose is set, prints step-by-step output (for -Run mode).
function Invoke-Test {
    param(
        [string]$Name,
        [string]$TestSrcDir,
        [string]$TestOutDir,
        [bool]$Verbose = $false,
        [string]$ExtraArgs = ""
    )

    # ── Collect .kt files ───────────────────────────────────────
    $ktFiles = @(Get-ChildItem "$TestSrcDir\*.kt" | Select-Object -ExpandProperty FullName)
    if ($ktFiles.Count -eq 0) {
        if ($Verbose) { Write-Fail "$Name — no .kt files in $TestSrcDir" } else { Write-Fail "$Name (no .kt files)" }
        return $false
    }

    if (Test-Path $TestOutDir) { Remove-Item $TestOutDir -Recurse -Force }
    New-Item $TestOutDir -ItemType Directory -Force | Out-Null

    if ($Verbose) {
        Write-Info "Input: $($ktFiles | ForEach-Object { Split-Path $_ -Leaf }) "
        Write-Info "Output dir: $TestOutDir"
    }

    # ── Transpile ───────────────────────────────────────────────
    if ($Verbose) { Write-Section "Transpile" }
    if ($Build -ne "Gradle") {
        $activeJar = if ($Build -eq "Proguard") { $releaseJar } else { $jar }
        $transpileArgs = @("-jar", $activeJar) + $ktFiles + @("-o", $TestOutDir)
    } else {
        # gradle run handles classpath, kotlin stdlib, and resources automatically
        $ktArgs = ($ktFiles -join " ") + " -o $TestOutDir"
        if ($ExtraArgs -ne "") { $ktArgs += " $ExtraArgs" }
        $transpileArgs = @("run", "--quiet", "--args=$ktArgs")
    }
    if ($ExtraArgs -ne "" -and $Build -ne "Gradle") {
        $transpileArgs += ($ExtraArgs -split '\s+')
    }
    if ($Verbose) {
        $ktNames = ($ktFiles | ForEach-Object { Split-Path $_ -Leaf }) -join ' '
        if ($Build -ne "Gradle") {
            $jarLabel = if ($Build -eq "Proguard") { "KotlinToC-release.jar" } else { "KotlinToC.jar" }
            $cmdLine = "java -jar $jarLabel $ktNames -o $TestOutDir"
        } else {
            $cmdLine = "gradlew run --args=`"$ktNames -o $TestOutDir`""
        }
        if ($ExtraArgs -ne "") { $cmdLine += " $ExtraArgs" }
        Write-Cmd $cmdLine
        Write-Host ""
    }
    if ($Build -ne "Gradle") {
        $transpileOutput = & java @transpileArgs 2>&1
    } else {
        $transpileOutput = & "$root\gradlew.bat" @transpileArgs 2>&1
    }
    $transpileExit = $LASTEXITCODE
    if ($Verbose) {
        foreach ($line in $transpileOutput) { Write-Host "  $line" }
        Write-Host ""
    }
    if ($transpileExit -ne 0) {
        if ($Verbose) { Write-Fail "Transpilation failed (exit code $transpileExit)" }
        else {
            Write-Fail "$Name (transpile failed)"
            Write-Host "       $transpileOutput" -ForegroundColor DarkGray
        }
        return $false
    }
    if ($Verbose) { Write-Pass "Transpilation succeeded" }

    # ── Discover generated .c files ─────────────────────────────
    $cFiles = @(Get-ChildItem "$TestOutDir\*.c" | Select-Object -ExpandProperty Name)
    if ($cFiles.Count -eq 0) {
        if ($Verbose) { Write-Fail "No .c files generated" } else { Write-Fail "$Name (no .c files generated)" }
        return $false
    }
    # Sort: ktc_std.c first, then the rest alphabetically
    $cFiles = $cFiles | Sort-Object { if ($_ -eq "ktc_std.c") { 0 } else { 1 } }, { $_ }
    $cSources = $cFiles | ForEach-Object { "$TestOutDir\$_" }

    # Binary name: use test name (e.g., PointerTest.exe)
    $exePath = "$TestOutDir\$Name.exe"

    # ── Compile ─────────────────────────────────────────────────
    if ($Verbose) { Write-Section "Compile" }
    $compileArgs = @("-std=c11", "-o", $exePath)
    if ($CCArgs -ne "") { $compileArgs += ($CCArgs -split '\s+') }
    $compileArgs += $cSources
    if ($Verbose) {
        $extraFlags = if ($CCArgs -ne "") { " $CCArgs" } else { "" }
        Write-Cmd "$CC -std=c11$extraFlags -o $exePath $($cSources -join ' ')"
        Write-Host ""
    }
    $compileOutput = & $CC @compileArgs 2>&1
    $compileExit = $LASTEXITCODE
    if ($Verbose -and $compileOutput) {
        foreach ($line in $compileOutput) { Write-Host "  $line" -ForegroundColor DarkGray }
        Write-Host ""
    }
    if ($compileExit -ne 0) {
        if ($Verbose) { Write-Fail "Compilation failed (exit code $compileExit)" }
        else {
            Write-Fail "$Name (compile failed)"
            $compileOutput | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
        }
        return $false
    }
    if ($Verbose) { Write-Pass "Compilation succeeded -> $exePath" }

    # ── Generated files (verbose only) ──────────────────────────
    if ($Verbose) {
        Write-Section "Generated Files"
        Get-ChildItem "$TestOutDir\*" | ForEach-Object {
            $size = if ($_.Length -ge 1024) { "{0:N1} KB" -f ($_.Length / 1024) } else { "$($_.Length) B" }
            Write-Info ("{0,-30} {1,10}" -f $_.Name, $size)
        }
    }

    # ── Run ─────────────────────────────────────────────────────
    if ($Verbose) { Write-Section "Run" }
    if ($Verbose) {
        Write-Cmd $exePath
        Write-Host ""
    }
    $runOutput = & $exePath 2>&1
    $runExit = $LASTEXITCODE
    if ($Verbose) {
        $runOutput | ForEach-Object { Write-Host "  $_" }
        Write-Host ""
    }
    if ($runExit -ne 0) {
        if ($Verbose) { Write-Fail "Runtime error (exit code $runExit)" }
        else {
            Write-Fail "$Name (runtime error, exit code $runExit)"
            $runOutput | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
        }
        return $false
    }
    if ($Verbose) { Write-Pass "Program exited successfully (code 0)" }

    if (-not $Verbose) { Write-Pass $Name }
    return $true
}

# ════════════════════════════════════════════════════════════════
# Single-test run mode: -Run <TestDirName>
# ════════════════════════════════════════════════════════════════
if ($Run -ne "") {
    Write-Info "Using C compiler: $CC"

    # ── Build only if -Build ───────────────────────────────────
    if ($Build -ne "") {
        Write-Section "Build"
        if ($Build -eq "Proguard") {
            Write-Cmd "gradlew proguard"
            & "$root\gradlew.bat" proguard --quiet 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
                Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
                exit 1
            }
            Write-Pass "Built $releaseJar"
        } else {
            Write-Cmd "gradlew jar"
            & "$root\gradlew.bat" jar --quiet 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) {
                Write-Host "ERROR: JAR build failed" -ForegroundColor Red
                exit 1
            }
            Write-Pass "Built $jar"
        }
    }

    $testSrcDir = "$testsDir\$Run"
    if (-not (Test-Path $testSrcDir -PathType Container)) {
        Write-Host "ERROR: test directory not found: $testSrcDir" -ForegroundColor Red
        Write-Host "Available tests:" -ForegroundColor Yellow
        Get-ChildItem $testsDir -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
        exit 1
    }

    # ── Build transpile args from flags ───────────────────────────
    $allArgs = ""
    if ($MemTrack) { $allArgs += " --mem-track" }
    if ($Ast) { $allArgs += " --ast" }
    if ($TranspilerArgs -ne "") { $allArgs += " $TranspilerArgs" }
    $allArgs = $allArgs.Trim()

    $result = Invoke-Test -Name $Run -TestSrcDir $testSrcDir -TestOutDir "$outDir\$Run" -Verbose $true -ExtraArgs $allArgs
    if ($result) { exit 0 } else { exit 1 }
}

# ════════════════════════════════════════════════════════════════
# Normal test-suite mode
# ════════════════════════════════════════════════════════════════

Write-Info "Using C compiler: $CC"

$totalTests = 0
$passedTests = 0
$failedTests = 0
$failedNames = @()

# ── 1. Unit Tests ───────────────────────────────────────────────
if ($Skip -ne "unit") {
    Write-Section "Unit Tests (gradlew test)"
    & "$root\gradlew.bat" test --quiet 2>&1 | Out-String -Stream | ForEach-Object {
        if ($_ -match "(\d+) tests completed, (\d+) failed") {
            $total = [int]$Matches[1]
            $failed = [int]$Matches[2]
            $totalTests += $total
            $passedTests += ($total - $failed)
            $failedTests += $failed
            if ($failed -gt 0) { $failedNames += "unit-tests ($failed failures)" }
        }
    }
    if ($LASTEXITCODE -eq 0) {
        Write-Pass "All unit tests passed"
    } else {
        Write-Fail "Unit tests had failures (see above)"
    }
}

# ── 2. Build JAR only if -Build ──────────────────────────────
if ($Build -ne "Gradle") {
    if ($Build -eq "Proguard") {
        Write-Section "Building ProGuard release JAR"
        & "$root\gradlew.bat" proguard --quiet 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
            Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
            exit 1
        }
        Write-Pass "Built $releaseJar"
    } else {
        Write-Section "Building transpiler JAR"
        & "$root\gradlew.bat" jar --quiet 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) {
            Write-Host "ERROR: JAR build failed" -ForegroundColor Red
            exit 1
        }
        Write-Pass "Built $jar"
    }
}

# ── Prepare output directory ────────────────────────────────────
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item $outDir -ItemType Directory -Force | Out-Null

# ── 3. Integration Tests — auto-discover from tests/ ───────────
Write-Section "Integration Tests"

$testDirs = Get-ChildItem $testsDir -Directory | Sort-Object Name
if ($testDirs.Count -eq 0) {
    Write-Info "No test directories found in $testsDir"
} else {
    # Build combined transpile args from flags
    $allArgs = ""
    if ($MemTrack) { $allArgs += " --mem-track" }
    if ($Ast) { $allArgs += " --ast" }
    if ($TranspilerArgs -ne "") { $allArgs += " $TranspilerArgs" }
    $allArgs = $allArgs.Trim()

    foreach ($dir in $testDirs) {
        $totalTests++
        $result = Invoke-Test -Name $dir.Name -TestSrcDir $dir.FullName -TestOutDir "$outDir\$($dir.Name)" -ExtraArgs $allArgs
        if ($result) {
            $passedTests++
        } else {
            $failedTests++
            $failedNames += $dir.Name
        }
    }
}

# ── Summary ─────────────────────────────────────────────────────
Write-Section "Summary"
$total = $passedTests + $failedTests
Write-Host "  Total: $total  |  " -NoNewline
Write-Host "Passed: $passedTests" -ForegroundColor Green -NoNewline
Write-Host "  |  " -NoNewline
if ($failedTests -gt 0) {
    Write-Host "Failed: $failedTests" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Failed tests:" -ForegroundColor Red
    foreach ($name in $failedNames) {
        Write-Host "    - $name" -ForegroundColor Red
    }
    exit 1
} else {
    Write-Host "Failed: 0" -ForegroundColor Green
    exit 0
}
