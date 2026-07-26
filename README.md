# Virtual-Thread Pinning Study: Benchmark Harness

A runnable harness for the study described in `docs/study-protocol.md`:
quantifying virtual-thread pinning throughput/latency impact on JDK ≤23
versus JDK 24 (JEP 491), including the residual pinning cases JEP 491
does not fix.

This was built for a **lean setup** (one app VM + one load-gen VM, not a
data-center-scale lab) — see study-protocol.md §2-3 for why that's
actually methodologically fine for this particular question.


## What's in here

```
app/      Spring Boot service exposing one endpoint per pinning scenario
load/     Gatling open-model load test (avoids coordinated omission)
db/       Optional secondary experiment: real JDBC driver pinning
scripts/  Orchestration (run_sweep.sh) + result aggregation/plots
docs/     The study protocol this harness implements
```

## The endpoints (app module)

| Endpoint | What it isolates |
|---|---|
| `GET /cpu-bound` | Pure CPU, no I/O, no locks — isolation control |
| `GET /io-baseline` | Blocking I/O, no lock — ceiling baseline |
| `GET /io-synchronized-pinned-uniquelock` | `synchronized`, unique lock/call — **primary pinning measurement, mechanism only** |
| `GET /io-synchronized-pinned-sharedlock` | `synchronized` on one shared lock — pinning + real contention |
| `GET /io-reentrantlock` | `ReentrantLock` — already-fixed-style baseline, never pins |
| `GET /class-init-own` | Blocking inside one's own class initializer — **residual pinning, JEP 491 does not fix this** |
| `GET /class-init-shared` | Waiting on another thread's class init — **residual pinning, JEP 491 does not fix this either** |
| `POST /admin/class-init-shared/reset` | Call before each run/repeat of `class-init-shared` |
| `GET /db-query` | Optional: real Postgres query via JDBC (see `db/README.md`) |

All I/O endpoints accept `?delayMs=N` to control simulated blocking time.

## Prerequisites (Windows + Git Bash)

**JDK 21** — already installed natively. Confirm with `java -version` in Git Bash.

**JDK 24** — install from Adoptium (free, no Oracle account needed):
1. Go to https://adoptium.net/temurin/releases/?version=24
2. Download the **Windows x64 `.msi`** installer and run it.
3. After install, find the exact folder name:
   ```bash
   ls "/c/Program Files/Eclipse Adoptium/"
   # Example output: jdk-24.0.1.9-hotspot
   ```
4. Open `scripts/run_sweep.sh` and paste that path into `JDK24_HOME`.
   Do the same for `JDK21_HOME` using your existing JDK 21 install directory.

The scripts use `JAVA_HOME` switching directly — no SDKMAN needed.

**Maven 3.9+** — if not already installed:
```bash
# Check first
mvn -version
```
If missing, download the binary zip from https://maven.apache.org/download.cgi,
extract it anywhere, and add its `bin/` directory to your system PATH.

**curl** — ships with Git Bash, no action needed.

**jcmd / jfr** — ship inside the JDK itself (`$JAVA_HOME/bin/`). The scripts
reference them by full path so they don't need to be on your system PATH.

**Python 3** (optional, for `parse_results.py` and `plot_results.py`):
```bash
pip install pandas matplotlib
```

**A separate load-generator machine** is strongly recommended for production
data — running Gatling on the same box as the app adds CPU noise. For a
first smoke-run to prove the pipeline works, same-box is fine.

## Quick start

```bash
# 0. Install JDK 24 (see Prerequisites above), then confirm both JDKs work:
"/c/Program Files/Eclipse Adoptium/jdk-21.0.7+6-hotspot/bin/java" -version
"/c/Program Files/Eclipse Adoptium/jdk-24.0.1+9-hotspot/bin/java" -version

# 1. Edit the JDK paths and CARRIER_PARALLELISM in BOTH scripts:
#    scripts/run_sweep.sh  -- the full overnight sweep
#    scripts/trim_run.sh   -- a 5-10 minute end-to-end pipeline check

# 2. Run the pre-flight standalone smoke test first (no Spring/Maven needed):
mkdir -p /tmp/smk
javac -d /tmp/smk \
  tools/SmokeTest.java \
  app/src/main/java/com/vtstudy/app/pinning/*.java

# On JDK 21 -- expect a ~10-17x slowdown on the synchronized unique-lock row
"/c/Program Files/Eclipse Adoptium/jdk-21.0.7+6-hotspot/bin/java" \
  -cp /tmp/smk -Djdk.virtualThreadScheduler.parallelism=4 SmokeTest

# On JDK 24 -- the synchronized unique-lock row should collapse toward the baseline
"/c/Program Files/Eclipse Adoptium/jdk-24.0.1+9-hotspot/bin/java" \
  -cp /tmp/smk -Djdk.virtualThreadScheduler.parallelism=4 SmokeTest

# 3. If both look right, validate the full Spring Boot + Gatling pipeline
#    with a 5-10 minute trim run before committing to the full sweep:
bash scripts/trim_run.sh

# 3. Inspect ONE run's outputs by hand before trusting automation:
ls results/*/*/rps_*/run_1/
cat results/*/*/rps_*/run_1/gatling/*/js/global_stats.json | head -50
cat results/*/*/rps_*/run_1/pin_events.json | head -50
#    -> Confirm these look like real data, then check that
#       scripts/parse_results.py's field names actually match what you see
#       (Gatling's JSON schema and JFR's --json schema both drift across
#       versions -- see the big comment at the top of parse_results.py).

# 4. If trim_run.sh produces a clean results/summary.csv with a visible
#    throughput gap between jdk21 and jdk24 on the unique-lock endpoint,
#    run the full sweep (2 JDKs x 7 endpoints x 6 RPS levels x 3 repeats):
bash scripts/run_sweep.sh

# 5. Aggregate and plot:
python scripts/parse_results.py
pip install pandas matplotlib     # if not already installed
python scripts/plot_results.py
```

## Honest caveats before you trust the numbers

- **`class-init-own` and `class-init-shared` need more investigation
  before you rely on them for RQ3/H3.** During development, concurrent
  fresh-classloader inits did not show the carrier-saturation-scaled
  slowdown the design predicts, and JFR captured far fewer
  `jdk.VirtualThreadPinned` events than the number of concurrent
  class-init calls made. See `tools/README.md` for the full writeup and
  what's already been ruled out (JVM carrier-pool compensation was tested
  and is not the explanation). Verify this on real hardware, ideally
  comparing JDK 21 vs. JDK 24 directly, before citing these two endpoints
  in a paper.
- **JFR and Gatling JSON schemas drift across versions.** `parse_results.py`
  documents this at the top of the file. Verify against one real run
  before trusting `summary.csv` across the whole sweep.
- **`class-init-shared` only produces a meaningful data point if reset
  between repeats.** `run_sweep.sh` does this automatically, but if you
  ever call the endpoint manually outside the script, remember to
  `POST /admin/class-init-shared/reset` first or you'll just be hitting an
  already-initialized class.
- **The secondary `/db-query` (real-driver) experiment requires you to
  verify the exact pgjdbc version where the synchronized→ReentrantLock fix
  landed** (`db/README.md`) — don't take the version numbers in this repo
  as ground truth without checking pgjdbc's own changelog/issue tracker.
- **Carrier parallelism must be identical across JDK 21 and JDK 24 runs**
  for the comparison to be fair — `run_sweep.sh` sets this explicitly via
  `-Djdk.virtualThreadScheduler.parallelism`, don't remove that flag.
- **This is a lean, single-tenant setup, not a data-center-scale lab.**
  State that plainly in the paper's threats-to-validity section rather
  than letting a reviewer discover it — see `docs/study-protocol.md` §6.

## Mapping back to the paper

`docs/study-protocol.md` §7 has the full run checklist and §8 has the
paper outline mapped to IEEE Software's structure. Once you have
`results/summary.csv` and the plots, RQ1/RQ2 come straight from comparing
`io-synchronized-pinned-uniquelock` across JDK 21 vs. 24, with
`io-baseline` and `io-reentrantlock` as your ceiling/already-fixed
reference lines, and `cpu-bound` as your isolation control. RQ3/H3 comes
from `class-init-own` and `class-init-shared`, which should show little
to no improvement on JDK 24 — that persistence *is* the finding.
