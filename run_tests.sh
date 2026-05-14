#!/usr/bin/env bash
#
# run_tests.sh — Build the transpiler, then run all integration tests.
#
# Each subdirectory under tests/ is one test. All .kt files in the directory
# are transpiled together. Adding a new directory = adding a new test.
#
# Usage:
#   ./run_tests.sh                            # Run all tests
#   ./run_tests.sh --help                     # Show this help
#   ./run_tests.sh --skip-unit                # Skip unit tests
#   ./run_tests.sh --run HashMapTest          # Single test
#   ./run_tests.sh --run "Test1,Test2"        # Multiple tests
#   ./run_tests.sh --run game --mem-track     # With --mem-track flag
#   ./run_tests.sh --run game --ast           # With --ast flag
#   ./run_tests.sh --run game --dump-semantics # With --dump-semantics flag
#   ./run_tests.sh --compiler clang           # Override C compiler
#   ./run_tests.sh --cc-args "-j14 -O2"       # Pass flags to C compiler
#   ./run_tests.sh --build jar                # Build fat JAR (default for suite)
#   ./run_tests.sh --build gradle             # Use gradle run (no JAR)
#   ./run_tests.sh --build proguard           # Use ProGuard-optimized JAR
#   ./run_tests.sh --clean                    # Remove all test output directories
#   ./run_tests.sh --rebuild                  # Force clean rebuild of JAR + run all tests
#
set -euo pipefail

usage() {
    sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# //'
    exit 0
}

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT.jar"
RELEASE_JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT-release.jar"
TESTS_DIR="$ROOT/tests"

SKIP_UNIT=false
RUN_TEST=""
EXTRA_ARGS=""
COMPILER=""
CC_ARGS=""
BUILD="jar"
CLEAN=false
REBUILD=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help)        usage ;;
        --skip-unit)   SKIP_UNIT=true; shift ;;
        --run)         RUN_TEST="$2"; shift 2 ;;
        --mem-track)   EXTRA_ARGS="$EXTRA_ARGS --mem-track"; shift ;;
        --ast)         EXTRA_ARGS="$EXTRA_ARGS --ast"; shift ;;
        --dump-semantics) EXTRA_ARGS="$EXTRA_ARGS --dump-semantics"; shift ;;
        --args)        EXTRA_ARGS="$EXTRA_ARGS $2"; shift 2 ;;
        --compiler)    COMPILER="$2"; shift 2 ;;
        --cc-args)     CC_ARGS="$2"; shift 2 ;;
        --build)       BUILD="$2"; shift 2 ;;
        --clean)       CLEAN=true; shift ;;
        --rebuild)     REBUILD=true; shift ;;
        *)             echo "Unknown option: $1 (use --help)"; exit 1 ;;
    esac
done

BUILD="$(echo "$BUILD" | tr '[:upper:]' '[:lower:]')"

# Gradle build commands — optionally force clean rebuild
if [[ "$REBUILD" == true ]]; then
    GRADLE_JAR="clean jar"
    GRADLE_PRO="clean proguard"
else
    GRADLE_JAR="jar"
    GRADLE_PRO="proguard"
fi

# ── Clean mode ──────────────────────────────────────────────────
if [[ "$CLEAN" == true ]]; then
    echo "Cleaning per-test output directories..."
    cleaned=0
    for d in "$TESTS_DIR"/*/; do
        [[ -d "$d" ]] || continue
        out="$d/out"
        if [[ -d "$out" ]]; then
            rm -rf "$out"
            echo "  removed $out"
            ((cleaned++)) || true
        fi
    done
    echo "Cleaned $cleaned test output directories."
    exit 0
fi

# ── Colors ──────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;90m'
WHITE='\033[1;37m'
DYELLOW='\033[0;33m'
NC='\033[0m'

pass()    { printf "  ${GREEN}PASS${NC} %s\n" "$1"; }
fail()    { printf "  ${RED}FAIL${NC} %s\n" "$1"; }
info()    { printf "  ${CYAN}----${NC} %s\n" "$1"; }
section() { printf "\n${YELLOW}=== %s ===${NC}\n" "$1"; }
showcmd() { printf "  ${DYELLOW}\$ ${WHITE}%s${NC}\n" "$1"; }

# Returns current time in milliseconds (best effort, falls back to seconds*1000)
now_ms() {
    local ms
    ms=$(date +%s%3N 2>/dev/null)
    [[ "$ms" =~ ^[0-9]+$ ]] && echo "$ms" && return
    ms=$(python3 -c "import time; print(int(time.time()*1000))" 2>/dev/null)
    [[ "$ms" =~ ^[0-9]+$ ]] && echo "$ms" && return
    ms=$(perl -MTime::HiRes=time -e 'printf "%d\n", time()*1000' 2>/dev/null)
    [[ "$ms" =~ ^[0-9]+$ ]] && echo "$ms" && return
    echo $(($(date +%s) * 1000))
}

# Formats milliseconds as "123ms" or "1.23s"
format_ms() {
    local ms=$1
    if [[ $ms -lt 1000 ]]; then
        echo "${ms}ms"
    else
        awk "BEGIN { printf \"%.2fs\", $ms / 1000 }"
    fi
}

# ── Detect C compiler ───────────────────────────────────────────
if [[ -n "$COMPILER" ]]; then
    CC="$COMPILER"
else
    CC=""
    for candidate in gcc clang cc; do
        if command -v "$candidate" &>/dev/null; then
            CC="$candidate"
            break
        fi
    done
fi
if [[ -z "$CC" ]]; then
    echo "ERROR: No C compiler found (tried gcc, clang, cc). Install one and add to PATH."
    exit 1
fi

# ── Helper: transpile, compile, run one test directory ──────────
invoke_test() {
    local name="$1"
    local test_src_dir="$2"
    local test_out_dir="$3"
    local verbose="${4:-false}"
    local extra_args="${5:-}"

    local transpile_ms=0 compile_ms=0 run_ms=0
    local ts_start ts_end

    # ── Collect .kt files ───────────────────────────────────────
    local kt_files=()
    for f in "$test_src_dir"/*.kt; do
        [[ -e "$f" ]] && kt_files+=("$f")
    done
    if [[ ${#kt_files[@]} -eq 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "$name — no .kt files in $test_src_dir"
        else
            fail "$name (no .kt files)"
        fi
        return 1
    fi

    rm -rf "$test_out_dir"
    mkdir -p "$test_out_dir"

    if [[ "$verbose" == "true" ]]; then
        local kt_names=""
        for f in "${kt_files[@]}"; do kt_names+="$(basename "$f") "; done
        info "Input: $kt_names"
        info "Output dir: $test_out_dir"
    fi

    # ── Transpile ───────────────────────────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Transpile"
        local kt_names=""
        for f in "${kt_files[@]}"; do kt_names+="$(basename "$f") "; done
        local extra_display=""
        [[ -n "$extra_args" ]] && extra_display=" $extra_args"
        case "$BUILD" in
            gradle) showcmd "gradlew run --args=\"$kt_names -o $test_out_dir$extra_display\"" ;;
            *)
                local jar_label
                case "$BUILD" in
                    proguard) jar_label="KotlinToC-release.jar" ;;
                    *)        jar_label="KotlinToC.jar" ;;
                esac
                showcmd "java -jar $jar_label $kt_names -o $test_out_dir$extra_display"
                ;;
        esac
        echo ""
    fi
    set +e
    local output
    ts_start=$(now_ms)
    case "$BUILD" in
        gradle)
            local app_args="${kt_files[*]} -o $test_out_dir $extra_args"
            output=$("$ROOT/gradlew" run --quiet --args="$app_args" 2>&1)
            ;;
        *)
            local active_jar
            case "$BUILD" in
                proguard) active_jar="$RELEASE_JAR" ;;
                *)        active_jar="$JAR" ;;
            esac
            output=$(java -jar "$active_jar" "${kt_files[@]}" -o "$test_out_dir" $extra_args 2>&1)
            ;;
    esac
    local transpile_exit=$?
    ts_end=$(now_ms)
    transpile_ms=$((ts_end - ts_start))
    set -e

    # Extract codegenWarning lines from transpiler output
    local warnings_lines=""
    warnings_lines=$(echo "$output" | grep 'warning:' || true)

    if [[ "$verbose" == "true" ]]; then
        while IFS= read -r line; do
            if echo "$line" | grep -q 'warning:'; then
                printf "  ${YELLOW}%s${NC}\n" "$line"
            else
                printf "  %s\n" "$line"
            fi
        done <<< "$output"
        echo ""
    fi
    if [[ $transpile_exit -ne 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "Transpilation failed (exit code $transpile_exit)"
        else
            fail "$name (transpile failed)"
            printf "  ${GRAY}%s${NC}\n" "$output"
        fi
        return 1
    fi
    if [[ "$verbose" == "true" ]]; then
        printf "  ${GREEN}PASS${NC} Transpilation succeeded  ${GRAY}(ktc: %s)${NC}\n" "$(format_ms $transpile_ms)"
    fi

    # ── Discover generated .c files ─────────────────────────────
    local ktc_dir="$test_out_dir/ktc"
    local c_sources=()
    if [[ -d "$ktc_dir" ]]; then
        [[ -f "$ktc_dir/ktc_intrinsic.c" ]] && c_sources+=("$ktc_dir/ktc_intrinsic.c")
        for f in "$ktc_dir"/*.c; do
            [[ -e "$f" ]] || continue
            [[ "$(basename "$f")" == "ktc_intrinsic.c" ]] && continue
            c_sources+=("$f")
        done
    fi
    local user_c=()
    for f in "$test_out_dir"/*.c; do
        [[ -e "$f" ]] && user_c+=("$f")
    done
    if [[ ${#user_c[@]} -gt 0 ]]; then
        IFS=$'\n' user_c=($(sort <<< "${user_c[*]}")); unset IFS
        c_sources+=("${user_c[@]}")
    fi

    if [[ ${#c_sources[@]} -eq 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "No .c files generated"
        else
            fail "$name (no .c files generated)"
        fi
        return 1
    fi

    local exe_path="$test_out_dir/$name"

    # ── Compile ─────────────────────────────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Compile"
        showcmd "$CC -std=c11 $CC_ARGS -o $exe_path ${c_sources[*]}"
        echo ""
    fi
    set +e
    ts_start=$(now_ms)
    output=$("$CC" -std=c11 $CC_ARGS -o "$exe_path" "${c_sources[@]}" 2>&1)
    local compile_exit=$?
    ts_end=$(now_ms)
    compile_ms=$((ts_end - ts_start))
    set -e
    if [[ "$verbose" == "true" && -n "$output" ]]; then
        while IFS= read -r line; do printf "  ${GRAY}%s${NC}\n" "$line"; done <<< "$output"
        echo ""
    fi
    if [[ $compile_exit -ne 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "Compilation failed (exit code $compile_exit)"
        else
            fail "$name (compile failed)"
            printf "  ${GRAY}%s${NC}\n" "$output"
        fi
        return 1
    fi
    if [[ "$verbose" == "true" ]]; then
        printf "  ${GREEN}PASS${NC} Compilation succeeded -> %s  ${GRAY}(comp: %s)${NC}\n" "$exe_path" "$(format_ms $compile_ms)"
    fi

    # ── Generated files (verbose only) ──────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Generated Files"
        for f in "$test_out_dir"/* "$test_out_dir"/ktc/*; do
            [[ -f "$f" ]] || continue
            local fname
            fname="${f#$test_out_dir/}"
            local size
            size=$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f" 2>/dev/null || echo "?")
            local human
            if [[ "$size" =~ ^[0-9]+$ ]] && [[ $size -ge 1024 ]]; then
                human="$(echo "scale=1; $size / 1024" | bc) KB"
            else
                human="$size B"
            fi
            printf "  ${CYAN}----${NC} %-30s %10s\n" "$fname" "$human"
        done
    fi

    # ── Run ─────────────────────────────────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Run"
        showcmd "$exe_path"
        echo ""
    fi
    set +e
    local run_exit=0
    ts_start=$(now_ms)
    output=$("$exe_path" 2>&1)
    run_exit=$?
    ts_end=$(now_ms)
    run_ms=$((ts_end - ts_start))
    set -e

    if [[ "$verbose" == "true" ]]; then
        while IFS= read -r line; do printf "  %s\n" "$line"; done <<< "$output"
        echo ""
    fi
    if [[ $run_exit -ne 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "Runtime error (exit code $run_exit)"
        else
            fail "$name (runtime error, exit code $run_exit)"
            printf "  ${GRAY}%s${NC}\n" "$output"
        fi
        return 1
    fi

    local has_leak=false
    echo "$output" | grep -q 'leaked' && has_leak=true || true

    if [[ "$verbose" == "true" ]]; then
        if [[ "$has_leak" == true ]]; then
            printf "  ${DYELLOW}PASS${NC} Program exited - memory leaks detected  ${RED}LEAK${NC}  ${GRAY}(run: %s)${NC}\n" "$(format_ms $run_ms)"
        else
            printf "  ${GREEN}PASS${NC} Program exited successfully (code 0)  ${GRAY}(run: %s)${NC}\n" "$(format_ms $run_ms)"
        fi
    else
        if [[ "$has_leak" == true ]]; then
            printf "  ${DYELLOW}PASS${NC} ${DYELLOW}%s${NC}  ${RED}LEAK${NC}  ${GRAY}ktc: %s  comp: %s  run: %s${NC}\n" \
                "$name" "$(format_ms $transpile_ms)" "$(format_ms $compile_ms)" "$(format_ms $run_ms)"
        else
            printf "  ${GREEN}PASS${NC} ${GREEN}%s${NC}  ${GRAY}ktc: %s  comp: %s  run: %s${NC}\n" \
                "$name" "$(format_ms $transpile_ms)" "$(format_ms $compile_ms)" "$(format_ms $run_ms)"
        fi
        if [[ -n "$warnings_lines" ]]; then
            while IFS= read -r w; do
                [[ -n "$w" ]] && printf "       ${YELLOW}%s${NC}\n" "$w"
            done <<< "$warnings_lines"
        fi
    fi
    return 0
}

# ════════════════════════════════════════════════════════════════
# Single/multi-test run mode: --run <Name> or --run "A,B,C"
# ════════════════════════════════════════════════════════════════
if [[ -n "$RUN_TEST" ]]; then
    info "Using C compiler: $CC"

    # ── Build ──────────────────────────────────────────────────
    if [[ "$BUILD" != "gradle" ]]; then
        section "Build"
        case "$BUILD" in
            proguard)
                showcmd "gradlew proguard"
                "$ROOT/gradlew" $GRADLE_PRO --quiet 2>&1
                if [[ ! -f "$RELEASE_JAR" ]]; then
                    echo "ERROR: ProGuard build failed"
                    exit 1
                fi
                pass "Built $RELEASE_JAR"
                ;;
            *)
                showcmd "gradlew jar"
                "$ROOT/gradlew" $GRADLE_JAR --quiet 2>&1
                if [[ ! -f "$JAR" ]]; then
                    echo "ERROR: JAR build failed"
                    exit 1
                fi
                pass "Built $JAR"
                ;;
        esac
    fi

    # ── Split on commas, run each test ─────────────────────────
    any_failed=false
    IFS=',' read -ra run_names <<< "$RUN_TEST"
    for test_name in "${run_names[@]}"; do
        test_name="${test_name// /}"  # trim spaces
        [[ -z "$test_name" ]] && continue
        test_src_dir="$TESTS_DIR/$test_name"
        if [[ ! -d "$test_src_dir" ]]; then
            echo "ERROR: test directory not found: $test_src_dir"
            echo "Available tests:"
            for d in "$TESTS_DIR"/*/; do
                [[ -d "$d" ]] && echo "  - $(basename "$d")"
            done
            any_failed=true
            continue
        fi
        if ! invoke_test "$test_name" "$test_src_dir" "$test_src_dir/out" "true" "$EXTRA_ARGS"; then
            any_failed=true
        fi
    done
    if [[ "$any_failed" == true ]]; then exit 1; else exit 0; fi
fi

# ════════════════════════════════════════════════════════════════
# Normal test-suite mode
# ════════════════════════════════════════════════════════════════

info "Using C compiler: $CC"

TOTAL=0
PASSED=0
FAILED=0
FAILED_NAMES=()

# ── 1. Unit Tests ───────────────────────────────────────────────
if [[ "$SKIP_UNIT" == false ]]; then
    section "Unit Tests (gradlew test)"
    if "$ROOT/gradlew" test --quiet 2>&1; then
        pass "All unit tests passed"
    else
        fail "Unit tests had failures"
        FAILED_NAMES+=("unit-tests")
        ((FAILED++)) || true
    fi
fi

# ── 2. Build ──────────────────────────────────────────────────
if [[ "$BUILD" != "gradle" ]]; then
    case "$BUILD" in
        proguard)
            section "Building ProGuard release JAR"
            "$ROOT/gradlew" $GRADLE_PRO --quiet 2>&1
            if [[ ! -f "$RELEASE_JAR" ]]; then
                echo "ERROR: ProGuard build failed"
                exit 1
            fi
            pass "Built $RELEASE_JAR"
            ;;
        *)
            section "Building transpiler JAR"
            "$ROOT/gradlew" $GRADLE_JAR --quiet 2>&1
            if [[ ! -f "$JAR" ]]; then
                echo "ERROR: JAR build failed"
                exit 1
            fi
            pass "Built $JAR"
            ;;
    esac
fi

# ── Create temp dir for parallel output capture (auto-deleted on exit) ──
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# ── 3. Integration Tests — auto-discover from tests/ ───────────
section "Integration Tests"

test_dirs=()
for d in "$TESTS_DIR"/*/; do
    [[ -d "$d" ]] && test_dirs+=("$d")
done

if [[ ${#test_dirs[@]} -eq 0 ]]; then
    info "No test directories found in $TESTS_DIR"
else
    IFS=$'\n' test_dirs=($(printf '%s\n' "${test_dirs[@]}" | sort)); unset IFS

    pids=()
    for dir in "${test_dirs[@]}"; do
        dir_name="$(basename "$dir")"
        (
            invoke_test "$dir_name" "$dir" "$dir/out" "false" "$EXTRA_ARGS" > "$TEMP_DIR/${dir_name}.out" 2>&1
            echo $? > "$TEMP_DIR/${dir_name}.code"
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || true
    done

    for dir in "${test_dirs[@]}"; do
        dir_name="$(basename "$dir")"
        exit_code=$(cat "$TEMP_DIR/${dir_name}.code" 2>/dev/null || echo 1)
        cat "$TEMP_DIR/${dir_name}.out" 2>/dev/null || true
        ((TOTAL++)) || true
        if [[ "$exit_code" == "0" ]]; then
            ((PASSED++)) || true
        else
            ((FAILED++)) || true
            FAILED_NAMES+=("$dir_name")
        fi
    done
fi

# ── Summary ─────────────────────────────────────────────────────
section "Summary"
TOTAL_RUN=$((PASSED + FAILED))
printf "  Total: %d  |  ${GREEN}Passed: %d${NC}  |  " "$TOTAL_RUN" "$PASSED"

if [[ $FAILED -gt 0 ]]; then
    printf "${RED}Failed: %d${NC}\n" "$FAILED"
    echo ""
    printf "  ${RED}Failed tests:${NC}\n"
    for name in "${FAILED_NAMES[@]}"; do
        printf "    ${RED}- %s${NC}\n" "$name"
    done
    exit 1
else
    printf "${GREEN}Failed: 0${NC}\n"
    exit 0
fi
