#!/usr/bin/env python3
"""
AgentGate — QA2 Pre-Pipeline Latency Decomposition Chart Generator (Task 16F-C)
Reads qa2_ingress_summary.json and qa2_ingress_samples.csv to generate:
docs/quality/performance/charts/qa2_ingress_decomposition.png
Uses only standard library (json, csv) + matplotlib + numpy.
"""

import csv
import json
import os
import matplotlib.pyplot as plt
import numpy as np

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA_DIR = os.path.join(ROOT_DIR, "scripts", "benchmarks", "data")
CHARTS_DIR = os.path.join(ROOT_DIR, "docs", "quality", "performance", "charts")
os.makedirs(CHARTS_DIR, exist_ok=True)

summary_path = os.path.join(DATA_DIR, "qa2_ingress_summary.json")
samples_path = os.path.join(DATA_DIR, "qa2_ingress_samples.csv")

with open(summary_path, "r", encoding="utf-8") as f:
    summary = json.load(f)

# Load Window 2 samples using standard csv module
w2_samples = {
    "sec_ingress_ms": [],
    "ingress_auth_ms": [],
    "ingress_routing_ms": [],
    "ingress_mcp_parse_ms": [],
    "ingress_callback_prep_ms": []
}

with open(samples_path, "r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
        if row.get("phase") == "profile_100rps_win2" and row.get("classification") == "SUCCESSFUL_GOVERNED_EXECUTION":
            for col in w2_samples.keys():
                val = row.get(col)
                if val:
                    w2_samples[col].append(float(val))

fig, axes = plt.subplots(2, 2, figsize=(16, 12))
fig.suptitle("AgentGate — Pre-Pipeline Latency Decomposition (Task 16F-C Forensic Telemetry)", fontsize=16, fontweight="bold", y=0.98)

# Panel 1: Stacked Bar Chart of Ingress Components across Workload Phases (p95)
ax1 = axes[0, 0]
phases = ["profile_10rps", "profile_30rps", "profile_60rps", "profile_100rps_win1", "profile_100rps_win2"]
labels = ["10 RPS", "30 RPS", "60 RPS", "100 RPS (W1)", "100 RPS (W2)"]

auth_p95 = [summary["phases"][p]["components_ms"]["ingress_auth"]["p95"] for p in phases]
route_p95 = [summary["phases"][p]["components_ms"]["ingress_routing"]["p95"] for p in phases]
mcp_p95 = [summary["phases"][p]["components_ms"]["ingress_mcp_parse"]["p95"] for p in phases]
prep_p95 = [summary["phases"][p]["components_ms"]["ingress_callback_prep"]["p95"] for p in phases]

x = np.arange(len(phases))
width = 0.55

p_auth = ax1.bar(x, auth_p95, width, label="Auth & JWT (T0->T1)", color="#4575b4")
p_route = ax1.bar(x, route_p95, width, bottom=auth_p95, label="MVC Routing (T1->T2)", color="#74add1")
p_mcp = ax1.bar(x, mcp_p95, width, bottom=np.array(auth_p95) + np.array(route_p95), label="MCP Parsing & Transport (T2->T3)", color="#d73027")
p_prep = ax1.bar(x, prep_p95, width, bottom=np.array(auth_p95) + np.array(route_p95) + np.array(mcp_p95), label="Callback Prep (T3->T4)", color="#fdae61")

for i in range(len(phases)):
    total = auth_p95[i] + route_p95[i] + mcp_p95[i] + prep_p95[i]
    mcp_pct = (mcp_p95[i] / total) * 100 if total > 0 else 0
    ax1.text(x[i], total + 1.0, f"{total:.1f}ms\n({mcp_pct:.0f}% MCP)", ha="center", va="bottom", fontsize=10, fontweight="bold")

ax1.set_title("Pre-Pipeline Ingress Decomposition at p95 by Arrival Rate", fontsize=12, fontweight="bold")
ax1.set_ylabel("Latency (ms)", fontsize=11)
ax1.set_xticks(x)
ax1.set_xticklabels(labels, fontsize=10)
ax1.set_ylim(0, 65)
ax1.legend(loc="upper right", fontsize=9)
ax1.grid(axis="y", linestyle="--", alpha=0.5)

# Panel 2: Pie Chart of Window 2 (100 RPS) Pre-Pipeline Latency Breakdown (p95)
ax2 = axes[0, 1]
w2_components = [
    summary["phases"]["profile_100rps_win2"]["components_ms"]["ingress_auth"]["p95"],
    summary["phases"]["profile_100rps_win2"]["components_ms"]["ingress_routing"]["p95"],
    summary["phases"]["profile_100rps_win2"]["components_ms"]["ingress_mcp_parse"]["p95"],
    summary["phases"]["profile_100rps_win2"]["components_ms"]["ingress_callback_prep"]["p95"]
]
pie_labels = [
    f"Auth & JWT\n({w2_components[0]:.2f}ms)",
    f"MVC Routing\n({w2_components[1]:.2f}ms)",
    f"MCP Transport & Parse\n({w2_components[2]:.2f}ms)",
    f"Callback Prep\n({w2_components[3]:.2f}ms)"
]
colors = ["#4575b4", "#74add1", "#d73027", "#fdae61"]
explode = (0.05, 0.05, 0.08, 0.05)

wedges, texts, autotexts = ax2.pie(
    w2_components, labels=pie_labels, autopct="%1.1f%%",
    startangle=140, colors=colors, explode=explode,
    textprops=dict(color="black", fontsize=10),
    pctdistance=0.65
)
for at in autotexts:
    at.set_fontweight("bold")
    at.set_fontsize(11)

ax2.set_title("Window 2 (100 RPS) Ingress Share at p95 (Total Ingress: 48.93ms)", fontsize=12, fontweight="bold")

# Panel 3: Cumulative Distribution Function (CDF) of Ingress Components in Window 2
ax3 = axes[1, 0]
cdf_configs = [
    ("sec_ingress_ms", "Total Pre-Pipeline Ingress (T0->T4)", "#000000"),
    ("ingress_mcp_parse_ms", "MCP Parsing & Transport (T2->T3)", "#d73027"),
    ("ingress_auth_ms", "Auth & JWT Verification (T0->T1)", "#4575b4"),
    ("ingress_routing_ms", "MVC Dispatch & Routing (T1->T2)", "#74add1"),
    ("ingress_callback_prep_ms", "Callback Prep & Parse (T3->T4)", "#fdae61")
]

for col, label, color in cdf_configs:
    vals = w2_samples[col]
    if vals:
        data = np.sort(vals)
        cdf = np.arange(1, len(data) + 1) / len(data)
        ax3.plot(data, cdf, label=label, color=color, linewidth=2.2 if "Total" in label or "MCP" in label else 1.5)

ax3.axvline(30.0, color="green", linestyle="--", alpha=0.8, label="QA2 Latency Ceiling (30ms)")
ax3.set_title("Window 2 (100 RPS) Latency CDF by Component", fontsize=12, fontweight="bold")
ax3.set_xlabel("Latency (ms)", fontsize=11)
ax3.set_ylabel("Cumulative Probability", fontsize=11)
ax3.set_xlim(0, 70)
ax3.set_ylim(0, 1.05)
ax3.legend(loc="lower right", fontsize=9)
ax3.grid(True, linestyle="--", alpha=0.5)

# Panel 4: Per-Request Residual Analysis (Overhead vs Ingress)
ax4 = axes[1, 1]
overhead_p95 = [summary["phases"][p]["overhead_ms"]["p95"] for p in phases]
ingress_p95 = [summary["phases"][p]["components_ms"]["security_ingress"]["p95"] for p in phases]
non_ingress_p95 = [max(0, o - i) for o, i in zip(overhead_p95, ingress_p95)]

x_idx = np.arange(len(phases))
b_ing = ax4.bar(x_idx - 0.15, ingress_p95, 0.3, label="Pre-Pipeline Ingress p95", color="#f46d43")
b_non = ax4.bar(x_idx + 0.15, non_ingress_p95, 0.3, label="Governance Pipeline p95 (Post-Ingress)", color="#66c2a5")

ax4.axhline(30.0, color="green", linestyle="--", alpha=0.8, label="QA2 Target (30ms)")
for i in range(len(phases)):
    ax4.text(x_idx[i] - 0.15, ingress_p95[i] + 1.0, f"{ingress_p95[i]:.1f}ms", ha="center", va="bottom", fontsize=9)
    ax4.text(x_idx[i] + 0.15, non_ingress_p95[i] + 1.0, f"{non_ingress_p95[i]:.1f}ms", ha="center", va="bottom", fontsize=9)

ax4.set_title("Pre-Pipeline Ingress vs Governance Pipeline (Post-Ingress)", fontsize=12, fontweight="bold")
ax4.set_ylabel("Latency (ms)", fontsize=11)
ax4.set_xticks(x_idx)
ax4.set_xticklabels(labels, fontsize=10)
ax4.legend(loc="upper right", fontsize=9)
ax4.grid(axis="y", linestyle="--", alpha=0.5)

plt.tight_layout(rect=[0, 0, 1, 0.96])
output_png = os.path.join(CHARTS_DIR, "qa2_ingress_decomposition.png")
plt.savefig(output_png, dpi=300)
print(f"Ingress decomposition chart saved to {output_png}")
