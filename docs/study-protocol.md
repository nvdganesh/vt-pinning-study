# Study Protocol: Quantifying Virtual-Thread Pinning Before and After JEP 491

**Target venue:** IEEE Software (practitioner-empirical article)
**Author:** Venkata Durga Ganesh Nandigam
**Status:** Draft protocol — fill in dated results as runs complete

---

## 1. Research Questions and Hypotheses

State these up front in the paper too — reviewers want to see the hypothesis before the data.

- **RQ1 (Magnitude):** Under realistic I/O-bound Spring Boot workloads, how much throughput and tail-latency degradation does `synchronized`-induced virtual-thread pinning cause on JDK 21–23, relative to an equivalent unpinned baseline?
- **RQ2 (Resolution):** Does upgrading to JDK 24 (JEP 491) recover that degradation without any application code change, and to what extent?
- **RQ3 (Residual risk):** Which pinning sources survive JEP 491 (native/FFM calls, class-init contention), and do they remain observable under load in a realistic stack?

**Hypotheses:**
- H1: Throughput collapses non-linearly once concurrent pinned requests approach the carrier-thread pool size on JDK ≤23.
- H2: JDK 24 removes the synchronized-pinning collapse entirely, converging to within noise of the never-pinned baseline.
- H3: A native-call pinning source (not addressed by JEP 491) still degrades throughput on JDK 24, proving the fix is partial, not total.

---

## 2. Experimental Variables (lean 2×2 design + sweep)

To keep this runnable on modest hardware, fix everything except two variables and a concurrency sweep.

| Variable | Levels | Notes |
|---|---|---|
| **JDK version** | JDK 21 (LTS, pre-fix) vs. JDK 24 (post-JEP 491) | Use SDKMAN to switch; pin exact build numbers in the paper |
| **Pinning source state** | (a) Pinning JDBC driver, (b) Fixed/non-pinning JDBC driver, (c) Native-call pinning stub (FFM or JNI sleep) | (c) is your H3 / residual-risk condition |
| **Concurrency** (sweep, not fixed) | e.g., 50 / 200 / 500 / 1000 / 2000 / 4000 concurrent virtual threads | Sweep until throughput plateaus or collapses |
| **Carrier pool size** | Fixed and reported, e.g. `-Djdk.virtualThreadScheduler.parallelism=N` matching vCPU count | Must be explicit and constant across JDK versions for fair comparison |

This gives ~3 driver states × 2 JDK versions × 6 concurrency steps = 36 runs. Very tractable for a lean setup, each run a few minutes.

**Library choice for pinning vs. fixed (pick one pair, document exact versions):**
- MySQL Connector/J: pre-9.0.0 (synchronized) vs. ≥9.0.0 (ReentrantLock) — documented in bug #110512
- or pgjdbc: pre-fix vs. post-fix (pgjdbc#1951)

---

## 3. Environment

**Hardware (lean — this is a feature, not a limitation, for this experiment):**
- App-under-test VM: modest, fixed vCPU count (e.g., 4–8 vCPU), document exact spec
- **Separate** load-generator machine/VM — non-negotiable, see §5 on coordinated omission
- Same network segment, document latency between the two

**Software stack:**
- Spring Boot 3.2+ (RestClient / Tomcat or Jetty with virtual threads enabled via `spring.threads.virtual.enabled=true`)
- JDK 21 (specific build) and JDK 24 (specific build) via SDKMAN
- Database: real Postgres or MySQL instance (containerized is fine) — must produce genuine blocking I/O, not a mock
- Load generator: **wrk2** or **Gatling** (both avoid coordinated omission; avoid plain `wrk` or ApacheBench for latency claims)

**Endpoint design:** one endpoint that performs a DB read wrapped in a `synchronized` block in the driver's connection-handling path (this is where pinning naturally occurs, not an artificially inserted lock), so the result generalizes to real applications rather than a contrived demo.

---

## 4. Workload Design

- Primary workload: I/O-bound, fixed small CPU component (e.g., simple JSON serialization) + DB round-trip — mirrors realistic request shape, not a synthetic sleep().
- Keep request shape **constant** across all runs; only JDK version, driver state, and concurrency vary.
- Include one explicitly CPU-bound control workload (no I/O, no locks) run at the same concurrency levels — this is your isolation control proving any throughput differences are pinning, not virtual-threads-are-bad-at-CPU-work (a separate, known phenomenon you must not conflate with pinning).

---

## 5. Metrics and Instrumentation

| Metric | Tool | Why |
|---|---|---|
| Throughput (req/s) | wrk2 / Gatling output | Primary outcome |
| Latency p50 / p99 / p99.9 | wrk2 / Gatling (coordinated-omission corrected) | Tail latency is where pinning shows up first |
| `jdk.VirtualThreadPinned` JFR event count + duration | JDK Flight Recorder | **Causal evidence** linking throughput drop to pinning specifically — this is what separates your paper from blog-post anecdotes |
| Carrier-thread pool saturation | JFR thread dump / custom metric | Confirms mechanism: pool exhaustion correlates with collapse |
| CPU utilization | OS-level (e.g., `mpstat`) | Isolation control — proves bottleneck is pinning, not CPU |

**Coordinated omission warning (must address explicitly in the paper's methodology section):** naive load generators under-report tail latency once a server starts queuing, because they wait for a response before issuing the next request — silently hiding the exact failure mode pinning causes. Using wrk2 (constant-throughput mode) or Gatling's open-workload model is required for credible p99 numbers here.

---

## 6. Controls / Threats to Validity (pre-empt reviewer objections)

- **JIT warm-up:** discard first N seconds/requests of each run; report steady-state only.
- **Repetition:** minimum 3–5 repeats per configuration; report mean ± std dev or median with IQR, not single-run numbers.
- **CPU-bound isolation control** (§4) — prevents conflating pinning with the separate, already-documented "virtual threads can be slower for CPU-bound work" finding.
- **Carrier pool parity:** explicitly set and report `-Djdk.virtualThreadScheduler.parallelism` identically across JDK 21/24 runs — otherwise a reviewer can claim you compared different machines, not different JVM behavior.
- **Driver version isolation:** changing only the JDBC driver version between (a)/(b) — verify via JFR that the pinning event actually disappears in (b); don't assume the changelog claim.
- **Background noise:** dedicated VM, no co-tenant load; note this limitation honestly in the paper (lean setup = single-tenant, not data-center-scale — say so).

---

## 7. Run Procedure (checklist form)

1. Provision app VM + load-gen VM; record exact specs.
2. Install JDK 21 and JDK 24 via SDKMAN; record build numbers.
3. Stand up DB; verify clean state before each run.
4. For each (JDK version × driver state × concurrency level):
   a. Start app with explicit carrier-pool flag and JFR recording enabled.
   b. Warm up (discard).
   c. Run wrk2/Gatling for fixed duration (e.g., 60–120s) at target constant throughput.
   d. Collect: throughput, latency percentiles, JFR pin events, CPU%.
   e. Repeat 3–5x.
   f. Tear down / reset DB state.
5. Run CPU-bound control workload at same concurrency levels (no driver/lock variable needed).
6. Aggregate into the results table + plots (throughput vs. concurrency, latency vs. concurrency, faceted by JDK version and driver state).

---

## 8. Paper Outline (mapped to IEEE Software conventions)

1. **Title/Abstract** — lead with the finding, not the topic ("We measure an N% throughput recovery..." not "This paper studies virtual threads...")
2. **Introduction** — the practitioner pain point (the "my throughput didn't improve" surprise), state RQs and contribution explicitly
3. **Background** — virtual threads, pinning mechanism, JEP 491 (brief; cite, don't re-explain at length — assume IEEE Software's audience knows Java)
4. **Related Work** — JEP 444/491 themselves, the InfoQ/Open Liberty case study, danvega's toy benchmark (cite as motivation for why a *realistic* study is needed)
5. **Study Design** — §2–§6 above, condensed
6. **Results** — per RQ, with plots; report effect sizes, not just "improved"
7. **Threats to Validity** — §6 above, explicit
8. **Discussion / Practitioner Implications** — concrete guidance: when to upgrade, what residual pinning to still watch for, how to instrument with JFR in production
9. **Conclusion**

---

## 9. What to fill in as you go

- [ ] Exact JDK build numbers used
- [ ] Exact driver versions (pinning / fixed)
- [ ] VM specs (app + load-gen)
- [ ] Raw result tables per run
- [ ] Plots: throughput vs. concurrency; p99 latency vs. concurrency; JFR pin-event count vs. concurrency
- [ ] Native-call (residual pinning) experiment results
- [ ] CPU-bound control results
