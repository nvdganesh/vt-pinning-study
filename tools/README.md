# Pre-flight smoke test (no Spring Boot, no Maven required)

`SmokeTest.java` exercises the pinning mechanics directly with real virtual
threads, independent of the whole Spring Boot + Gatling stack. Run this
FIRST on each machine/JDK you plan to test on -- it takes a few seconds and
catches most environment problems before you invest time in a full sweep.

## Build and run

```bash
mkdir -p /tmp/smoketest-classes
javac -d /tmp/smoketest-classes \
  tools/SmokeTest.java \
  app/src/main/java/com/vtstudy/app/pinning/*.java

java -cp /tmp/smoketest-classes \
  -Djdk.virtualThreadScheduler.parallelism=4 \
  SmokeTest
```

Adjust `parallelism` to a small number relative to the hardcoded
`concurrency=200` in `SmokeTest.java` -- the whole point is to force carrier
contention so pinning's effect is visible.

## What "good" output looks like

On JDK ≤23 you should see something close to:

```
io-baseline (no lock):              ~60 ms
synchronized, unique lock (pinned): ~1000 ms      <- should roughly match
                                                       ceil(concurrency/parallelism) * delayMs
ReentrantLock (never pins):         much higher    <- this is GENUINE
                                                       contention (shared lock),
                                                       not a bug -- see note below
Pinning slowdown ratio: > 10x
```

On JDK 24+ (JEP 491), the `synchronized, unique lock` number should drop
close to the `io-baseline` number.

## A known open item: shared-lock numbers can look confusing

`synchronized, shared lock` and `ReentrantLock` (which also uses a shared
lock in this test) can come out nearly **identical** even though one pins
and the other doesn't. That's not a bug -- at high concurrency with a
shared lock, genuine mutual-exclusion serialization (`concurrency x
delayMs`) dominates the timeline and can completely mask pinning's
marginal contribution. This is exactly why the study protocol treats the
**unique-lock** endpoint as the primary RQ1/RQ2 measurement and the
shared-lock variant as secondary/qualitative evidence only -- don't try to
read a clean pinning signal out of the shared-lock numbers alone.

## A known open item: class-init timing needs more investigation

`class-init-shared` (and the controller's `/class-init-own`) did not show
the sustained, carrier-saturation-scaled slowdown a naive model predicts,
and JFR captured far fewer `jdk.VirtualThreadPinned` events than the
number of concurrent class-initialization calls being made. This was
being actively investigated when this harness was handed off and is
**not yet resolved** -- treat RQ3/H3 numbers from these two endpoints as
provisional until you've either (a) confirmed the mechanism with your own
JFR inspection on real hardware, ideally comparing JDK 21 vs. JDK 24
directly, or (b) revised the class-init reproduction approach. Don't
write these into a paper without re-verifying first.
