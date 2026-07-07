#!/usr/bin/env python3
"""
Cross-framework baseline: run **Claude Code** on the SAME SWE-bench-Live subset,
SAME model (deepseek-chat via DeepSeek's Anthropic-compatible endpoint), so the
only variable vs LiCode is the *framework*. Mirrors run_licode.py exactly:
per instance → docker run the image → run the agent headless in /testbed →
`git diff` → predictions.jsonl → (then the official run_evaluation scores it).

Claude Code is a Node CLI. To avoid installing Node/claude-code inside each
container (offline-hostile networks), we MOUNT a host portable Node + a host dir
that already has `@anthropic-ai/claude-code` installed, read-only — same trick as
LiCode's mounted JRE.

────────────────────────────────────────────────────────────────────────────
⚠️  CONFIRM on the 1-instance smoke (Claude Code specifics I can't fully verify):
   1. Auth var: DeepSeek's anthropic endpoint may want ANTHROPIC_API_KEY (x-api-key)
      or ANTHROPIC_AUTH_TOKEN (Bearer). We pass ANTHROPIC_API_KEY; switch if 401.
   2. `--dangerously-skip-permissions` may refuse to run as root. Fallbacks below.
   3. CC_ENTRY path to the CLI (cli.js) — verify it exists in your install.
   4. Endpoint compat: deepseek-chat via anthropic-compat must support tool use.
────────────────────────────────────────────────────────────────────────────

Host prep (once, WHERE THE NETWORK WORKS):
    # portable Node (official builds target old glibc, like Temurin)
    curl -fLO https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-x64.tar.xz
    mkdir -p ~/node20 && tar -xf node-v20.18.1-linux-x64.tar.xz -C ~/node20 --strip-components=1
    # claude-code into a mountable dir
    mkdir -p ~/cc && cd ~/cc && ~/node20/bin/npm init -y \
      && ~/node20/bin/npm install @anthropic-ai/claude-code

Run:
    export ANTHROPIC_API_KEY=sk-<deepseek key>
    export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
    export ANTHROPIC_MODEL=deepseek-chat
    python run_claudecode.py --subset instances.txt --out preds.claudecode.jsonl
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

# ── Model / provider (MUST match LiCode's run for a fair framework comparison) ──
MODEL = os.environ.get("ANTHROPIC_MODEL", "deepseek-chat")
BASE_URL = os.environ.get("ANTHROPIC_BASE_URL", "https://api.deepseek.com/anthropic")
SMALL_MODEL = os.environ.get("ANTHROPIC_SMALL_FAST_MODEL", MODEL)  # keep background off real Anthropic

# ── Host claude-code (native binary) mounted read-only into each container ──
# Claude Code 2.x ships a self-contained native ELF binary (built for an ancient
# glibc baseline, so it runs in the older instance images) — no Node needed.
CC_HOST = os.environ.get("CC_DIR", str(Path.home() / "cc"))   # dir with node_modules/@anthropic-ai/claude-code
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


def load_instances(subset_file: Path) -> list[dict]:
    from datasets import load_dataset
    ds = load_dataset(DATASET_ID, split=DATASET_SPLIT)
    wanted = {ln.strip() for ln in subset_file.read_text().splitlines()
              if ln.strip() and not ln.startswith("#")}
    rows = [dict(r) for r in ds if r["instance_id"] in wanted]
    missing = wanted - {r["instance_id"] for r in rows}
    if missing:
        print(f"[warn] {len(missing)} instance_ids not found: "
              f"{sorted(missing)[:5]}{'...' if len(missing) > 5 else ''}", file=sys.stderr)
    return rows


def run_one(inst: dict, api_key: str, timeout_s: int) -> str:
    iid = inst["instance_id"]
    image = image_for(iid)
    container = f"cc_{iid}".replace("__", "_").replace("/", "_")[:60]

    env_flags = [
        "-e", f"ANTHROPIC_API_KEY={api_key}",
        "-e", f"ANTHROPIC_BASE_URL={BASE_URL}",
        "-e", f"ANTHROPIC_MODEL={MODEL}",
        "-e", f"ANTHROPIC_SMALL_FAST_MODEL={SMALL_MODEL}",
        # Claude Code writes state under $HOME/.claude; give it a writable home.
        "-e", "HOME=/root",
        # Helps some Claude Code versions allow skip-permissions in a sandbox.
        "-e", "IS_SANDBOX=1",
        # Reduce noise / disable update checks & telemetry in CI.
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
        # Clean base_commit, then run Claude Code headless in the repo.
        sh(["docker", "exec", container, "bash", "-lc",
            f"cd {TESTBED} && git checkout -- . && git checkout {inst['base_commit']} 2>/dev/null || true"])
        problem = inst["problem_statement"]
        # node <cli.js> -p "<problem>" --dangerously-skip-permissions
        #   -p = print/non-interactive; skip-permissions = auto-approve tools (BYPASS analog).
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
    ap.add_argument("--subset", default=str(Path(__file__).with_name("instances.txt")))
    ap.add_argument("--out", default="preds.claudecode.jsonl")
    ap.add_argument("--timeout", type=int, default=1800)
    args = ap.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        print("[error] set ANTHROPIC_API_KEY to your DeepSeek key", file=sys.stderr)
        return 1
    host_bin = Path(CC_HOST) / "node_modules" / "@anthropic-ai" / "claude-code" / "bin" / "claude.exe"
    if not host_bin.exists():
        print(f"[error] claude-code binary not found at {host_bin} — see host prep", file=sys.stderr)
        return 1

    model_tag = f"claudecode+{MODEL}"
    instances = load_instances(Path(args.subset))
    print(f"[run] {len(instances)} instances, framework=Claude Code {MODEL}, "
          f"mounting native binary from {CC_HOST}", file=sys.stderr)

    with open(args.out, "w", encoding="utf-8") as fh:
        for inst in instances:
            patch = run_one(inst, api_key, args.timeout)
            fh.write(json.dumps({
                "instance_id": inst["instance_id"],
                "model_name_or_path": model_tag,
                "model_patch": patch,
            }) + "\n")
            fh.flush()
            print(f"[done] {inst['instance_id']} patch={len(patch)} chars", file=sys.stderr)

    print(f"\n[predictions] {args.out}", file=sys.stderr)
    print("Next — score it with the SAME official eval as LiCode:", file=sys.stderr)
    print(f"  python -m swebench.harness.run_evaluation \\\n"
          f"    --dataset_name {DATASET_ID} --split {DATASET_SPLIT} --namespace {NAMESPACE} \\\n"
          f"    --predictions_path {args.out} --run_id {model_tag} --max_workers 1",
          file=sys.stderr)
    print("Then compare vs LiCode (same 20 tasks, paired):", file=sys.stderr)
    print("  python aggregate_ablation.py --baseline <licode.report.json> "
          "--ablation <claudecode.report.json> --out ../../target/framework-compare.json",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
