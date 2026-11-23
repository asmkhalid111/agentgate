#!/usr/bin/env python3
"""
AgentGate — QA2 Root-Cause Diagnostic Chart Generator (Task 16E)
Generates:
  1. docs/quality/performance/charts/qa2_profile_component_breakdown.png
  2. docs/quality/performance/charts/qa2_profile_rate_scaling.png
  3. docs/quality/performance/charts/qa2_profile_audit_composition.png
  4. docs/quality/performance/charts/qa2_profile_stabilization.png
"""

import json
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA_DIR = os.path.join(ROOT_DIR, "scripts", "benchmarks", "data")
CHARTS_DIR = os.path.join(ROOT_DIR, "docs", "quality", "performance", "charts")
os.makedirs(CHARTS_DIR, exist_ok=True)

def generate_profile_charts():
    summary_path = os.path.join(DATA_DIR, "qa2_profile_summary.json")
    if not os.path.exists(summary_path):
        print(f"Error: {summary_path} not found")
        return

    with open(summary_path, "r", encoding="utf-8") as f:
        summary = json.load(f)

    phases = ["profile_10rps", "profile_30rps", "profile_60rps", "profile_100rps_win1", "profile_100rps_win2"]
    labels = ["10 RPS", "30 RPS", "60 RPS", "100 RPS (Win 1)", "100 RPS (Win 2)"]

    # -------------------------------------------------------------
    # Chart 1: Component Breakdown (p50 and p95 stacked / grouped)
    # -------------------------------------------------------------
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(15, 6))

    components = [
        ("security_ingress", "Security Ingress", "#7E57C2"),
        ("permission_check", "Permission Check", "#FF7043"),
        ("rate_limiter", "Rate Limiter", "#FFCA28"),
        ("schema_validation", "Schema Validation", "#9CCC65"),
        ("policy_evaluation", "Policy Evaluation", "#26A69A"),
        ("audit_lock_wait", "Audit Lock Wait", "#E53935"),
        ("audit_db_write", "Audit DB Write", "#1E88E5"),
        ("audit_commit", "Audit Commit", "#D81B60"),
        ("pii_and_truncation", "PII & Truncation", "#8D6E63")
    ]

    x = np.arange(len(phases))
    width = 0.55

    # Plot p50 breakdown
    bottom_p50 = np.zeros(len(phases))
    for key, name, color in components:
        vals = [summary["phases"][p]["components_ms"][key]["p50"] for p in phases]
        ax1.bar(x, vals, width, bottom=bottom_p50, label=name, color=color, edgecolor="#333", linewidth=0.5)
        bottom_p50 += np.array(vals)

    ax1.set_title("p50 (Median) Component Breakdown by Arrival Rate", fontsize=11, fontweight="bold")
    ax1.set_ylabel("Latency (ms)", fontsize=10)
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels, fontsize=9.5)
    ax1.grid(True, axis="y", linestyle=":", alpha=0.6)
    ax1.legend(loc="upper left", fontsize=8)

    # Plot p95 breakdown
    bottom_p95 = np.zeros(len(phases))
    for key, name, color in components:
        vals = [summary["phases"][p]["components_ms"][key]["p95"] for p in phases]
        ax2.bar(x, vals, width, bottom=bottom_p95, label=name, color=color, edgecolor="#333", linewidth=0.5)
        bottom_p95 += np.array(vals)

    ax2.axhline(30.0, color="#B71C1C", linestyle="--", linewidth=2.0, label="QA2 Threshold (30 ms)")
    ax2.set_title("p95 (Tail) Component Breakdown by Arrival Rate", fontsize=11, fontweight="bold")
    ax2.set_ylabel("Latency (ms)", fontsize=10)
    ax2.set_xticks(x)
    ax2.set_xticklabels(labels, fontsize=9.5)
    ax2.grid(True, axis="y", linestyle=":", alpha=0.6)
    ax2.legend(loc="upper left", fontsize=8)

    plt.tight_layout()
    chart1_path = os.path.join(CHARTS_DIR, "qa2_profile_component_breakdown.png")
    fig.savefig(chart1_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart1_path}")

    # -------------------------------------------------------------
    # Chart 2: Rate Scaling Curve (Overhead vs RPS)
    # -------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(10, 5.5))
    rates = [10.0, 30.0, 60.0, 100.0]
    p50_overhead = [
        summary["phases"]["profile_10rps"]["overhead_ms"]["p50"],
        summary["phases"]["profile_30rps"]["overhead_ms"]["p50"],
        summary["phases"]["profile_60rps"]["overhead_ms"]["p50"],
        summary["phases"]["profile_100rps_win2"]["overhead_ms"]["p50"]
    ]
    p95_overhead = [
        summary["phases"]["profile_10rps"]["overhead_ms"]["p95"],
        summary["phases"]["profile_30rps"]["overhead_ms"]["p95"],
        summary["phases"]["profile_60rps"]["overhead_ms"]["p95"],
        summary["phases"]["profile_100rps_win2"]["overhead_ms"]["p95"]
    ]
    p95_audit_lock = [
        summary["phases"]["profile_10rps"]["components_ms"]["audit_lock_wait"]["p95"],
        summary["phases"]["profile_30rps"]["components_ms"]["audit_lock_wait"]["p95"],
        summary["phases"]["profile_60rps"]["components_ms"]["audit_lock_wait"]["p95"],
        summary["phases"]["profile_100rps_win2"]["components_ms"]["audit_lock_wait"]["p95"]
    ]

    ax.plot(rates, p50_overhead, marker="o", color="#1E88E5", linewidth=2.0, label="Overhead p50 (Median)")
    ax.plot(rates, p95_overhead, marker="s", color="#E53935", linewidth=2.2, label="Overhead p95 (95th percentile)")
    ax.plot(rates, p95_audit_lock, marker="^", color="#8E24AA", linewidth=2.0, linestyle="--", label="Audit Chain Lock Wait p95")
    ax.axhline(30.0, color="#B71C1C", linestyle=":", linewidth=2.0, label="QA2 SLA Limit (30 ms)")

    # Highlight inflection point
    ax.axvspan(30, 60, color="#FFF3E0", alpha=0.5, label="Lock Contention Inflection Window")

    ax.set_title("Gateway Overhead Scaling vs. Arrival Rate (10 to 100 RPS)", fontsize=11, fontweight="bold")
    ax.set_xlabel("Target Arrival Rate (Calls / Second)", fontsize=10)
    ax.set_ylabel("Gateway Added Latency (ms)", fontsize=10)
    ax.set_xticks(rates)
    ax.grid(True, linestyle=":", alpha=0.6)
    ax.legend(loc="upper left", fontsize=9)

    plt.tight_layout()
    chart2_path = os.path.join(CHARTS_DIR, "qa2_profile_rate_scaling.png")
    fig.savefig(chart2_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart2_path}")

    # -------------------------------------------------------------
    # Chart 3: Audit Composition Breakdown across Rates
    # -------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(10, 5.5))

    audit_cats = [
        ("audit_lock_wait", "Lock Wait on audit_chain_state", "#E53935"),
        ("audit_db_write", "SQL Insert & Update", "#1E88E5"),
        ("audit_commit", "Transaction Commit & Flush", "#FB8C00"),
        ("audit_hash_calc", "SHA-256 Hash Computation", "#43A047")
    ]

    bottom = np.zeros(len(phases))
    for key, name, color in audit_cats:
        vals = [summary["phases"][p]["components_ms"][key]["mean"] for p in phases]
        ax.bar(x, vals, width, bottom=bottom, label=name, color=color, edgecolor="#333", linewidth=0.5)
        bottom += np.array(vals)

    ax.set_title("Mean Audit Sub-Operation Breakdown by Arrival Rate", fontsize=11, fontweight="bold")
    ax.set_ylabel("Duration (ms)", fontsize=10)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=9.5)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)
    ax.legend(loc="upper left", fontsize=9)

    plt.tight_layout()
    chart3_path = os.path.join(CHARTS_DIR, "qa2_profile_audit_composition.png")
    fig.savefig(chart3_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart3_path}")

    # -------------------------------------------------------------
    # Chart 4: Stabilization Comparison at 100 RPS
    # -------------------------------------------------------------
    fig, ax = plt.subplots(figsize=(9, 5))
    stab_phases = ["profile_stabilization", "profile_100rps_win1", "profile_100rps_win2"]
    stab_labels = ["Stabilization (60s)", "Window 1 (30s)", "Window 2 (30s)"]

    ov_p50 = [summary["phases"][p]["overhead_ms"]["p50"] for p in stab_phases]
    ov_p95 = [summary["phases"][p]["overhead_ms"]["p95"] for p in stab_phases]
    lock_p95 = [summary["phases"][p]["components_ms"]["audit_lock_wait"]["p95"] for p in stab_phases]

    x_s = np.arange(len(stab_phases))
    w_s = 0.25

    r1 = ax.bar(x_s - w_s, ov_p50, w_s, label="Overhead p50", color="#42A5F5")
    r2 = ax.bar(x_s, ov_p95, w_s, label="Overhead p95", color="#EF5350")
    r3 = ax.bar(x_s + w_s, lock_p95, w_s, label="Audit Lock p95", color="#AB47BC")

    ax.axhline(30.0, color="#B71C1C", linestyle="--", linewidth=1.8, label="QA2 Limit (30 ms)")

    ax.set_title("100 RPS Latency Stabilization Progression across Successive Windows", fontsize=11, fontweight="bold")
    ax.set_ylabel("Duration (ms)", fontsize=10)
    ax.set_xticks(x_s)
    ax.set_xticklabels(stab_labels, fontsize=9.5)
    ax.grid(True, axis="y", linestyle=":", alpha=0.6)
    ax.legend(loc="upper right", fontsize=9)

    def autolabel(rects):
        for rect in rects:
            height = rect.get_height()
            ax.annotate(f"{height:.1f}ms",
                        xy=(rect.get_x() + rect.get_width() / 2, height),
                        xytext=(0, 3),
                        textcoords="offset points",
                        ha="center", va="bottom", fontsize=8.5, fontweight="bold")

    autolabel(r1)
    autolabel(r2)
    autolabel(r3)

    plt.tight_layout()
    chart4_path = os.path.join(CHARTS_DIR, "qa2_profile_stabilization.png")
    fig.savefig(chart4_path, dpi=200)
    plt.close(fig)
    print(f"Generated {chart4_path}")

if __name__ == "__main__":
    generate_profile_charts()
