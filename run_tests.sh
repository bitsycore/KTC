#!/usr/bin/env bash
#
# run_tests.sh — Build the transpiler, then run all integration tests.
#
# Each subdirectory under tests/ is one test. All .kt files in the directory
# are transpiled together. Adding a new directory = adding a new test.
#
# Usage:
#   ./run_tests.sh                        # Run all tests
#   ./run_tests.sh --skip-unit            # Skip unit tests, only run integration
#   ./run_tests.sh --run HashMapTest      # Transpile, compile & run a single test
#   ./run_tests.sh --run game --args "--mem-track"  # Pass extra args to transpiler
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/libs/KotlinToC-1.0-SNAPSHOT.jar"
OUT_DIR="$ROOT/test_out"
TESTS_DIR="$ROOT/tests"

SKIP_UNIT=false
RUN_TEST=""
EXTRA_ARGS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-unit) SKIP_UNIT=true; shift ;;
        --run)       RUN_TEST="$2"; shift 2 ;;
        --args)      EXTRA_ARGS="$2"; shift 2 ;;
        *)           echo "Unknown option: $1"; exit 1 ;;
    esac
done

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
CC=""
for candidate in gcc clang cc; do
    if command -v "$candidate" &>/dev/null; then
        CC="$candidate"
        break
    fi
done
if [[ -z "$CC" ]]; then
    echo "ERROR: No C compiler found (tried gcc, clang, cc). Install one and add to PATH."
    exit 1
fi

# ── Helper: transpile, compile, run one test directory ──────────
# Usage: invoke_test <name> <test_src_dir> <test_out_dir> [verbose] [extra_args]
# Returns 0 on success, 1 on failure.
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
        showcmd "java -jar KotlinToC.jar $kt_names -o $test_out_dir$extra_display"
        echo ""
    fi
    set +e
    local output
    # shellcheck disable=SC2086
    output=$(java -jar "$JAR" "${kt_files[@]}" -o "$test_out_dir" $extra_args 2>&1)
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
        if [[ "$cname" == "ktc.c" ]]; then
            ktc_first+=("$cname")
        else
            others+=("$cname")
        fi
    done
    # Sort others alphabetically
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

    # Binary name: first non-ktc .c file without extension
    local bin_base=""
    for cf in "${c_files[@]}"; do
        if [[ "$cf" != "ktc.c" ]]; then
            bin_base="${cf%.c}"
            break
        fi
    done
    [[ -z "$bin_base" ]] && bin_base="${c_files[0]%.c}"

    local c_sources=()
    for cf in "${c_files[@]}"; do
        c_sources+=("$test_out_dir/$cf")
    done
    local exe_path="$test_out_dir/$bin_base"

    # ── Compile ─────────────────────────────────────────────────
    if [[ "$verbose" == "true" ]]; then
        section "Compile"
        showcmd "$CC -std=c11 -o $exe_path ${c_sources[*]}"
        echo ""
    fi
    set +e
    output=$("$CC" -std=c11 -o "$exe_path" "${c_sources[@]}" 2>&1)
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

    if [[ "$verbose" != "true" ]]; then pass "$name"; fi
    return 0
}

# ════════════════════════════════════════════════════════════════
# Single-test run mode: --run <TestDirName>
# ════════════════════════════════════════════════════════════════
if [[ -n "$RUN_TEST" ]]; then
    info "Using C compiler: $CC"

    # ── Build JAR if needed ─────────────────────────────────────
    section "Build"
    if [[ ! -f "$JAR" ]]; then
        showcmd "gradlew jar"
        "$ROOT/gradlew" jar --quiet 2>&1
        if [[ ! -f "$JAR" ]]; then
            echo "ERROR: JAR build failed"
            exit 1
        fi
        pass "Built $JAR"
    else
        info "JAR up to date"
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

# ── 2. Build JAR ───────────────────────────────────────────────
section "Building transpiler JAR"
"$ROOT/gradlew" jar --quiet 2>&1
if [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR not found at $JAR"
    exit 1
fi
pass "Built $JAR"

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
    # Sort directories by name
    IFS=$'\n' test_dirs=($(printf '%s\n' "${test_dirs[@]}" | sort)); unset IFS

    for dir in "${test_dirs[@]}"; do
        dir_name="$(basename "$dir")"
        ((TOTAL++)) || true
        if invoke_test "$dir_name" "$dir" "$OUT_DIR/$dir_name" "false" "$EXTRA_ARGS"; then
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
