#!/usr/bin/env python3
"""
AgentGate — QA2 Performance Chart Generator
Generates:
  1. docs/quality/performance/charts/qa2_latency_distribution.png
  2. docs/quality/performance/charts/qa2_throughput_time_series.png
  3. docs/quality/performance/charts/qa2_gateway_vs_downstream.png
"""

import csv
import json
import os
import sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA_DIR = os.path.join(ROOT_DIR, "scripts", "benchmarks", "data")
CHARTS_DIR = os.path.join(ROOT_DIR, "docs", "quality", "performance", "charts")
os.makedirs(CHARTS_DIR, exist_ok=True)

def generate_charts():
    summary_path = os.path.join(DATA_DIR, "qa2_benchmark_summary.json")
    samples_path = os.path.join(DATA_DIR, "qa2_latency_samples.csv")
    ts_path = os.path.join(DATA_DIR, "qa2_throughput_timeseries.csv")

    if not os.path.exists(summary_path) or not os.path.exists(samples_path):
        print(f"Error: Missing benchmark data in {DATA_DIR}")
        return

    with open(summary_path, "r", encoding="utf-8") as f:
        summary = json.load(f)

    # 1. Read latency samples
    direct_lats = []
    run1_overheads = []
    run2_overheads = []
    run3_overheads = []
    all_overheads = []
    all_gw_durs = []
    all_down_durs = []

    with open(samples_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row["status_code"] != "200":
                continue
            phase = row["phase"]
            c_lat = float(row["client_latency_ms"])
            gw_dur = float(row["gateway_duration_ms"])
            down_dur = float(row["downstream_duration_ms"])
            over = float(row["overhead_ms"])

            if phase == "direct_baseline":
                direct_lats.append(c_lat)
            elif phase == "governed_run1":
                run1_overheads.append(over)
                all_overheads.append(over)
                all_gw_durs.append(gw_dur)
                all_down_durs.append(down_dur)
            elif phase == "governed_run2":
                run2_overheads.append(over)
                all_overheads.append(over)
                all_gw_durs.append(gw_dur)
                all_down_durs.append(down_dur)
            elif phase == "governed_run3":
                run3_overheads.append(over)
                all_overheads.append(over)
                all_gw_durs.append(gw_dur)
                all_down_durs.append(down_dur)

    # Chart 1: Latency Distribution & Cumulative Distribution Function (CDF)
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5.5))
    
    # Histogram of overheads
    counts, bins, _ = ax1.hist(all_overheads, bins=50, color="#1976D2", alpha=0.75, edgecolor="#0D47A1", density=True)
    p50 = np.percentile(all_overheads, 50)
    p95 = np.percentile(all_overheads, 95)
    p99 = np.percentile(all_overheads, 99)
    
    ax1.axvline(p50, color="#388E3C", linestyle="--", linewidth=1.8, label=f"p50: {p50:.2f} ms")
    ax1.axvline(p95, color="#F57C00", linestyle="--", linewidth=2.0, label=f"p95: {p95:.2f} ms")
    ax1.axvline(30.0, color="#D32F2F", linestyle="-", linewidth=2.2, label="QA2 SLA Limit: 30.0 ms")
    ax1.set_title("Incremental Gateway Overhead Distribution (18,000 calls)", fontsize=11, fontweight="bold")
    ax1.set_xlabel("Gateway Overhead (ms)", fontsize=10)
    ax1.set_ylabel("Probability Density", fontsize=10)
    ax1.legend(loc="upper right", fontsize=9)
    ax1.grid(True, linestyle=":", alpha=0.6)

    # CDF
    sorted_data = np.sort(all_overheads)
    yvals = np.arange(len(sorted_data)) / float(len(sorted_data) - 1)
    ax2.plot(sorted_data, yvals * 100.0, color="#0288D1", linewidth=2.2, label="Measured Overhead CDF")
    ax2.axvline(30.0, color="#D32F2F", linestyle="-", linewidth=2.2, label="QA2 Threshold (30 ms)")
    ax2.axhline(95.0, color="#7B1FA2", linestyle=":", linewidth=1.5, label="95th Percentile Target")
    ax2.scatter([p95], [95.0], color="#E65100", s=60, zorder=5, label=f"p95 Point ({p95:.2f} ms, 95%)")
    ax2.set_title("Overhead Cumulative Distribution Function (CDF)", fontsize=11, fontweight="bold")
    ax2.set_xlabel("Gateway Overhead (ms)", fontsize=10)
    ax2.set_ylabel("Cumulative Percentage (%)", fontsize=10)
    ax2.set_ylim([0, 105])
    ax2.legend(loc="lower right", fontsize=9)
    ax2.grid(True, linestyle=":", alpha=0.6)

    plt.tight_layout()
    chart1_path = os.path.join(CHARTS_DIR, "qa2_latency_distribution.png")
    fig.savefig(chart1_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart1_path}")

    # Chart 2: Throughput & Latency Time Series
    ts_seconds = []
    ts_rps = []
    ts_p95 = []
    with open(ts_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        offset = 0
        last_phase = None
        for row in reader:
            phase = row["phase"]
            if phase.startswith("governed"):
                sec = int(row["second"])
                if last_phase and phase != last_phase:
                    offset += 60
                last_phase = phase
                global_sec = sec + offset
                ts_seconds.append(global_sec)
                ts_rps.append(float(row["actual_rps"]))
                ts_p95.append(float(row["p95_overhead_ms"]))

    fig, ax1 = plt.subplots(figsize=(13, 5))
    ax2 = ax1.twinx()

    color_rps = "#1976D2"
    color_p95 = "#D32F2F"

    ax1.plot(ts_seconds, ts_rps, color=color_rps, linewidth=1.8, label="Delivered Throughput (RPS)")
    ax1.axhline(100.0, color="#0D47A1", linestyle="--", alpha=0.7, label="Target Load (100 RPS)")
    ax1.set_xlabel("Elapsed Time across 3 Steady-State Runs (seconds)", fontsize=10)
    ax1.set_ylabel("Throughput (Calls / Second)", color=color_rps, fontsize=10)
    ax1.tick_params(axis="y", labelcolor=color_rps)
    ax1.set_ylim([0, 120])

    ax2.plot(ts_seconds, ts_p95, color=color_p95, linewidth=1.6, linestyle="-.", label="1s Window Overhead p95 (ms)")
    ax2.axhline(30.0, color="#B71C1C", linestyle="-", linewidth=2.0, label="QA2 Threshold (30 ms)")
    ax2.set_ylabel("p95 Gateway Overhead (ms)", color=color_p95, fontsize=10)
    ax2.tick_params(axis="y", labelcolor=color_p95)

    # Shaded run boundaries
    ax1.axvline(60, color="#9E9E9E", linestyle=":", alpha=0.8)
    ax1.axvline(120, color="#9E9E9E", linestyle=":", alpha=0.8)
    ax1.text(30, 110, "Run 1", horizontalalignment="center", fontsize=9, fontweight="bold", color="#555")
    ax1.text(90, 110, "Run 2", horizontalalignment="center", fontsize=9, fontweight="bold", color="#555")
    ax1.text(150, 110, "Run 3", horizontalalignment="center", fontsize=9, fontweight="bold", color="#555")

    plt.title("QA2 Throughput & Gateway Overhead Latency Time Series (180s Total)", fontsize=11, fontweight="bold")
    ax1.grid(True, linestyle=":", alpha=0.5)

    lines1, labels1 = ax1.get_legend_handles_labels()
    lines2, labels2 = ax2.get_legend_handles_labels()
    ax1.legend(lines1 + lines2, labels1 + labels2, loc="upper right", fontsize=8.5)

    plt.tight_layout()
    chart2_path = os.path.join(CHARTS_DIR, "qa2_throughput_time_series.png")
    fig.savefig(chart2_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart2_path}")

    # Chart 3: Gateway vs Downstream Decomposition Bar / Box Plot
    fig, ax = plt.subplots(figsize=(10, 6))

    categories = ["Direct Downstream Baseline", "Downstream (Governed)", "Gateway Overhead", "Total Governed Duration"]
    direct_p50 = np.percentile(direct_lats, 50)
    direct_p95 = np.percentile(direct_lats, 95)
    down_p50 = np.percentile(all_down_durs, 50)
    down_p95 = np.percentile(all_down_durs, 95)
    over_p50 = np.percentile(all_overheads, 50)
    over_p95 = np.percentile(all_overheads, 95)
    gw_p50 = np.percentile(all_gw_durs, 50)
    gw_p95 = np.percentile(all_gw_durs, 95)

    p50s = [direct_p50, down_p50, over_p50, gw_p50]
    p95s = [direct_p95, down_p95, over_p95, gw_p95]

    x = np.arange(len(categories))
    width = 0.35

    rects1 = ax.bar(x - width/2, p50s, width, label="p50 (Median)", color="#42A5F5", edgecolor="#1565C0")
    rects2 = ax.bar(x + width/2, p95s, width, label="p95 (95th percentile)", color="#26A69A", edgecolor="#00695C")

    ax.axhline(30.0, color="#D32F2F", linestyle="--", linewidth=2.0, label="QA2 Gateway Overhead Target (30 ms)")

    ax.set_ylabel("Latency / Duration (ms)", fontsize=10)
    ax.set_title("Latency Breakdown: Baseline vs. Governed Pipeline Components", fontsize=11, fontweight="bold")
    ax.set_xticks(x)
    ax.set_xticklabels(categories, fontsize=9.5)
    ax.legend(loc="upper left", fontsize=9)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)

    def autolabel(rects):
        for rect in rects:
            height = rect.get_height()
            ax.annotate(f"{height:.2f}ms",
                        xy=(rect.get_x() + rect.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha="center", va="bottom", fontsize=8.5, fontweight="bold")

    autolabel(rects1)
    autolabel(rects2)

    plt.tight_layout()
    chart3_path = os.path.join(CHARTS_DIR, "qa2_gateway_vs_downstream.png")
    fig.savefig(chart3_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart3_path}")

if __name__ == "__main__":
    generate_charts()
