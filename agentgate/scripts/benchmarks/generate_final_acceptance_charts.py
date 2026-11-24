#!/usr/bin/env python3
"""
AgentGate — M04 Task 16F-E: Final QA2 Acceptance Charts Generator
Reads:
  - scripts/benchmarks/data/qa2_final_acceptance_summary.json
  - scripts/benchmarks/data/qa2_final_acceptance_samples.csv
Generates:
  - docs/quality/performance/charts/qa2_final_acceptance_windows.png
  - docs/quality/performance/charts/qa2_final_latency_cdf.png
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

with open(os.path.join(DATA_DIR, "qa2_final_acceptance_summary.json"), "r", encoding="utf-8") as f:
    summary = json.load(f)

overhead_by_win = {"window_1": [], "window_2": [], "window_3": []}
with open(os.path.join(DATA_DIR, "qa2_final_acceptance_samples.csv"), "r", encoding="utf-8") as f:
    reader = csv.DictReader(f)
    for row in reader:
        w = row.get("assigned_window")
        if w in overhead_by_win and row.get("classification") == "SUCCESSFUL_GOVERNED_EXECUTION":
            overhead_by_win[w].append(float(row["overhead_ms"]))

pooled_overhead = overhead_by_win["window_1"] + overhead_by_win["window_2"] + overhead_by_win["window_3"]

# Figure 1: Windows Comparison
fig, axes = plt.subplots(2, 2, figsize=(16, 12))
fig.suptitle("AgentGate — M04 Task 16F-E: Final QA2 Acceptance Windows (18,000 Calls @ 100 RPS)",
             fontsize=15, fontweight="bold", y=0.98)

# Panel 1: Gateway Overhead p50, p95, p99 across Windows + Pooled
ax1 = axes[0, 0]
labels = ["Window 1\n(6,000 calls)", "Window 2\n(6,000 calls)", "Window 3\n(6,000 calls)", "Pooled\n(18,000 calls)"]
keys = ["window_1", "window_2", "window_3"]

p50s = [summary["windows"][k]["overhead_ms"]["p50"] for k in keys] + [summary["pooled"]["overhead_ms"]["p50"]]
p95s = [summary["windows"][k]["overhead_ms"]["p95"] for k in keys] + [summary["pooled"]["overhead_ms"]["p95"]]
p99s = [summary["windows"][k]["overhead_ms"]["p99"] for k in keys] + [summary["pooled"]["overhead_ms"]["p99"]]

x = np.arange(len(labels))
width = 0.25

ax1.bar(x - width, p50s, width, label="p50 (Median)", color="#4575b4")
r95 = ax1.bar(x, p95s, width, label="p95 (Acceptance Metric)", color="#1a9850")
ax1.bar(x + width, p99s, width, label="p99 (Tail)", color="#fdae61")
ax1.axhline(30.0, color="#d73027", linestyle="--", linewidth=2, label="QA2 Ceiling (30.0 ms)")

for rect in r95:
    h = rect.get_height()
    ax1.text(rect.get_x() + rect.get_width() / 2., h + 0.8, f"{h:.2f}ms", ha="center", va="bottom", fontsize=9, fontweight="bold")

ax1.set_title("Gateway-Added Overhead Percentiles by Acceptance Window", fontsize=11, fontweight="bold")
ax1.set_ylabel("Overhead (ms)", fontsize=10)
ax1.set_xticks(x)
ax1.set_xticklabels(labels, fontsize=9)
ax1.legend(loc="upper right", fontsize=9)
ax1.grid(axis="y", linestyle="--", alpha=0.4)

# Panel 2: Achieved RPS per Window
ax2 = axes[0, 1]
rps_vals = [summary["windows"][k]["accounting"]["achieved_successful_rps"] for k in keys] + [summary["pooled"]["accounting"]["achieved_successful_rps"]]
bars = ax2.bar(x, rps_vals, width=0.5, color="#3288bd")
ax2.axhline(100.0, color="#d73027", linestyle="--", linewidth=2, label="Target Offered Load (100 RPS)")
for bar in bars:
    h = bar.get_height()
    ax2.text(bar.get_x() + bar.get_width() / 2., h + 0.5, f"{h:.2f}", ha="center", va="bottom", fontsize=10, fontweight="bold")
ax2.set_title("Achieved Successful Throughput (RPS)", fontsize=11, fontweight="bold")
ax2.set_ylabel("Requests per Second", fontsize=10)
ax2.set_xticks(x)
ax2.set_xticklabels(labels, fontsize=9)
ax2.set_ylim(0, max(rps_vals + [105]) * 1.1)
ax2.legend(loc="lower right", fontsize=9)
ax2.grid(axis="y", linestyle="--", alpha=0.4)

# Panel 3: Component Mean Breakdown (Pooled)
ax3 = axes[1, 0]
comp = summary["pooled"]["components_ms"]
comp_names = [
    "Auth & JWT", "MVC Routing", "MCP Parse", "Callback Prep",
    "Permission", "Rate Limit", "Schema", "Audit Total", "PII & Ser."
]
comp_means = [
    comp["ingress_auth"]["mean"],
    comp["ingress_routing"]["mean"],
    comp["ingress_mcp_parse"]["mean"],
    comp["ingress_callback_prep"]["mean"],
    comp["permission_check"]["mean"],
    comp["rate_limiter"]["mean"],
    comp["schema_validation"]["mean"],
    comp["audit_total"]["mean"],
    comp["pii_and_truncation"]["mean"] + comp["serialization"]["mean"]
]
y_pos = np.arange(len(comp_names))
ax3.barh(y_pos, comp_means, color="#5e4fa2")
for i, v in enumerate(comp_means):
    ax3.text(v + 0.1, i, f"{v:.2f} ms", va="center", fontsize=9, fontweight="bold")
ax3.set_yticks(y_pos)
ax3.set_yticklabels(comp_names, fontsize=9)
ax3.invert_yaxis()
ax3.set_title("Pooled Mean Latency Breakdown by Component (18,000 Calls)", fontsize=11, fontweight="bold")
ax3.set_xlabel("Mean Duration (ms)", fontsize=10)
ax3.grid(axis="x", linestyle="--", alpha=0.4)

# Panel 4: Summary Text Box
ax4 = axes[1, 1]
ax4.axis("off")
po = summary["pooled"]
pac = po["accounting"]
pov = po["overhead_ms"]
fa = summary["final_audit_verification"]
ia = summary["initial_audit_verification"]

txt = (
    "Task 16F-E Final Acceptance Summary\n"
    "------------------------------------------------------------\n"
    f"Client: SingleBufferHTTPConnection (TCP_NODELAY=1)\n"
    f"Pooled Valid Calls: {pac['successful_executions']} / {pac['planned_requests']}\n"
    f"Pooled Achieved RPS: {pac['achieved_successful_rps']:.2f} RPS\n\n"
    f"Pooled Gateway Overhead:\n"
    f"  - Mean: {pov['mean']:.2f} ms\n"
    f"  - p50:  {pov['p50']:.2f} ms\n"
    f"  - p90:  {pov['p90']:.2f} ms\n"
    f"  - p95:  {pov['p95']:.2f} ms  (Target <= 30.0 ms)\n"
    f"  - p99:  {pov['p99']:.2f} ms\n"
    f"  - Max:  {pov['max']:.2f} ms\n\n"
    f"Audit Chain Verification:\n"
    f"  - Initial Sequence: {ia.get('lastSequence')}\n"
    f"  - Final Sequence:   {fa.get('lastSequence')}\n"
    f"  - Chain Valid:      {fa.get('valid')} (Corrupted: {fa.get('corruptedSequence')})\n"
)
ax4.text(0.02, 0.95, txt, transform=ax4.transAxes, fontsize=10,
         verticalalignment="top", fontfamily="monospace",
         bbox=dict(boxstyle="round,pad=0.6", facecolor="#f8f9fa", edgecolor="#6c757d"))

plt.tight_layout(rect=[0, 0, 1, 0.95])
plt.savefig(os.path.join(CHARTS_DIR, "qa2_final_acceptance_windows.png"), dpi=200)
plt.close()

# Figure 2: Latency CDF
fig2, ax = plt.subplots(figsize=(12, 7))
for k, label, col in [
    ("window_1", "Window 1 (6,000 calls)", "#3288bd"),
    ("window_2", "Window 2 (6,000 calls)", "#abdda4"),
    ("window_3", "Window 3 (6,000 calls)", "#fdae61")
]:
    arr = np.sort(overhead_by_win[k])
    y = np.arange(1, len(arr) + 1) / len(arr) * 100.0
    ax.plot(arr, y, label=label, linewidth=1.8, color=col)

arr_p = np.sort(pooled_overhead)
y_p = np.arange(1, len(arr_p) + 1) / len(arr_p) * 100.0
ax.plot(arr_p, y_p, label="Pooled (18,000 calls)", linewidth=2.5, color="#1a1a1a", linestyle="-")

ax.axvline(30.0, color="#d73027", linestyle="--", linewidth=2, label="QA2 Ceiling (30.0 ms)")
ax.axhline(95.0, color="#666666", linestyle=":", linewidth=1.5, label="95th Percentile (p95)")

p95_pooled = summary["pooled"]["overhead_ms"]["p95"]
ax.scatter([p95_pooled], [95.0], color="#d73027", zorder=5, s=80)
ax.annotate(f"Pooled p95 = {p95_pooled:.2f} ms", xy=(p95_pooled, 95.0),
            xytext=(p95_pooled + 3.0, 88.0), fontsize=11, fontweight="bold",
            arrowprops=dict(arrowstyle="->", color="#d73027"))

ax.set_title("AgentGate — Final QA2 Acceptance Gateway Overhead CDF (18,000 Governed Calls)", fontsize=14, fontweight="bold")
ax.set_xlabel("Gateway-Added Overhead (ms)", fontsize=11)
ax.set_ylabel("Cumulative Percentage (%)", fontsize=11)
ax.set_xlim(0, max(45.0, p95_pooled * 1.5))
ax.set_ylim(0, 101)
ax.legend(loc="lower right", fontsize=10)
ax.grid(True, linestyle="--", alpha=0.4)

plt.tight_layout()
plt.savefig(os.path.join(CHARTS_DIR, "qa2_final_latency_cdf.png"), dpi=200)
plt.close()
print("Charts generated successfully.")
