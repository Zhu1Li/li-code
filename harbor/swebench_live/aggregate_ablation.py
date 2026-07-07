#!/usr/bin/env python3
"""
Track B aggregation: compare two SWE-bench-Live evaluation reports produced over
the SAME instance subset (baseline = compaction ON, ablation = LICODE_ABLATE=compaction)
and run a PAIRED McNemar test on the resolved/unresolved outcomes.

Input reports are the JSON summaries emitted by the SWE-bench-Live evaluation
harness (each contains, at minimum, the list of resolved instance ids). We only
require a `resolved_ids` list per report — the loader below tolerates the common
SWE-bench field names.

Usage:
  python aggregate_ablation.py --baseline base.report.json --ablation abl.report.json \
      [--baseline-traj dir] [--ablation-traj dir] --out target/ablation-swebench.json
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
from pathlib import Path


def load_resolved(report_path: str) -> set[str]:
    data = json.loads(Path(report_path).read_text(encoding="utf-8"))
    for key in ("resolved_ids", "resolved", "resolved_instances"):
        if isinstance(data.get(key), list):
            return set(data[key])
    # Fallback: per-instance map {id: {"resolved": bool}}
    out = set()
    for k, v in data.items():
        if isinstance(v, dict) and v.get("resolved"):
            out.add(k)
    if not out:
        raise SystemExit(f"[error] could not find resolved ids in {report_path} "
                         f"(keys: {list(data)[:8]}) — adjust load_resolved()")
    return out


def mcnemar_exact_p(b: int, c: int) -> float:
    """Two-sided exact McNemar (binomial) p-value on discordant counts b, c."""
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    # P(X <= k) under Binomial(n, 0.5), doubled, clamped to 1.
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def trajectory_totals(traj_dir: str | None) -> dict:
    """Sum tokens / turns / tool-calls across all *.jsonl trajectories in a dir.
    LiCode session lines carry per-message content; usage is logged separately, so
    this is a coarse proxy (chars/turns/tool-calls). Extend if you persist usage."""
    if not traj_dir:
        return {}
    turns = tool_calls = chars = files = 0
    for fp in glob.glob(os.path.join(traj_dir, "**", "*.jsonl"), recursive=True):
        files += 1
        try:
            for line in Path(fp).read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                msg = json.loads(line)
                if msg.get("role") == "assistant":
                    turns += 1
                for tu in msg.get("toolUses") or []:
                    tool_calls += 1
                if msg.get("content"):
                    chars += len(msg["content"])
        except (OSError, json.JSONDecodeError):
            continue
    return {"trajectory_files": files, "assistant_turns": turns,
            "tool_calls": tool_calls, "content_chars": chars}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="baseline (compaction ON) report json")
    ap.add_argument("--ablation", required=True, help="ablation (compaction OFF) report json")
    ap.add_argument("--subset", default=str(Path(__file__).with_name("instances.txt")))
    ap.add_argument("--baseline-traj", default=None)
    ap.add_argument("--ablation-traj", default=None)
    ap.add_argument("--out", default="target/ablation-swebench.json")
    args = ap.parse_args()

    subset = {ln.strip() for ln in Path(args.subset).read_text().splitlines()
              if ln.strip() and not ln.startswith("#")}
    base = load_resolved(args.baseline) & subset
    abl = load_resolved(args.ablation) & subset
    n = len(subset)

    both = len(base & abl)
    base_only = len(base - abl)   # baseline solved, ablation didn't  (b)
    abl_only = len(abl - base)    # ablation solved, baseline didn't  (c)
    neither = n - both - base_only - abl_only

    p = mcnemar_exact_p(base_only, abl_only)

    result = {
        "artifact_type": "ablation-swebench",
        "variable": "context-compaction (L1+L2)",
        "subset_size": n,
        "baseline_resolved": len(base),
        "ablation_resolved": len(abl),
        "baseline_resolved_pct": round(100 * len(base) / n, 1) if n else 0,
        "ablation_resolved_pct": round(100 * len(abl) / n, 1) if n else 0,
        "delta_pct_points": round(100 * (len(base) - len(abl)) / n, 1) if n else 0,
        "contingency": {"both": both, "baseline_only": base_only,
                        "ablation_only": abl_only, "neither": neither},
        "mcnemar_exact_p": round(p, 4),
        "baseline_cost": trajectory_totals(args.baseline_traj),
        "ablation_cost": trajectory_totals(args.ablation_traj),
    }

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2), encoding="utf-8")

    print("═══ context-compaction ablation (paired, N=%d) ═══" % n)
    print(f"  baseline (ON):  {result['baseline_resolved']}/{n} = {result['baseline_resolved_pct']}%")
    print(f"  ablation (OFF): {result['ablation_resolved']}/{n} = {result['ablation_resolved_pct']}%")
    print(f"  Δ = {result['delta_pct_points']:+.1f} pts")
    print(f"  discordant: baseline-only={base_only}, ablation-only={abl_only}  "
          f"→ McNemar exact p = {p:.4f}")
    for tag in ("baseline_cost", "ablation_cost"):
        c = result[tag]
        if c:
            print(f"  {tag}: turns={c['assistant_turns']} tools={c['tool_calls']} "
                  f"chars={c['content_chars']:,}")
    print(f"\n[artifact] {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
