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
#   .\run_tests.ps1 -Run game -DumpSemantics # Run single test with --dump-semantics
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
    [switch]$DumpSemantics,
    [string]$Build = "Jar"
)

$BuildMode = $Build.ToLowerInvariant()

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
    if ($BuildMode -eq "gradle") {
        # gradle run handles classpath, kotlin stdlib, and resources automatically
        $ktArgs = ($ktFiles -join " ") + " -o $TestOutDir"
        if ($ExtraArgs -ne "") { $ktArgs += " $ExtraArgs" }
        $transpileArgs = @("run", "--quiet", "--args=$ktArgs")
    } else {
        $activeJar = if ($BuildMode -eq "proguard") { $releaseJar } else { $jar }
        $transpileArgs = @("-jar", $activeJar) + $ktFiles + @("-o", $TestOutDir)
        if ($ExtraArgs -ne "") { $transpileArgs += ($ExtraArgs -split '\s+') }
    }
    if ($Verbose) {
        $ktNames = ($ktFiles | ForEach-Object { Split-Path $_ -Leaf }) -join ' '
        if ($BuildMode -eq "gradle") {
            $cmdLine = "gradlew run --args=`"$ktNames -o $TestOutDir`""
        } else {
            $jarLabel = if ($BuildMode -eq "proguard") { "KotlinToC-release.jar" } else { "KotlinToC.jar" }
            $cmdLine = "java -jar $jarLabel $ktNames -o $TestOutDir"
        }
        if ($ExtraArgs -ne "") { $cmdLine += " $ExtraArgs" }
        Write-Cmd $cmdLine
        Write-Host ""
    }
    if ($BuildMode -eq "gradle") {
        $transpileOutput = & "$root\gradlew.bat" @transpileArgs 2>&1
    } else {
        $transpileOutput = & java @transpileArgs 2>&1
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
    if ($BuildMode -ne "gradle") {
        Write-Section "Build"
        if ($BuildMode -eq "proguard") {
            Write-Cmd "gradlew proguard"
            & "$root\gradlew.bat" proguard 2>&1
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
                Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
                exit 1
            }
            Write-Pass "Built $releaseJar"
        } else {
            Write-Cmd "gradlew jar"
            & "$root\gradlew.bat" jar 2>&1
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
    if ($DumpSemantics) { $allArgs += " --dump-semantics" }
    if ($TranspilerArgs -ne "") { $allArgs += " $TranspilerArgs" }
    $allArgs = $allArgs.Trim()

    $result = Invoke-Test -Name $Run -TestSrcDir $testSrcDir -TestOutDir "$testSrcDir\out" -Verbose $true -ExtraArgs $allArgs
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

# ── 2. Build transpiler if needed ────────────────────────────
if ($BuildMode -ne "gradle") {
    if ($BuildMode -eq "proguard") {
        Write-Section "Building ProGuard release JAR"
        & "$root\gradlew.bat" proguard 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
            Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
            exit 1
        }
        Write-Pass "Built $releaseJar"
    } else {
        Write-Section "Building transpiler JAR"
        & "$root\gradlew.bat" jar 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) {
            Write-Host "ERROR: JAR build failed" -ForegroundColor Red
            exit 1
        }
        Write-Pass "Built $jar"
    }
}

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
    if ($DumpSemantics) { $allArgs += " --dump-semantics" }
    if ($TranspilerArgs -ne "") { $allArgs += " $TranspilerArgs" }
    $allArgs = $allArgs.Trim()

    $throttle = [Math]::Max(1, [Environment]::ProcessorCount)
    $results = $testDirs | ForEach-Object -Parallel {
        $dir = $_
        $dirName = Split-Path $dir -Leaf
        $testOutDir = "$($dir.FullName)\out"

        # Collect .kt files
        $ktFiles = @(Get-ChildItem "$dir\*.kt" -ErrorAction SilentlyContinue)
        if ($ktFiles.Count -eq 0) {
            Write-Host "  FAIL $dirName (no .kt files)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "no .kt files" }
        }
        $ktPaths = $ktFiles | ForEach-Object FullName

        # Create output dir
        if (Test-Path $testOutDir) { Remove-Item $testOutDir -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item $testOutDir -ItemType Directory -Force | Out-Null

        # Transpile
        $transpileExit = 0
        try {
            if ($using:BuildMode -eq "gradle") {
                $argsStr = "$ktPaths -o $testOutDir"
                if ($using:allArgs) { $argsStr += " $using:allArgs" }
                & "$using:root\gradlew.bat" run --quiet --args="$argsStr" 2>&1 | Out-Null
                $transpileExit = $LASTEXITCODE
            } else {
                $activeJar = if ($using:BuildMode -eq "proguard") { $using:releaseJar } else { $using:jar }
                $cmdArgs = @("-jar", $activeJar) + $ktPaths + @("-o", $testOutDir)
                if ($using:allArgs) { $cmdArgs += $using:allArgs -split '\s+' }
                & java @cmdArgs 2>&1 | Out-Null
                $transpileExit = $LASTEXITCODE
            }
        } catch {
            Write-Host "  FAIL $dirName (transpile: $_)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "transpile: $_" }
        }
        if ($transpileExit -ne 0) {
            Write-Host "  FAIL $dirName (transpile failed)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "transpile failed" }
        }

        # Discover .c files
        $cFiles = @(Get-ChildItem "$testOutDir\*.c" -ErrorAction SilentlyContinue)
        if ($cFiles.Count -eq 0) {
            Write-Host "  FAIL $dirName (no .c files generated)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "no .c files" }
        }
        $cFiles = $cFiles | Sort-Object { if ($_.Name -eq "ktc_std.c") { 0 } else { 1 } }, { $_.Name }
        $cSources = $cFiles | ForEach-Object FullName

        # Compile
        $exePath = "$testOutDir\$dirName.exe"
        $compileArgs = @("-std=c11", "-o", $exePath)
        if ($using:CCArgs) { $compileArgs += $using:CCArgs -split '\s+' }
        $compileArgs += $cSources
        try {
            & $using:CC @compileArgs 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  FAIL $dirName (compile failed)" -ForegroundColor Red
                return @{ Name = $dirName; Passed = $false; Reason = "compile failed" }
            }
        } catch {
            Write-Host "  FAIL $dirName (compile: $_)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "compile: $_" }
        }

        # Run
        try {
            & $exePath 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  FAIL $dirName (runtime error, exit $LASTEXITCODE)" -ForegroundColor Red
                return @{ Name = $dirName; Passed = $false; Reason = "runtime error, exit $LASTEXITCODE" }
            }
        } catch {
            Write-Host "  FAIL $dirName (run: $_)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "run: $_" }
        }

        Write-Host "  PASS $dirName" -ForegroundColor Green
        return @{ Name = $dirName; Passed = $true; Reason = "" }
    } -ThrottleLimit $throttle

    foreach ($r in $results) {
        $totalTests++
        if ($r.Passed) {
            $passedTests++
        } else {
            $failedTests++
            $failedNames += $r.Name
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
