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
#   ./run_tests.sh --run game --mem-track     # With --mem-track flag
#   ./run_tests.sh --run game --ast           # With --ast flag
#   ./run_tests.sh --run game --dump-semantics # With --dump-semantics flag
#   ./run_tests.sh --compiler clang           # Override C compiler
#   ./run_tests.sh --cc-args "-j14 -O2"       # Pass flags to C compiler
#   ./run_tests.sh --build jar                # Build fat JAR (default for suite)
#   ./run_tests.sh --build gradle             # Use gradle run (no JAR)
#   ./run_tests.sh --build proguard           # Use ProGuard-optimized JAR
#
set -euo pipefail

usage() {
    sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# //'
    exit 0
}

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT.jar"
RELEASE_JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT-release.jar"
OUT_DIR="$ROOT/test_out"
TESTS_DIR="$ROOT/tests"

SKIP_UNIT=false
RUN_TEST=""
EXTRA_ARGS=""
COMPILER=""
CC_ARGS=""
BUILD="jar"

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
        *)             echo "Unknown option: $1 (use --help)"; exit 1 ;;
    esac
done

BUILD="$(echo "$BUILD" | tr '[:upper:]' '[:lower:]')"

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
    set -e
    if [[ "$verbose" == "true" ]]; then
        while IFS= read -r line; do printf "  %s\n" "$line"; done <<< "$output"
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
    if [[ "$verbose" == "true" ]]; then pass "Transpilation succeeded"; fi

    # ── Discover generated .c files ─────────────────────────────
    local c_files=()
    local ktc_first=()
    local others=()
    for f in "$test_out_dir"/*.c; do
        [[ -e "$f" ]] || continue
        local cname
        cname="$(basename "$f")"
        if [[ "$cname" == "ktc_std.c" ]]; then
            ktc_first+=("$cname")
        else
            others+=("$cname")
        fi
    done
    IFS=$'\n' others=($(sort <<< "${others[*]}")); unset IFS
    c_files=("${ktc_first[@]}" "${others[@]}")

    if [[ ${#c_files[@]} -eq 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "No .c files generated"
        else
            fail "$name (no .c files generated)"
        fi
        return 1
    fi

    # Binary name: use test name
    local exe_path="$test_out_dir/$name"

    local c_sources=()
    for cf in "${c_files[@]}"; do
        c_sources+=("$test_out_dir/$cf")
    done

    # ── Compile ─────────────────────────────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Compile"
        showcmd "$CC -std=c11 $CC_ARGS -o $exe_path ${c_sources[*]}"
        echo ""
    fi
    set +e
    output=$("$CC" -std=c11 $CC_ARGS -o "$exe_path" "${c_sources[@]}" 2>&1)
    local compile_exit=$?
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
    if [[ "$verbose" == "true" ]]; then pass "Compilation succeeded -> $exe_path"; fi

    # ── Generated files (verbose only) ──────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Generated Files"
        for f in "$test_out_dir"/*; do
            local fname
            fname="$(basename "$f")"
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
    if [[ "$verbose" == "true" ]]; then
        "$exe_path" 2>&1 | while IFS= read -r line; do printf "  %s\n" "$line"; done
        local run_exit=${PIPESTATUS[0]}
    else
        output=$("$exe_path" 2>&1)
        local run_exit=$?
    fi
    set -e
    if [[ "$verbose" == "true" ]]; then echo ""; fi
    if [[ $run_exit -ne 0 ]]; then
        if [[ "$verbose" == "true" ]]; then
            fail "Runtime error (exit code $run_exit)"
        else
            fail "$name (runtime error, exit code $run_exit)"
            printf "  ${GRAY}%s${NC}\n" "$output"
        fi
        return 1
    fi
    if [[ "$verbose" == "true" ]]; then
        pass "Program exited successfully (code 0)"
    fi

    if [[ "$verbose" != "true" ]]; then pass "$name"; fi
    return 0
}

# ════════════════════════════════════════════════════════════════
# Single-test run mode: --run <TestDirName>
# ════════════════════════════════════════════════════════════════
if [[ -n "$RUN_TEST" ]]; then
    info "Using C compiler: $CC"

    # ── Build ──────────────────────────────────────────────────
    if [[ "$BUILD" != "gradle" ]]; then
        section "Build"
        case "$BUILD" in
            proguard)
                showcmd "gradlew proguard"
                "$ROOT/gradlew" proguard --quiet 2>&1
                if [[ ! -f "$RELEASE_JAR" ]]; then
                    echo "ERROR: ProGuard build failed"
                    exit 1
                fi
                pass "Built $RELEASE_JAR"
                ;;
            *)
                showcmd "gradlew jar"
                "$ROOT/gradlew" jar --quiet 2>&1
                if [[ ! -f "$JAR" ]]; then
                    echo "ERROR: JAR build failed"
                    exit 1
                fi
                pass "Built $JAR"
                ;;
        esac
    fi

    test_src_dir="$TESTS_DIR/$RUN_TEST"
    if [[ ! -d "$test_src_dir" ]]; then
        echo "ERROR: test directory not found: $test_src_dir"
        echo "Available tests:"
        for d in "$TESTS_DIR"/*/; do
            [[ -d "$d" ]] && echo "  - $(basename "$d")"
        done
        exit 1
    fi

    if invoke_test "$RUN_TEST" "$test_src_dir" "$OUT_DIR/$RUN_TEST" "true" "$EXTRA_ARGS"; then
        exit 0
    else
        exit 1
    fi
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
            "$ROOT/gradlew" proguard --quiet 2>&1
            if [[ ! -f "$RELEASE_JAR" ]]; then
                echo "ERROR: ProGuard build failed"
                exit 1
            fi
            pass "Built $RELEASE_JAR"
            ;;
        *)
            section "Building transpiler JAR"
            "$ROOT/gradlew" jar --quiet 2>&1
            if [[ ! -f "$JAR" ]]; then
                echo "ERROR: JAR build failed"
                exit 1
            fi
            pass "Built $JAR"
            ;;
    esac
fi

# ── Prepare output directory ────────────────────────────────────
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

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
            invoke_test "$dir_name" "$dir" "$OUT_DIR/$dir_name" "false" "$EXTRA_ARGS" > "$OUT_DIR/${dir_name}.out" 2>&1
            echo $? > "$OUT_DIR/${dir_name}.code"
        ) &
        pids+=($!)
    done

    for pid in "${pids[@]}"; do
        wait "$pid" || true
    done

    for dir in "${test_dirs[@]}"; do
        dir_name="$(basename "$dir")"
        exit_code=$(cat "$OUT_DIR/${dir_name}.code" 2>/dev/null || echo 1)
        cat "$OUT_DIR/${dir_name}.out" 2>/dev/null || true
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
