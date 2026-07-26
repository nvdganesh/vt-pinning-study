package com.vtstudy.load;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * One simulation class covers every endpoint / arrival-rate / duration
 * combination in the sweep -- run_sweep.sh passes everything in via -D
 * system properties so nothing needs recompiling between runs.
 *
 * IMPORTANT METHODOLOGY NOTE (put the equivalent of this in the paper's
 * methodology section):
 *
 * This uses Gatling's OPEN workload model (rampUsersPerSec /
 * constantUsersPerSec): new virtual users arrive at a fixed target rate
 * regardless of whether earlier requests have completed yet. This is what
 * avoids coordinated omission. A CLOSED model (a fixed pool of N
 * concurrent users, each waiting for a response before issuing its next
 * request) would silently under-report tail latency exactly when the
 * server starts queuing -- which is precisely the failure mode pinning
 * causes, so a closed model would hide the effect you are trying to
 * measure.
 *
 * Because this is an open, rate-based model, "concurrency" in the study
 * protocol's sweep is actually driven by TARGET_RPS (arrival rate); the
 * resulting number of concurrently in-flight virtual threads is an
 * EMERGENT, measured quantity (rate x service time, by Little's Law), not
 * a parameter you set directly. Report both the target arrival rate and
 * the observed concurrency (derivable from Gatling's active-users metric
 * or from JFR thread counts) in your results table.
 */
public class PinningSimulation extends Simulation {

    private static final String BASE_URL    = System.getProperty("baseUrl", "http://localhost:8080");
    private static final String PATH        = System.getProperty("endpointPath", "/io-baseline");
    private static final int    DURATION_S  = Integer.getInteger("durationSeconds", 60);
    private static final int    RAMP_S      = Integer.getInteger("rampSeconds", 10);
    private static final double TARGET_RPS  = Double.parseDouble(System.getProperty("targetRps", "100"));
    private static final long   DELAY_MS    = Long.getLong("delayMs", 50L);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .userAgentHeader("vt-pinning-study-gatling")
            .disableCaching();

    private final ScenarioBuilder scn = scenario("pinning-probe")
            .exec(
                http("probe-request")
                    .get(session -> PATH + (PATH.contains("?") ? "&" : "?") + "delayMs=" + DELAY_MS)
                    .check(status().is(200))
            );

    {
        setUp(
            scn.injectOpen(
                rampUsersPerSec(0.0).to(TARGET_RPS).during(Duration.ofSeconds(RAMP_S)),
                constantUsersPerSec(TARGET_RPS).during(Duration.ofSeconds(DURATION_S))
            )
        ).protocols(httpProtocol);
    }
}
