#!/usr/bin/env python3
"""
Post-hoc difficulty stratification of an already-run SWE-bench-Live eval.

No new agent runs, no cost: it reads the gold `patch` of each evaluated instance
from the dataset, uses **files-changed in the gold patch** as a difficulty proxy,
buckets the instances (simple / medium / complex), and reports resolved% per
bucket — so you can see whether the gap is on complex tasks (à la SWE-agent /
MewCode style analysis).

Usage:
  python stratify_difficulty.py --baseline <baseline.report.json> \
      [--ablation <ablation.report.json>] [--out target/difficulty-strata.json]
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path

# HF mirror default (huggingface.co often unreachable in some regions).
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

DATASET_ID = os.environ.get("SWEBL_DATASET", "SWE-bench-Live/SWE-bench-Live")
DATASET_SPLIT = os.environ.get("SWEBL_SPLIT", "lite")

# Difficulty buckets by number of files touched in the GOLD fix patch.
def bucket_for(n_files: int) -> str:
    if n_files <= 1:
        return "simple (1 file)"
    if n_files <= 5:
        return "medium (2-5 files)"
    return "complex (>5 files)"

BUCKET_ORDER = ["simple (1 file)", "medium (2-5 files)", "complex (>5 files)"]


def _difficulty_label(raw) -> str:
    """Safely extract a difficulty label from the dataset field (str | dict | None).
    Returns '' (→ fall back to file-count bucketing) when no clean string label exists."""
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw.strip()
    if isinstance(raw, dict):
        for k in ("difficulty", "label", "category", "level", "rating", "class",
                  "bucket", "estimate", "time", "value"):
            v = raw.get(k)
            if isinstance(v, str) and v.strip():
                return v.strip()
        return ""
    return str(raw).strip()


def order_difficulty(values):
    """Sort SWE-bench difficulty labels ascending (time-to-fix buckets); unknowns last."""
    def key(v: str):
        vl = v.lower()
        if "15 min" in vl and "<" in vl:  return (0, vl)   # <15 min
        if "15 min" in vl:                return (1, vl)   # 15 min - 1 hour
        if "1-4" in vl or "1 - 4" in vl:  return (2, vl)   # 1-4 hours
        if ">4" in vl or "4 hour" in vl:  return (3, vl)   # >4 hours
        return (8, vl)
    return sorted(values, key=key)


def load_resolved(report_path: str) -> set[str]:
    data = json.loads(Path(report_path).read_text(encoding="utf-8"))
    for key in ("resolved_ids", "resolved", "resolved_instances"):
        if isinstance(data.get(key), list):
            return set(data[key])
    return {k for k, v in data.items() if isinstance(v, dict) and v.get("resolved")}


def load_submitted(report_path: str) -> list[str] | None:
    data = json.loads(Path(report_path).read_text(encoding="utf-8"))
    v = data.get("submitted_ids")
    return list(v) if isinstance(v, list) else None


def files_changed(patch: str) -> int:
    if not patch:
        return 0
    n = len(re.findall(r"^diff --git ", patch, flags=re.M))
    if n:
        return n
    # Fallback: count unique +++ b/<file> targets
    return len({l for l in patch.splitlines() if l.startswith("+++ ")})


def lines_changed(patch: str) -> int:
    if not patch:
        return 0
    add = sum(1 for l in patch.splitlines() if l.startswith("+") and not l.startswith("+++"))
    rem = sum(1 for l in patch.splitlines() if l.startswith("-") and not l.startswith("---"))
    return add + rem


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="baseline eval report json")
    ap.add_argument("--ablation", default=None, help="optional ablation eval report json")
    ap.add_argument("--subset", default=str(Path(__file__).with_name("instances.txt")))
    ap.add_argument("--tertile", action="store_true",
                    help="equal-count buckets (rank by files then lines, split into 3) "
                         "instead of fixed file thresholds — balances bucket sizes for small N")
    ap.add_argument("--files", action="store_true",
                    help="force fixed file-count thresholds even if the dataset has an "
                         "official `difficulty` field (default prefers the official field)")
    ap.add_argument("--out", default="target/difficulty-strata.json")
    args = ap.parse_args()

    universe = load_submitted(args.baseline)
    if not universe:
        universe = [ln.strip() for ln in Path(args.subset).read_text().splitlines()
                    if ln.strip() and not ln.startswith("#")]
    universe = set(universe)
    base_resolved = load_resolved(args.baseline)
    abl_resolved = load_resolved(args.ablation) if args.ablation else None

    from datasets import load_dataset  # lazy
    ds = load_dataset(DATASET_ID, split=DATASET_SPLIT)
    meta = {}
    for r in ds:
        if r["instance_id"] in universe:
            patch = r.get("patch") or ""
            # SWE-bench-Live `difficulty` = STRUCTURAL size of the gold fix:
            # {files, hunks, lines}. Prefer the dataset's numbers; recompute as fallback.
            d = r.get("difficulty") if isinstance(r.get("difficulty"), dict) else {}
            meta[r["instance_id"]] = {
                "files": d.get("files", files_changed(patch)),
                "hunks": d.get("hunks"),
                "lines": d.get("lines", lines_changed(patch)),
            }
    missing = universe - set(meta)
    if missing:
        print(f"[warn] {len(missing)} instance(s) not found in {DATASET_ID}:{DATASET_SPLIT} "
              f"— excluded: {sorted(missing)}")

    # Per-instance rows
    rows = []
    for iid in sorted(universe & set(meta)):
        m = meta[iid]
        rows.append({
            "instance_id": iid,
            "files_changed": m["files"],
            "hunks": m["hunks"],
            "lines_changed": m["lines"],
            "bucket": bucket_for(m["files"]),
            "baseline_resolved": iid in base_resolved,
            **({"ablation_resolved": iid in abl_resolved} if abl_resolved is not None else {}),
        })

    # Bucketing mode. SWE-bench-Live's `difficulty` is STRUCTURAL (files/hunks/lines
    # of the gold fix), NOT a human/semantic rating — so we bucket by structural size.
    #   default = equal-count tertiles by hunks (then lines): the dataset's own axis
    #   --files = fixed file-count thresholds (1 / 2-5 / >5)
    def _score(r):
        h = r["hunks"] if r["hunks"] is not None else r["files_changed"]
        return (h, r["lines_changed"], r["files_changed"])
    if args.files:
        order = BUCKET_ORDER
        mode_desc = "files-changed thresholds"
    else:
        ranked = sorted(rows, key=_score)
        labels = ["low (smallest fixes)", "mid", "high (largest fixes)"]
        n = len(ranked)
        cut1, cut2 = n // 3, (2 * n) // 3
        for i, r in enumerate(ranked):
            r["bucket"] = labels[0] if i < cut1 else labels[1] if i < cut2 else labels[2]
        order = labels
        mode_desc = "SWE-bench-Live structural difficulty (files/hunks/lines), tertiles by hunks"

    # Per-bucket aggregation
    buckets = {}
    for b in order:
        brows = [r for r in rows if r["bucket"] == b]
        n = len(brows)
        if n == 0:
            continue
        bk = {"n": n,
              "baseline_resolved": sum(r["baseline_resolved"] for r in brows),
              "baseline_pct": round(100 * sum(r["baseline_resolved"] for r in brows) / n, 1)}
        if abl_resolved is not None:
            bk["ablation_resolved"] = sum(r["ablation_resolved"] for r in brows)
            bk["ablation_pct"] = round(100 * sum(r["ablation_resolved"] for r in brows) / n, 1)
        buckets[b] = bk

    # ── Print ──
    print(f"\n═══ 难度分层 ({mode_desc}, N={len(rows)}) ═══")
    hdr = f"{'bucket':<20} {'n':>3} {'baseline':>12}"
    if abl_resolved is not None:
        hdr += f" {'ablation':>12}"
    print(hdr)
    print("-" * len(hdr))
    for b in order:
        if b not in buckets:
            continue
        bk = buckets[b]
        line = f"{b:<20} {bk['n']:>3} {bk['baseline_resolved']}/{bk['n']} ({bk['baseline_pct']:>4}%)"
        if abl_resolved is not None:
            line += f"   {bk['ablation_resolved']}/{bk['n']} ({bk['ablation_pct']:>4}%)"
        print(line)

    print("\nper-instance:")
    for r in sorted(rows, key=lambda x: (x["files_changed"], x["instance_id"])):
        mark = "✓" if r["baseline_resolved"] else "✗"
        extra = ""
        if abl_resolved is not None:
            extra = f"  abl:{'✓' if r['ablation_resolved'] else '✗'}"
        hk = r["hunks"] if r["hunks"] is not None else "?"
        print(f"  {mark} {r['instance_id']:<34} files={r['files_changed']:>2} "
              f"hunks={str(hk):>3} lines={r['lines_changed']:>4}{extra}")

    out = {"artifact_type": "difficulty-strata",
           "bucketing": mode_desc,
           "n": len(rows), "buckets": buckets, "instances": rows}
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[artifact] {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
