#!/usr/bin/env python3
"""
Generates empirical comparison charts between:
- Baseline (Task 16E, HikariCP Pool Size = 10)
- Optimized (Task 16F-B, HikariCP Pool Size = 30)
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

with open(os.path.join(DATA_DIR, "qa2_16e_profile_summary.json")) as f:
    base = json.load(f)

with open(os.path.join(DATA_DIR, "qa2_pool30_profile_summary.json")) as f:
    pool30 = json.load(f)

plt.style.use("seaborn-v0_8-whitegrid" if "seaborn-v0_8-whitegrid" in plt.style.available else "default")
fig, axes = plt.subplots(1, 2, figsize=(16, 6))

# --- Chart 1: Component Tail Latency (p95) Comparison at 100 RPS Window 2 ---
ax1 = axes[0]
b_comp = base["phases"]["profile_100rps_win2"]["components_ms"]
p_comp = pool30["phases"]["profile_100rps_win2"]["components_ms"]

components = [
    ("Audit Lock Wait", "audit_lock_wait"),
    ("Permission Check", "permission_check"),
    ("Audit Commit", "audit_commit"),
    ("Audit DB Write", "audit_db_write"),
    ("Audit Total", "audit_total"),
    ("Downstream Adapter", "downstream_adapter"),
    ("Rate Limiter", "rate_limiter"),
    ("Security Ingress", "security_ingress"),
    ("Total Overhead", None)
]

labels = [c[0] for c in components]
base_vals = []
pool30_vals = []

for label, key in components:
    if key is None:
        base_vals.append(base["phases"]["profile_100rps_win2"]["overhead_ms"]["p95"])
        pool30_vals.append(pool30["phases"]["profile_100rps_win2"]["overhead_ms"]["p95"])
    else:
        base_vals.append(b_comp[key]["p95"])
        pool30_vals.append(p_comp[key]["p95"])

y = np.arange(len(labels))
height = 0.35

rects1 = ax1.barh(y - height/2, base_vals, height, label="Pool Size = 10 (Task 16E)", color="#d9534f", alpha=0.85)
rects2 = ax1.barh(y + height/2, pool30_vals, height, label="Pool Size = 30 (Task 16F-B)", color="#2ca02c", alpha=0.85)

ax1.axvline(30.0, color="#d9534f", linestyle="--", linewidth=1.5, label="QA2 SLA Limit (30 ms)")
ax1.set_xlabel("Tail Latency p95 (ms)", fontsize=11, fontweight="bold")
ax1.set_title("Component Tail Latency p95 at 100 RPS: Pool 10 vs. Pool 30", fontsize=12, fontweight="bold")
ax1.set_yticks(y)
ax1.set_yticklabels(labels, fontsize=10)
ax1.legend(loc="lower right", fontsize=10)
ax1.set_xlim(0, max(base_vals) * 1.08)

# Add text labels on bars
for bar in rects1:
    w = bar.get_width()
    if w > 5:
        ax1.text(w + 3, bar.get_y() + bar.get_height()/2, f"{w:.1f}ms", va="center", ha="left", fontsize=8, color="#a94442")

for bar in rects2:
    w = bar.get_width()
    ax1.text(w + 3, bar.get_y() + bar.get_height()/2, f"{w:.1f}ms", va="center", ha="left", fontsize=8, color="#2e6da4", fontweight="bold")

# --- Chart 2: Gateway Overhead p95 Scaling across Arrival Rates ---
ax2 = axes[1]
phases = ["profile_10rps", "profile_30rps", "profile_60rps", "profile_100rps_win1", "profile_100rps_win2"]
x_labels = ["10 RPS", "30 RPS", "60 RPS", "100 RPS (W1)", "100 RPS (W2)"]

b_p95 = [base["phases"][p]["overhead_ms"]["p95"] for p in phases]
p_p95 = [pool30["phases"][p]["overhead_ms"]["p95"] for p in phases]
b_p50 = [base["phases"][p]["overhead_ms"]["p50"] for p in phases]
p_p50 = [pool30["phases"][p]["overhead_ms"]["p50"] for p in phases]

x = np.arange(len(x_labels))
ax2.plot(x, b_p95, "o-", color="#d9534f", linewidth=2.5, markersize=8, label="Pool 10: Overhead p95")
ax2.plot(x, p_p95, "s-", color="#2ca02c", linewidth=2.5, markersize=8, label="Pool 30: Overhead p95")
ax2.plot(x, b_p50, "o--", color="#f0ad4e", linewidth=1.5, markersize=6, alpha=0.7, label="Pool 10: Overhead p50")
ax2.plot(x, p_p50, "s--", color="#1f77b4", linewidth=1.5, markersize=6, alpha=0.7, label="Pool 30: Overhead p50")

ax2.axhline(30.0, color="#d9534f", linestyle="--", linewidth=1.5, label="QA2 Ceiling (30 ms)")
ax2.set_xticks(x)
ax2.set_xticklabels(x_labels, fontsize=10, fontweight="bold")
ax2.set_ylabel("Gateway Added Latency (ms)", fontsize=11, fontweight="bold")
ax2.set_title("Gateway Overhead Scaling: Pool 10 vs. Pool 30", fontsize=12, fontweight="bold")
ax2.legend(loc="upper left", fontsize=10)
ax2.grid(True, linestyle="--", alpha=0.6)

plt.tight_layout()
out_path = os.path.join(CHARTS_DIR, "qa2_pool_comparison.png")
plt.savefig(out_path, dpi=180)
print(f"Comparison chart generated: {out_path}")
