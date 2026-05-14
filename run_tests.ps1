#!/usr/bin/env pwsh
#
# run_tests.ps1 — Build the transpiler, then run all integration tests.
#
# Each subdirectory under tests/ is one test. All .kt files in the directory
# are transpiled together. Adding a new directory = adding a new test.
#
# Usage:
#   .\run_tests.ps1                              # Run all tests
#   .\run_tests.ps1 -Skip unit                  # Skip unit tests, only run integration
#   .\run_tests.ps1 -Run HashMapTest             # Transpile, compile & run a single test
#   .\run_tests.ps1 -Run "Test1,Test2"           # Run multiple tests
#   .\run_tests.ps1 -Run game -MemTrack          # Run single test with --mem-track
#   .\run_tests.ps1 -Run game -Ast               # Run single test with --ast
#   .\run_tests.ps1 -Run game -DumpSemantics     # Run single test with --dump-semantics
#   .\run_tests.ps1 -Run game -MemTrack -TranspilerArgs "--other"  # Combined
#   .\run_tests.ps1 -Clean                       # Remove all test out/ directories
#   .\run_tests.ps1 -Rebuild                     # Force clean rebuild of JAR + run all tests
#   .\run_tests.ps1 -Rebuild -Run Utf8Test       # Rebuild JAR + run single test
#   .\run_tests.ps1 -Compiler clang              # Use clang instead of auto-detected gcc
#   .\run_tests.ps1 -CCArgs "-j14 -O2"           # Pass flags to the C compiler
#   .\run_tests.ps1 -Build Jar                   # Build fat JAR (default)
#   .\run_tests.ps1 -Build Gradle                # Build using gradle "run"
#   .\run_tests.ps1 -Build Proguard              # Build and use the ProGuard-optimized JAR
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
    [switch]$Clean,
    [switch]$Rebuild,
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
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = $PSScriptRoot
$jar        = "$root\build\libs\KotlinToC-1.0-SNAPSHOT.jar"
$releaseJar = "$root\build\libs\KotlinToC-1.0-SNAPSHOT-release.jar"
$testsDir = "$root\tests"

# ── Clean mode ──────────────────────────────────────────────────
if ($Clean) {
    Write-Host "Cleaning per-test output directories..." -ForegroundColor Cyan
    $cleaned = 0
    Get-ChildItem $testsDir -Directory | ForEach-Object {
        $outPath = Join-Path $_.FullName "out"
        if (Test-Path $outPath) {
            Remove-Item $outPath -Recurse -Force
            Write-Host "  removed $outPath" -ForegroundColor DarkGray
            $cleaned++
        }
    }
    Write-Host "Cleaned $cleaned test output directories." -ForegroundColor Green
    exit 0
}

# Gradle build command — optionally force clean rebuild
$gradleJarCmd  = if ($Rebuild) { "clean jar" } else { "jar" }
$gradleProCmd  = if ($Rebuild) { "clean proguard" } else { "proguard" }

# ── Colors ──────────────────────────────────────────────────────
function Write-Pass($msg) { Write-Host "  PASS " -ForegroundColor Green  -NoNewline; Write-Host $msg }
function Write-Fail($msg) { Write-Host "  FAIL " -ForegroundColor Red    -NoNewline; Write-Host $msg }
function Write-Info($msg) { Write-Host "  ---- " -ForegroundColor Cyan   -NoNewline; Write-Host $msg }
function Write-Warn($msg) { Write-Host "  WARN " -ForegroundColor Yellow -NoNewline; Write-Host $msg -ForegroundColor Yellow }
function Write-Section($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Yellow }
function Write-Cmd($msg) { Write-Host "  `$ " -ForegroundColor DarkYellow -NoNewline; Write-Host $msg -ForegroundColor White }

function Format-Ms([long]$ms) {
    if ($ms -lt 1000) { return "${ms}ms" }
    return ("{0:N2}s" -f ($ms / 1000.0))
}

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

    $sw = [Diagnostics.Stopwatch]::StartNew()

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
    $transpileMs = $sw.ElapsedMilliseconds
    $sw.Restart()

    if ($Verbose) {
        foreach ($line in $transpileOutput) {
            if ("$line" -match 'warning:') {
                Write-Host "  $line" -ForegroundColor Yellow
            } else {
                Write-Host "  $line"
            }
        }
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
    if ($Verbose) {
        Write-Host "  PASS " -ForegroundColor Green -NoNewline
        Write-Host "Transpilation succeeded  " -NoNewline
        Write-Host "(ktc: $(Format-Ms $transpileMs))" -ForegroundColor DarkGray
    }

    # ── Discover generated .c files ─────────────────────────────
    $ktcSubDir = "$TestOutDir\ktc"
    $cSources = @()
    if (Test-Path $ktcSubDir -PathType Container) {
        $ktcIntrinsic = "$ktcSubDir\ktc_intrinsic.c"
        if (Test-Path $ktcIntrinsic) { $cSources += $ktcIntrinsic }
        Get-ChildItem "$ktcSubDir\*.c" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne "ktc_intrinsic.c" } |
            Sort-Object Name |
            ForEach-Object { $cSources += $_.FullName }
    }
    Get-ChildItem "$TestOutDir\*.c" -ErrorAction SilentlyContinue |
        Sort-Object Name |
        ForEach-Object { $cSources += $_.FullName }

    if ($cSources.Count -eq 0) {
        if ($Verbose) { Write-Fail "No .c files generated" } else { Write-Fail "$Name (no .c files generated)" }
        return $false
    }

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
    $compileMs = $sw.ElapsedMilliseconds
    $sw.Restart()

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
    if ($Verbose) {
        Write-Host "  PASS " -ForegroundColor Green -NoNewline
        Write-Host "Compilation succeeded -> $exePath  " -NoNewline
        Write-Host "(comp: $(Format-Ms $compileMs))" -ForegroundColor DarkGray
    }

    # ── Generated files (verbose only) ──────────────────────────
    if ($Verbose) {
        Write-Section "Generated Files"
        Get-ChildItem "$TestOutDir" -Recurse -File | Sort-Object FullName | ForEach-Object {
            $relName = $_.FullName.Substring($TestOutDir.Length + 1)
            $size = if ($_.Length -ge 1024) { "{0:N1} KB" -f ($_.Length / 1024) } else { "$($_.Length) B" }
            Write-Info ("{0,-30} {1,10}" -f $relName, $size)
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
    $runMs = $sw.ElapsedMilliseconds
    $sw.Stop()

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
    $hasLeak = ($runOutput | Where-Object { "$_" -match 'leaked\s+:' }).Count -gt 0
    if ($Verbose) {
        if ($hasLeak) {
            Write-Host "  PASS " -ForegroundColor DarkYellow -NoNewline
            Write-Host "Program exited - memory leaks detected  " -NoNewline
            Write-Host "LEAK  " -ForegroundColor Red -NoNewline
            Write-Host "(run: $(Format-Ms $runMs))" -ForegroundColor DarkGray
        } else {
            Write-Host "  PASS " -ForegroundColor Green -NoNewline
            Write-Host "Program exited successfully (code 0)  " -NoNewline
            Write-Host "(run: $(Format-Ms $runMs))" -ForegroundColor DarkGray
        }
    }
    if (-not $Verbose) {
        if ($hasLeak) {
            Write-Host "  PASS " -ForegroundColor DarkYellow -NoNewline
            Write-Host "$Name  " -NoNewline
            Write-Host "LEAK" -ForegroundColor Red
        } else { Write-Pass $Name }
    }
    return $true
}

# ════════════════════════════════════════════════════════════════
# Single/multi-test run mode: -Run <Name> or -Run "A,B,C"
# ════════════════════════════════════════════════════════════════
if ($Run -ne "") {
    Write-Info "Using C compiler: $CC"

    # ── Build only if -Build ───────────────────────────────────
    if ($BuildMode -ne "gradle") {
        Write-Section "Build"
        if ($BuildMode -eq "proguard") {
            Write-Cmd "gradlew $gradleProCmd"
            & "$root\gradlew.bat" $gradleProCmd.Split(' ') 2>&1
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
                Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
                exit 1
            }
            Write-Pass "Built $releaseJar"
        } else {
            Write-Cmd "gradlew $gradleJarCmd"
            & "$root\gradlew.bat" $gradleJarCmd.Split(' ') 2>&1
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jar)) {
                Write-Host "ERROR: JAR build failed" -ForegroundColor Red
                exit 1
            }
            Write-Pass "Built $jar"
        }
    }

    # ── Build transpile args from flags ───────────────────────────
    $allArgs = ""
    if ($MemTrack) { $allArgs += " --mem-track" }
    if ($Ast) { $allArgs += " --ast" }
    if ($DumpSemantics) { $allArgs += " --dump-semantics" }
    if ($TranspilerArgs -ne "") { $allArgs += " $TranspilerArgs" }
    $allArgs = $allArgs.Trim()

    # ── Split on commas, run each test ────────────────────────────
    $runNames = $Run -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
    $anyFailed = $false
    foreach ($testName in $runNames) {
        $testSrcDir = "$testsDir\$testName"
        if (-not (Test-Path $testSrcDir -PathType Container)) {
            Write-Host "ERROR: test directory not found: $testSrcDir" -ForegroundColor Red
            Write-Host "Available tests:" -ForegroundColor Yellow
            Get-ChildItem $testsDir -Directory | ForEach-Object { Write-Host "  - $($_.Name)" }
            $anyFailed = $true
            continue
        }
        $result = Invoke-Test -Name $testName -TestSrcDir $testSrcDir -TestOutDir "$testSrcDir\out" -Verbose $true -ExtraArgs $allArgs
        if (-not $result) { $anyFailed = $true }
    }
    if ($anyFailed) { exit 1 } else { exit 0 }
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
        & "$root\gradlew.bat" $gradleProCmd.Split(' ') 2>&1
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $releaseJar)) {
            Write-Host "ERROR: ProGuard build failed" -ForegroundColor Red
            exit 1
        }
        Write-Pass "Built $releaseJar"
    } else {
        Write-Section "Building transpiler JAR"
        & "$root\gradlew.bat" $gradleJarCmd.Split(' ') 2>&1
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

        # Local ms formatter (functions from outer scope are not available in parallel blocks)
        function fmtMs([long]$ms) {
            if ($ms -lt 1000) { return "${ms}ms" }
            return ("{0:N2}s" -f ($ms / 1000.0))
        }

        $sw = [Diagnostics.Stopwatch]::StartNew()

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
        $transpileOutput = @()
        try {
            if ($using:BuildMode -eq "gradle") {
                $argsStr = "$ktPaths -o $testOutDir"
                if ($using:allArgs) { $argsStr += " $using:allArgs" }
                $transpileOutput = & "$using:root\gradlew.bat" run --quiet --args="$argsStr" 2>&1
                $transpileExit = $LASTEXITCODE
            } else {
                $activeJar = if ($using:BuildMode -eq "proguard") { $using:releaseJar } else { $using:jar }
                $cmdArgs = @("-jar", $activeJar) + $ktPaths + @("-o", $testOutDir)
                if ($using:allArgs) { $cmdArgs += $using:allArgs -split '\s+' }
                $transpileOutput = & java @cmdArgs 2>&1
                $transpileExit = $LASTEXITCODE
            }
        } catch {
            Write-Host "  FAIL $dirName (transpile: $_)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "transpile: $_" }
        }
        $transpileMs = $sw.ElapsedMilliseconds
        $sw.Restart()

        $warnings = @($transpileOutput | Where-Object { "$_" -match 'warning:' })

        if ($transpileExit -ne 0) {
            Write-Host "  FAIL $dirName (transpile failed)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "transpile failed" }
        }

        # Discover .c files: ktc/ subdir first (intrinsic then rest), then user files
        $ktcSubDir = "$testOutDir\ktc"
        $cSources = @()
        if (Test-Path $ktcSubDir -PathType Container) {
            $ktcIntrinsic = "$ktcSubDir\ktc_intrinsic.c"
            if (Test-Path $ktcIntrinsic) { $cSources += $ktcIntrinsic }
            Get-ChildItem "$ktcSubDir\*.c" -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -ne "ktc_intrinsic.c" } |
                Sort-Object Name |
                ForEach-Object { $cSources += $_.FullName }
        }
        Get-ChildItem "$testOutDir\*.c" -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object { $cSources += $_.FullName }
        if ($cSources.Count -eq 0) {
            Write-Host "  FAIL $dirName (no .c files generated)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "no .c files" }
        }

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
        $compileMs = $sw.ElapsedMilliseconds
        $sw.Restart()

        # Run
        $runOutput = @()
        try {
            $runOutput = & $exePath 2>&1
            if ($LASTEXITCODE -ne 0) {
                Write-Host "  FAIL $dirName (runtime error, exit $LASTEXITCODE)" -ForegroundColor Red
                return @{ Name = $dirName; Passed = $false; Reason = "runtime error, exit $LASTEXITCODE" }
            }
        } catch {
            Write-Host "  FAIL $dirName (run: $_)" -ForegroundColor Red
            return @{ Name = $dirName; Passed = $false; Reason = "run: $_" }
        }
        $runMs = $sw.ElapsedMilliseconds
        $sw.Stop()

        $hasLeak = @($runOutput | Where-Object { "$_" -match 'leaked\s+:' }).Count -gt 0
        $esc = [char]27
        $g = "${esc}[32m"; $gr = "${esc}[90m"; $y = "${esc}[33m"; $red = "${esc}[31m"; $r = "${esc}[0m"
        $timing = "${gr}ktc: $(fmtMs $transpileMs)  comp: $(fmtMs $compileMs)  run: $(fmtMs $runMs)${r}"
        if ($hasLeak) {
            Write-Host "  ${y}PASS $dirName${r}  ${red}LEAK${r}  $timing"
        } else {
            Write-Host "  ${g}PASS $dirName${r}  $timing"
        }
        foreach ($w in $warnings) { Write-Host "       $w" -ForegroundColor Yellow }

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
