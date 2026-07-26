#!/usr/bin/env python3
"""
Quick plotting helper -- not required, just saves you re-deriving these
two figures by hand. Produces, per endpoint:
  results/plots/<endpoint>_throughput.png   (throughput vs target RPS, by JDK)
  results/plots/<endpoint>_p99.png          (p99 latency vs target RPS, by JDK)

Requires: pip install pandas matplotlib --break-system-packages
"""
import sys
from pathlib import Path

try:
    import pandas as pd
    import matplotlib.pyplot as plt
except ImportError:
    print("Install deps first: pip install pandas matplotlib --break-system-packages", file=sys.stderr)
    sys.exit(1)

RESULTS_ROOT = Path("results")
SUMMARY_CSV = RESULTS_ROOT / "summary.csv"
PLOTS_DIR = RESULTS_ROOT / "plots"


def main():
    if not SUMMARY_CSV.exists():
        print(f"{SUMMARY_CSV} not found -- run scripts/parse_results.py first.", file=sys.stderr)
        sys.exit(1)

    df = pd.read_csv(SUMMARY_CSV)
    df["target_rps"] = pd.to_numeric(df["target_rps"], errors="coerce")
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)

    # Average across repeats for a cleaner line; report std dev in the
    # paper's table even though the plot just shows the mean.
    grouped = df.groupby(["endpoint", "jdk", "target_rps"], as_index=False).agg(
        mean_requests_per_sec=("mean_requests_per_sec", "mean"),
        p99_ms=("p99_ms", "mean"),
        pin_event_count=("pin_event_count", "mean"),
    )

    for endpoint, sub in grouped.groupby("endpoint"):
        fig, ax = plt.subplots()
        for jdk, line in sub.groupby("jdk"):
            line = line.sort_values("target_rps")
            ax.plot(line["target_rps"], line["mean_requests_per_sec"], marker="o", label=jdk)
        ax.set_xlabel("Target arrival rate (req/s)")
        ax.set_ylabel("Achieved throughput (req/s)")
        ax.set_title(f"{endpoint}: throughput vs. target rate")
        ax.legend()
        fig.tight_layout()
        fig.savefig(PLOTS_DIR / f"{endpoint}_throughput.png", dpi=150)
        plt.close(fig)

        fig, ax = plt.subplots()
        for jdk, line in sub.groupby("jdk"):
            line = line.sort_values("target_rps")
            ax.plot(line["target_rps"], line["p99_ms"], marker="o", label=jdk)
        ax.set_xlabel("Target arrival rate (req/s)")
        ax.set_ylabel("p99 latency (ms)")
        ax.set_title(f"{endpoint}: p99 latency vs. target rate")
        ax.legend()
        fig.tight_layout()
        fig.savefig(PLOTS_DIR / f"{endpoint}_p99.png", dpi=150)
        plt.close(fig)

    print(f"Wrote plots to {PLOTS_DIR}/")


if __name__ == "__main__":
    main()
