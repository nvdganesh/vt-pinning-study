#!/usr/bin/env bash
#
# trim_run.sh -- a quick sanity check before you run the full sweep.
#
# Exercises ONE endpoint (the primary pinning measurement) at TWO arrival
# rates on BOTH JDKs. Total wall time: roughly 5-10 minutes.
# If this produces a clean results/summary.csv with a visible throughput
# difference between jdk21 and jdk24 on the synchronized-unique-lock
# endpoint, your entire pipeline is working end to end.
#
# Run from the project root:
#   bash scripts/trim_run.sh
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

# -----------------------------------------------------------------------
# Paste the same JDK paths you set in run_sweep.sh
# -----------------------------------------------------------------------
JDK21_HOME="/c/Program Files/Java/jdk-21"
JDK24_HOME="/c/Program Files/Eclipse Adoptium/jdk-24.0.2.12-hotspot"

declare -A JDK_MAP
JDK_MAP["jdk21"]="$JDK21_HOME"
JDK_MAP["jdk24"]="$JDK24_HOME"
JDK_ORDER=("jdk21" "jdk24")

# Just one endpoint and two RPS levels for the trim run
ENDPOINTS=("io-synchronized-pinned-uniquelock")
TARGET_RPS_LEVELS=(100 500)
REPEATS=1
DURATION_S=20
RAMP_S=5
DELAY_MS=50
CARRIER_PARALLELISM=4
HEALTH_TIMEOUT_S=30
COOLDOWN_S=2
APP_PORT=8080
BASE_URL="http://localhost:${APP_PORT}"
APP_JAR="app/target/vt-pinning-app.jar"
RESULTS_ROOT="results"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

switch_jdk() {
  export JAVA_HOME="$1"
  export PATH="${1}/bin:$PATH"
}

wait_for_health() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_S))
  while [ $SECONDS -lt $deadline ]; do
    if curl -fsS "${BASE_URL}/actuator/health" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

latest_gatling_dir() { ls -td load/results/*/ 2>/dev/null | head -n1 || true; }

switch_jdk "$JDK21_HOME"
log "Building jar..."
mvn -f app/pom.xml -q clean package -DskipTests
mvn -f load/pom.xml -q clean test-compile
mkdir -p "$RESULTS_ROOT"

for label in "${JDK_ORDER[@]}"; do
  jdk_home="${JDK_MAP[$label]}"
  switch_jdk "$jdk_home"
  log "=== $label ==="

  for endpoint in "${ENDPOINTS[@]}"; do
    for rps in "${TARGET_RPS_LEVELS[@]}"; do
      run_dir="${RESULTS_ROOT}/${label}/${endpoint}/rps_${rps}/run_1"
      mkdir -p "$run_dir"
      jfr_file="$(pwd)/${run_dir}/recording.jfr"

      log "  ${endpoint} @ rps=${rps}"

      "${jdk_home}/bin/java" \
        -Djdk.virtualThreadScheduler.parallelism="${CARRIER_PARALLELISM}" \
        -Djdk.virtualThreadScheduler.maxPoolSize="${CARRIER_PARALLELISM}" \
        -Dvtstudy.classinit.sleepms="${DELAY_MS}" \
        -Dserver.port="${APP_PORT}" \
        -XX:StartFlightRecording="filename=${jfr_file},settings=profile,disk=true,dumponexit=true" \
        -jar "${APP_JAR}" > "${run_dir}/app.log" 2>&1 &
      app_pid=$!
      # Git Bash (MSYS) has its own internal PID space -- $! is NOT the real
      # Windows PID. jcmd is a native Windows tool and needs the real one
      # (from /proc/<msys-pid>/winpid), or JFR.dump silently targets a
      # nonexistent process and produces a 0-byte file every time.
      app_winpid=$(cat "/proc/${app_pid}/winpid" 2>/dev/null || echo "$app_pid")

      if ! wait_for_health; then
        echo "  FAIL: app did not start. See ${run_dir}/app.log" >&2
        kill "$app_pid" 2>/dev/null || true
        continue
      fi

      mvn -f load/pom.xml -q gatling:test \
        -DbaseUrl="${BASE_URL}" \
        -DendpointPath="/${endpoint}" \
        -DtargetRps="${rps}" \
        -DdurationSeconds="${DURATION_S}" \
        -DrampSeconds="${RAMP_S}" \
        -DdelayMs="${DELAY_MS}" || true

      gdir="$(latest_gatling_dir)"
      [ -n "$gdir" ] && cp -r "$gdir" "${run_dir}/gatling" || true

      "${jdk_home}/bin/jcmd" "$app_winpid" JFR.dump filename="${jfr_file}" >/dev/null 2>&1 || true
      kill "$app_pid" 2>/dev/null || true
      wait "$app_pid" 2>/dev/null || true

      if [ -f "$jfr_file" ]; then
        "${jdk_home}/bin/jfr" print \
          --events jdk.VirtualThreadPinned --json "$jfr_file" \
          > "${run_dir}/pin_events.json" 2>/dev/null || true
      fi

      sleep "$COOLDOWN_S"
    done
  done
done

log "Trim run done. Running parser..."
python scripts/parse_results.py

log ""
log "Check results/summary.csv -- you should see:"
log "  jdk21 + io-synchronized-pinned-uniquelock -> much lower throughput than jdk24"
log "  That contrast is the core finding of the paper."
