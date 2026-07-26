#!/usr/bin/env python3
"""
Walks results/<jdk>/<endpoint>/rps_<rps>/run_<n>/ and produces
results/summary.csv with one row per run, combining:
  - Gatling's global_stats.json (throughput, latency percentiles)
  - JFR's jdk.VirtualThreadPinned event count and total duration

IMPORTANT, read before trusting the output:
  Both Gatling's JSON report schema and `jfr print --json`'s schema have
  changed across tool/JDK versions and are not guaranteed stable. Before
  running this over your full sweep, run it on ONE run directory first,
  inspect results/summary.csv, and spot-check the numbers against the
  Gatling HTML report and a manual `jfr print` of the same recording. If
  field names below don't match what your installed versions produce,
  adjust GATLING_STAT_PATHS / PIN_EVENT_TYPE_KEYS accordingly -- this
  script is a documented starting point, not a guaranteed-correct parser
  for every Gatling/JDK version.
"""
import csv
import json
import re
import sys
from pathlib import Path

RESULTS_ROOT = Path("results")
PIN_EVENT_TYPE = "jdk.VirtualThreadPinned"


def find_global_stats(gatling_dir: Path):
    """Gatling writes js/global_stats.json under the per-run report folder."""
    candidates = list(gatling_dir.rglob("global_stats.json"))
    return candidates[0] if candidates else None


def parse_gatling_stats(stats_path: Path) -> dict:
    with open(stats_path) as f:
        data = json.load(f)

    # Verified against Gatling 3.10.5's actual global_stats.json: percentiles1-4
    # are TOP-LEVEL keys (there is no "percentiles" wrapper object), and each
    # value -- like meanNumberOfRequestsPerSecond -- is itself a
    # {total, ok, ko} dict, not a scalar.
    return {
        "requests_total": data.get("numberOfRequests", {}).get("total"),
        "requests_ok": data.get("numberOfRequests", {}).get("ok"),
        "requests_ko": data.get("numberOfRequests", {}).get("ko"),
        "mean_response_ms": data.get("meanResponseTime", {}).get("total"),
        "p50_ms": data.get("percentiles1", {}).get("total"),
        "p75_ms": data.get("percentiles2", {}).get("total"),
        "p95_ms": data.get("percentiles3", {}).get("total"),
        "p99_ms": data.get("percentiles4", {}).get("total"),
        "mean_requests_per_sec": data.get("meanNumberOfRequestsPerSecond", {}).get("total"),
    }


def _walk(obj):
    """Recursively yield every dict found anywhere in a parsed JSON tree."""
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from _walk(v)
    elif isinstance(obj, list):
        for item in obj:
            yield from _walk(item)


def _duration_to_ms(value) -> float:
    """JFR's --json duration fields (verified against real `jfr print --json`
    output on JDK 21/24) are ISO-8601 duration strings like 'PT0.058638S',
    NOT plain 'NN ms' strings. Handle that format, with a fallback for
    plain unit-suffixed strings in case other tool versions differ."""
    if value is None:
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip()
    iso = re.match(r"^PT(?:([\d.]+)H)?(?:([\d.]+)M)?(?:([\d.]+)S)?$", s)
    if iso and any(iso.groups()):
        hours, minutes, seconds = iso.groups()
        total_s = float(hours or 0) * 3600 + float(minutes or 0) * 60 + float(seconds or 0)
        return total_s * 1000.0
    m = re.match(r"([\d.]+)\s*(ms|s|us|ns)?", s)
    if not m:
        return 0.0
    num = float(m.group(1))
    unit = m.group(2) or "ms"
    factor = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1000.0}[unit]
    return num * factor


def parse_pin_events(pin_events_path: Path) -> dict:
    if not pin_events_path.exists() or pin_events_path.stat().st_size == 0:
        return {"pin_event_count": 0, "pin_total_duration_ms": 0.0}

    try:
        with open(pin_events_path) as f:
            data = json.load(f)
    except json.JSONDecodeError:
        # `jfr print` without --json, or a truncated/empty recording.
        return {"pin_event_count": 0, "pin_total_duration_ms": 0.0}

    count = 0
    total_ms = 0.0
    for node in _walk(data):
        node_type = node.get("type")
        if node_type == PIN_EVENT_TYPE:
            count += 1
            values = node.get("values", node)
            total_ms += _duration_to_ms(values.get("duration"))

    return {"pin_event_count": count, "pin_total_duration_ms": round(total_ms, 3)}


def main():
    rows = []
    if not RESULTS_ROOT.exists():
        print(f"No '{RESULTS_ROOT}' directory found; run scripts/run_sweep.sh first.", file=sys.stderr)
        sys.exit(1)

    for jdk_dir in sorted(RESULTS_ROOT.iterdir()):
        if not jdk_dir.is_dir():
            continue
        for endpoint_dir in sorted(jdk_dir.iterdir()):
            for rps_dir in sorted(endpoint_dir.glob("rps_*")):
                rps = rps_dir.name.replace("rps_", "")
                for run_dir in sorted(rps_dir.glob("run_*")):
                    run_no = run_dir.name.replace("run_", "")

                    row = {
                        "jdk": jdk_dir.name,
                        "endpoint": endpoint_dir.name,
                        "target_rps": rps,
                        "run": run_no,
                    }

                    gatling_dir = run_dir / "gatling"
                    stats_path = find_global_stats(gatling_dir) if gatling_dir.exists() else None
                    if stats_path:
                        try:
                            row.update(parse_gatling_stats(stats_path))
                        except Exception as e:
                            print(f"WARN: failed parsing {stats_path}: {e}", file=sys.stderr)
                    else:
                        print(f"WARN: no global_stats.json under {gatling_dir}", file=sys.stderr)

                    pin_path = run_dir / "pin_events.json"
                    row.update(parse_pin_events(pin_path))

                    rows.append(row)

    if not rows:
        print("No runs found under results/. Nothing to summarize.", file=sys.stderr)
        sys.exit(1)

    fieldnames = list(rows[0].keys())
    for r in rows:
        for k in r:
            if k not in fieldnames:
                fieldnames.append(k)

    out_path = RESULTS_ROOT / "summary.csv"
    with open(out_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows to {out_path}")


if __name__ == "__main__":
    main()
