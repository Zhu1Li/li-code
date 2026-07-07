#!/usr/bin/env python3
"""
Re-run only failed instances (0-char patches) from an existing predictions file,
splice the results back in. Use when most instances ran fine but a few failed
due to transient network errors.

Usage:
  python rerun_failed.py --preds preds.claudecode.jsonl [--timeout 1800]
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

DATASET_ID = os.environ.get("SWEBL_DATASET", "SWE-bench-Live/SWE-bench-Live")
DATASET_SPLIT = os.environ.get("SWEBL_SPLIT", "lite")
NAMESPACE = os.environ.get("SWEBL_NAMESPACE", "starryzhang")
TESTBED = "/testbed"

MODEL = os.environ.get("ANTHROPIC_MODEL", "deepseek-chat")
BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")
SMALL_MODEL = os.environ.get("ANTHROPIC_SMALL_FAST_MODEL", MODEL)

CC_HOST = os.environ.get("CC_DIR", str(Path.home() / "cc"))
CC_BIN = os.environ.get("CC_BIN",
                        "/opt/cc/node_modules/@anthropic-ai/claude-code/bin/claude.exe")


def sh(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, **kw)


def image_for(instance_id: str) -> str:
    name = instance_id.replace("__", "_1776_").lower()
    override = os.environ.get("SWEBL_IMAGE_PATTERN")
    if override:
        return override.format(name=name, instance_id=instance_id)
    return f"{NAMESPACE}/sweb.eval.x86_64.{name}"


def load_dataset_instance(instance_id: str) -> dict | None:
    from datasets import load_dataset
    ds = load_dataset(DATASET_ID, split=DATASET_SPLIT)
    for r in ds:
        if r["instance_id"] == instance_id:
            return dict(r)
    return None


def run_one(inst: dict, api_key: str, timeout_s: int) -> str:
    iid = inst["instance_id"]
    image = image_for(iid)
    container = f"cc_{iid}".replace("__", "_").replace("/", "_")[:60]

    env_flags = [
        "-e", f"ANTHROPIC_API_KEY={api_key}",
        "-e", f"ANTHROPIC_BASE_URL={BASE_URL}",
        "-e", f"ANTHROPIC_MODEL={MODEL}",
        "-e", f"ANTHROPIC_SMALL_FAST_MODEL={SMALL_MODEL}",
        "-e", "HOME=/root",
        "-e", "IS_SANDBOX=1",
        "-e", "CI=1",
        "-e", "DISABLE_AUTOUPDATER=1",
        "-e", "DISABLE_TELEMETRY=1",
    ]
    mounts = ["-v", f"{CC_HOST}:/opt/cc:ro"]

    sh(["docker", "rm", "-f", container])
    up = sh(["docker", "run", "-d", "--name", container, *env_flags, *mounts,
             image, "sleep", "infinity"])
    if up.returncode != 0:
        print(f"[{iid}] docker run failed: {up.stderr.strip()}", file=sys.stderr)
        return ""

    try:
        sh(["docker", "exec", container, "bash", "-lc",
            f"cd {TESTBED} && git checkout -- . && git checkout {inst['base_commit']} 2>/dev/null || true"])
        problem = inst["problem_statement"]
        run = sh(["docker", "exec", "-w", TESTBED, container,
                  CC_BIN, "-p", problem, "--dangerously-skip-permissions"],
                 timeout=timeout_s)
        if run.stderr:
            last = run.stderr.strip().splitlines()[-1:] or [""]
            print(f"[{iid}] {last[0]}", file=sys.stderr)
        if run.returncode != 0 and not run.stdout:
            print(f"[{iid}] claude exit={run.returncode} (see stderr)", file=sys.stderr)

        diff = sh(["docker", "exec", container, "bash", "-lc",
                   f"cd {TESTBED}; "
                   "[ -d .git ] || { g=$(find . -maxdepth 2 -mindepth 2 -type d -name .git -print -quit); "
                   "[ -n \"$g\" ] && cd \"${g%/.git}\"; }; "
                   "git --no-pager diff HEAD --text"])
        return diff.stdout
    except subprocess.TimeoutExpired:
        print(f"[{iid}] timed out after {timeout_s}s", file=sys.stderr)
        return ""
    finally:
        sh(["docker", "rm", "-f", container])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", required=True, help="existing predictions file")
    ap.add_argument("--timeout", type=int, default=1800)
    args = ap.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        print("[error] set ANTHROPIC_API_KEY", file=sys.stderr)
        return 1

    preds_path = Path(args.preds)
    if not preds_path.exists():
        print(f"[error] {preds_path} not found", file=sys.stderr)
        return 1

    # Read all entries, find failed ones
    lines = preds_path.read_text(encoding="utf-8").strip().splitlines()
    entries = [json.loads(l) for l in lines if l.strip()]
    failed = [e for e in entries if not e["model_patch"].strip()]
    ok = [e for e in entries if e["model_patch"].strip()]

    print(f"[scan] {len(entries)} total, {len(ok)} ok, {len(failed)} failed")
    if not failed:
        print("[done] nothing to re-run")
        return 0

    print(f"[rerun] re-running {len(failed)} failed instance(s):")
    for e in failed:
        print(f"  - {e['instance_id']}")

    model_tag = ok[0]["model_name_or_path"] if ok else f"claudecode+{MODEL}"

    for e in failed:
        iid = e["instance_id"]
        print(f"\n[{iid}] re-running...", file=sys.stderr)
        inst = load_dataset_instance(iid)
        if inst is None:
            print(f"[{iid}] SKIP: not found in dataset", file=sys.stderr)
            continue
        patch = run_one(inst, api_key, args.timeout)
        print(f"[{iid}] new patch={len(patch)} chars", file=sys.stderr)
        e["model_patch"] = patch

    # Write back (all in original order)
    all_entries = ok + failed
    with open(preds_path, "w", encoding="utf-8") as fh:
        for e in entries:  # preserve original order
            # Find matching entry (may have been updated)
            match = next(x for x in all_entries if x["instance_id"] == e["instance_id"])
            fh.write(json.dumps(match) + "\n")

    final_failed = sum(1 for e in all_entries if not e["model_patch"].strip())
    print(f"\n[done] {len(all_entries)} total, {final_failed} still failed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
