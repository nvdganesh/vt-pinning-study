#!/usr/bin/env bash
#
# run_sweep.sh -- orchestrates the full experiment sweep on Windows (Git Bash)
#
# Prerequisites:
#   - JDK 21 installed natively (the one you already have)
#   - JDK 24 installed via Adoptium Temurin (see SETUP SECTION below)
#   - Maven 3.9+ on PATH
#   - curl on PATH (comes with Git Bash)
#   - A separate load-generator machine (or another terminal on LAN) for Gatling
#
# Run from the project root:
#   bash scripts/run_sweep.sh
#
set -euo pipefail

# Git Bash (MSYS) auto-converts any argv that looks like a POSIX absolute
# path into a Windows path when exec'ing a native binary (java.exe, via
# mvn) -- e.g. "-DendpointPath=/io-baseline" silently becomes
# "-DendpointPath=C:/Program Files/Git/io-baseline". Since that value then
# contains whitespace, Maven can't even propagate it to the forked JVM, and
# PinningSimulation.java silently falls back to its "/io-baseline" default
# regardless of which endpoint was actually requested.
#
# MSYS_NO_PATHCONV=1 disables conversion entirely, which breaks Maven's own
# bootstrap (it relies on path conversion to locate its own launcher jar via
# M2_HOME). A blanket MSYS2_ARG_CONV_EXCL="-D" is ALSO wrong -- it blocks
# conversion of Maven's own internal "-Dclassworlds.conf=<path>" argument,
# which genuinely needs it, corrupting Maven's own bootstrap path instead.
# Target only the one property that's actually a fake "path": endpointPath.
export MSYS2_ARG_CONV_EXCL="-DendpointPath="

# ===========================================================================
# SETUP SECTION -- edit this before you run anything
# ===========================================================================

# Absolute paths to each JDK home directory on YOUR machine.
# In Git Bash, Windows paths are written with forward slashes and a
# lowercase drive letter prefix, e.g. /c/Program\ Files/... 
#
# After installing JDK 24 from https://adoptium.net/temurin/releases/?version=24
# check the actual install directory (it varies by version/build):
#   ls "/c/Program Files/Eclipse Adoptium/"
# then paste the exact folder name below.
#
# IMPORTANT: if the path contains spaces (it usually does -- "Program Files"),
# keep the quotes around the variable everywhere it's used in this script.

JDK21_HOME="/c/Program Files/Java/jdk-21"
JDK24_HOME="/c/Program Files/Eclipse Adoptium/jdk-24.0.2.12-hotspot"

# Array of JDK homes to sweep. Keys are used in result directory names.
declare -A JDK_MAP
JDK_MAP["jdk21"]="$JDK21_HOME"
JDK_MAP["jdk24"]="$JDK24_HOME"

# Order matters for result readability: pre-fix first, post-fix second.
JDK_ORDER=("jdk21" "jdk24")

# Must match (or be less than) your machine's logical CPU count.
# Virtual thread pinning shows up most clearly when this is small relative
# to your peak concurrency, so don't just max it out.
# Check your CPU count: wmic cpu get NumberOfLogicalProcessors
CARRIER_PARALLELISM=4

# Endpoints to sweep. Comment out any you want to skip.
#
# Scope narrowed to RQ1/RQ2 (synchronized-pinning, before/after JEP 491) --
# class-init-own/class-init-shared (RQ3/H3) are excluded from this paper.
# The smoke test reproduced the same non-scaling, near-baseline anomaly on
# BOTH JDK 21 and JDK 24 (~60-70ms vs. a naive carrier-saturation model
# predicting ~650ms), with JFR capturing far fewer jdk.VirtualThreadPinned
# events than expected. That needs its own investigation (does JFR even
# observe class-init pinning?) before it's safe to report -- treat as
# future work. See tools/README.md and docs/study-protocol.md RQ3 section.
ENDPOINTS=(
  "io-baseline"
  "io-synchronized-pinned-uniquelock"
  "io-synchronized-pinned-sharedlock"
  "io-reentrantlock"
  "cpu-bound"
)

# Open-model arrival rates to sweep. Measured in requests/second.
# Start trimmed (e.g. just 50 200 500) for a first smoke-run before
# committing to the full matrix.
TARGET_RPS_LEVELS=(50 200 500 1000 2000 4000)

REPEATS=3
DURATION_S=60     # Gatling steady-state duration per run
RAMP_S=10         # ramp-up before steady state
DELAY_MS=50       # simulated blocking time per request in I/O endpoints
HEALTH_TIMEOUT_S=30
COOLDOWN_S=3      # seconds between runs to let OS settle

APP_PORT=8080
BASE_URL="http://localhost:${APP_PORT}"
APP_JAR="app/target/vt-pinning-app.jar"
RESULTS_ROOT="results"

# ===========================================================================
# END OF SETUP SECTION
# ===========================================================================

log() { echo "[$(date '+%H:%M:%S')] $*"; }

check_prereqs() {
  local missing=0
  for tool in mvn curl; do
    if ! command -v "$tool" &>/dev/null; then
      echo "ERROR: '$tool' not found on PATH. Install it before running this script." >&2
      missing=1
    fi
  done
  for label in "${JDK_ORDER[@]}"; do
    local jh="${JDK_MAP[$label]}"
    if [ ! -f "${jh}/bin/java.exe" ] && [ ! -f "${jh}/bin/java" ]; then
      echo "ERROR: JDK not found at '${jh}'. Check the path in the SETUP SECTION." >&2
      missing=1
    fi
  done
  [ $missing -eq 0 ] || exit 1
}

switch_jdk() {
  local jh="$1"
  export JAVA_HOME="$jh"
  export PATH="${jh}/bin:$PATH"
  log "Active JDK: $("${jh}/bin/java" -version 2>&1 | head -1)"
}

wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_S))
  while [ $SECONDS -lt $deadline ]; do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: app did not become healthy within ${HEALTH_TIMEOUT_S}s" >&2
  return 1
}

latest_gatling_dir() {
  ls -td load/results/*/ 2>/dev/null | head -n1 || true
}

# ---------------------------------------------------------------------------
# Build the app ONCE at JDK 21 level.
# Compiled bytecode target is set to Java 21 in app/pom.xml so the same
# jar runs on both JDK 21 and JDK 24 without recompiling.
# Rebuilding per JDK version would introduce a confound -- don't do it.
# ---------------------------------------------------------------------------
check_prereqs

switch_jdk "$JDK21_HOME"
log "Building application jar (once, reused across all JDK versions)..."
mvn -f app/pom.xml -q clean package -DskipTests

log "Compiling Gatling simulation..."
mvn -f load/pom.xml -q clean test-compile

mkdir -p "$RESULTS_ROOT"

# ---------------------------------------------------------------------------
# Main sweep
# ---------------------------------------------------------------------------
for label in "${JDK_ORDER[@]}"; do
  jdk_home="${JDK_MAP[$label]}"
  switch_jdk "$jdk_home"
  java_bin="${jdk_home}/bin/java"
  jfr_bin="${jdk_home}/bin/jfr"
  jcmd_bin="${jdk_home}/bin/jcmd"

  log "============================================================"
  log "  JDK: $label"
  log "============================================================"

  for endpoint in "${ENDPOINTS[@]}"; do
    for rps in "${TARGET_RPS_LEVELS[@]}"; do
      for run in $(seq 1 "$REPEATS"); do

        run_dir="${RESULTS_ROOT}/${label}/${endpoint}/rps_${rps}/run_${run}"

        # Resume support: skip runs that already completed successfully
        # (global_stats.json only exists if Gatling produced a real report).
        # Runs that crashed/hung partway through (e.g. app.log and JFR data
        # present but no gatling/ report) are correctly NOT skipped.
        if compgen -G "${run_dir}/gatling/*/js/global_stats.json" > /dev/null 2>&1; then
          log "--- ${label} | ${endpoint} | rps=${rps} | run=${run} --- SKIP (already completed)"
          continue
        fi

        rm -rf "$run_dir"
        mkdir -p "$run_dir"
        jfr_file="$(pwd -W 2>/dev/null || pwd)/${run_dir}/recording.jfr"

        log "--- ${label} | ${endpoint} | rps=${rps} | run=${run} ---"

        "$java_bin" \
          -Djdk.virtualThreadScheduler.parallelism="${CARRIER_PARALLELISM}" \
          -Djdk.virtualThreadScheduler.maxPoolSize="${CARRIER_PARALLELISM}" \
          -Dvtstudy.classinit.sleepms="${DELAY_MS}" \
          -Dserver.port="${APP_PORT}" \
          -XX:StartFlightRecording="filename=${jfr_file},settings=profile,disk=true,dumponexit=true" \
          -jar "${APP_JAR}" > "${run_dir}/app.log" 2>&1 &
        app_pid=$!
        # Git Bash (MSYS) has its own internal PID space -- $! is NOT the
        # real Windows PID. jcmd is a native Windows tool and needs the
        # real one (from /proc/<msys-pid>/winpid), or JFR.dump silently
        # targets a nonexistent process and produces a 0-byte file.
        app_winpid=$(cat "/proc/${app_pid}/winpid" 2>/dev/null || echo "$app_pid")

        if ! wait_for_health; then
          echo "  SKIP: app failed to start. Check ${run_dir}/app.log" >&2
          kill "$app_pid" 2>/dev/null || true
          continue
        fi

        # Reset shared class-init state so each run starts with a fresh
        # contention condition -- otherwise only the very first run ever
        # actually triggers class-init blocking.
        if [ "$endpoint" = "class-init-shared" ]; then
          curl -fsS -X POST "${BASE_URL}/admin/class-init-shared/reset" >/dev/null
        fi

        mvn -f load/pom.xml -q gatling:test \
          -DbaseUrl="${BASE_URL}" \
          -DendpointPath="/${endpoint}" \
          -DtargetRps="${rps}" \
          -DdurationSeconds="${DURATION_S}" \
          -DrampSeconds="${RAMP_S}" \
          -DdelayMs="${DELAY_MS}" \
          || echo "  WARN: Gatling reported a failure. Check ${run_dir}." >&2

        gdir="$(latest_gatling_dir)"
        if [ -n "$gdir" ]; then
          cp -r "$gdir" "${run_dir}/gatling"
        else
          echo "  WARN: could not locate Gatling results directory for this run." >&2
        fi

        # Dump JFR before killing the JVM.
        "$jcmd_bin" "$app_winpid" JFR.dump filename="${jfr_file}" >/dev/null 2>&1 || true
        kill "$app_pid" 2>/dev/null || true
        wait "$app_pid" 2>/dev/null || true

        if [ -f "$jfr_file" ]; then
          "$jfr_bin" print \
            --events jdk.VirtualThreadPinned \
            --json "$jfr_file" \
            > "${run_dir}/pin_events.json" \
            2>"${run_dir}/jfr_print.err" || true
        else
          echo "  WARN: no JFR recording at ${jfr_file}" >&2
        fi

        sleep "$COOLDOWN_S"
      done
    done
  done
done

log "Sweep complete. Run:  python scripts/parse_results.py"
