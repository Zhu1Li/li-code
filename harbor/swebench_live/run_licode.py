#!/usr/bin/env python3
"""
SWE-bench-Live prediction generator for LiCode (Track A of the eval plan).

For each instance in a fixed subset, this:
  1. runs the instance's official Docker image (repo pre-checked-out at base_commit
     under /testbed, test env installed),
  2. installs a JRE + the LiCode fat jar into that container,
  3. runs LiCode headless (`--print "<problem_statement>"`) in /testbed,
  4. captures `git -C /testbed diff` as the `model_patch`,
  5. writes one JSONL line {instance_id, model_name_or_path, model_patch}.

Then run the OFFICIAL SWE-bench-Live evaluation harness on the produced
predictions file (this script prints the exact command at the end).

────────────────────────────────────────────────────────────────────────────
⚠️  CONFIRM these against https://swe-bench-live.github.io/ and the project's
    GitHub repo before the first real run — they are the SWE-bench-Live-specific
    bits I could not verify (WebFetch was unavailable). Everything else follows
    the standard SWE-bench schema, which SWE-bench-Live inherits verbatim.
────────────────────────────────────────────────────────────────────────────
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

# Default to the HF mirror when HF_ENDPOINT is unset (huggingface.co is often
# unreachable in some regions). setdefault respects a user-provided value.
# Must run before `datasets` is imported — datasets is imported lazily inside
# load_instances(), so setting it here at module load is early enough.
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

# Dataset id + split (confirmed against microsoft/SWE-bench-Live python-only branch).
# Python dataset: SWE-bench-Live/SWE-bench-Live. Splits: lite/verified (frozen,
# reproducible+cheap — use for the paired ablation), full, test (monthly +50).
DATASET_ID = os.environ.get("SWEBL_DATASET", "SWE-bench-Live/SWE-bench-Live")
DATASET_SPLIT = os.environ.get("SWEBL_SPLIT", "lite")
# DockerHub namespace hosting the per-instance images.
NAMESPACE = os.environ.get("SWEBL_NAMESPACE", "starryzhang")


# Per-instance image name — matches SWE-bench-Live get_default_image_name():
#   starryzhang/sweb.eval.x86_64.<name>   where name = instance_id "__"->"_1776_" lowercased
def image_for(instance_id: str) -> str:
    name = instance_id.replace("__", "_1776_").lower()
    override = os.environ.get("SWEBL_IMAGE_PATTERN")
    if override:
        return override.format(name=name, instance_id=instance_id)
    return f"{NAMESPACE}/sweb.eval.x86_64.{name}"

# Repo checkout path inside the image (SWE-bench convention).
TESTBED = "/testbed"

# LiCode side (matches harbor/harbor_licode/agent.py conventions).
LICODE_JAR_HOST = os.environ.get("LICODE_JAR",
                                 str(Path(__file__).resolve().parents[2] / "target" / "li-code-1.0-SNAPSHOT.jar"))
LICODE_JAR_CONT = "/opt/licode/licode.jar"


# Reuse the HOST's Java (mounted read-only into each container) instead of
# apt-installing a JRE per container — robust on networks where the container
# can't reach distro package repos. Auto-detected from `java` on PATH; override
# with LICODE_JRE (a JAVA_HOME whose bin/java is a linux-x64 executable).
# Host WSL is linux-x64, matching the linux/amd64 instance images.
def _detect_jre() -> str:
    override = os.environ.get("LICODE_JRE")
    if override:
        return override
    import shutil
    j = shutil.which("java")
    return str(Path(j).resolve().parent.parent) if j else ""

JRE_HOST = _detect_jre()
MODEL_NAME = os.environ.get("LICODE_MODEL", "claude-opus-4-8")
PROTOCOL = os.environ.get("LICODE_PROTOCOL", "anthropic")
BASE_URL = os.environ.get("LICODE_BASE_URL", "https://api.anthropic.com")
# LICODE_ABLATE (e.g. "compaction") is forwarded into the container unchanged.
ABLATE = os.environ.get("LICODE_ABLATE", "")


def sh(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, **kw)


def load_instances(subset_file: Path) -> list[dict]:
    from datasets import load_dataset  # lazy import
    ds = load_dataset(DATASET_ID, split=DATASET_SPLIT)
    wanted = {ln.strip() for ln in subset_file.read_text().splitlines()
              if ln.strip() and not ln.startswith("#")}
    rows = [dict(r) for r in ds if r["instance_id"] in wanted]
    missing = wanted - {r["instance_id"] for r in rows}
    if missing:
        print(f"[warn] {len(missing)} instance_ids not found in dataset: "
              f"{sorted(missing)[:5]}{'...' if len(missing) > 5 else ''}", file=sys.stderr)
    return rows


def run_one(inst: dict, api_key: str, timeout_s: int) -> str:
    """Run LiCode in the instance container and return the git diff (model_patch)."""
    iid = inst["instance_id"]
    image = image_for(iid)
    container = f"licode_{iid}".replace("__", "_").replace("/", "_")[:60]

    # Provider config; api_key omitted -> LiCode reads it from container env.
    cfg = (f"providers:\n  - name: default\n    protocol: {PROTOCOL}\n"
           f"    base_url: {BASE_URL}\n    model: {MODEL_NAME}\n")

    env_flags = [
        "-e", f"ANTHROPIC_API_KEY={api_key}",
        "-e", f"OPENAI_API_KEY={api_key}",
    ]
    if ABLATE:
        env_flags += ["-e", f"LICODE_ABLATE={ABLATE}"]

    # Mount host Java + the LiCode jar read-only (no apt, no docker cp — works on
    # networks where the container can't reach distro package repos).
    mounts = ["-v", f"{JRE_HOST}:/opt/jre:ro",
              "-v", f"{LICODE_JAR_HOST}:{LICODE_JAR_CONT}:ro"]
    sh(["docker", "rm", "-f", container])
    up = sh(["docker", "run", "-d", "--name", container, *env_flags, *mounts,
             image, "sleep", "infinity"])
    if up.returncode != 0:
        print(f"[{iid}] docker run failed: {up.stderr.strip()}", file=sys.stderr)
        return ""

    try:
        # 1) provider config (no network needed)
        sh(["docker", "exec", container, "bash", "-lc",
            f"mkdir -p ~/.licode && cat > ~/.licode/config.yaml <<'EOF'\n{cfg}EOF"])

        # 2) ensure clean base_commit, then run LiCode headless in the repo
        sh(["docker", "exec", container, "bash", "-lc",
            f"cd {TESTBED} && git checkout -- . && git checkout {inst['base_commit']} 2>/dev/null || true"])
        problem = inst["problem_statement"]
        run = sh(["docker", "exec", "-w", TESTBED, container,
                  "/opt/jre/bin/java", "-jar", LICODE_JAR_CONT, "--print", problem],
                 timeout=timeout_s)
        # LiCode logs tool activity to stderr; final answer to stdout (ignored — we score the diff).
        if run.stderr:
            print(f"[{iid}] " + run.stderr.strip().splitlines()[-1], file=sys.stderr)

        # 3) capture the patch (matches SWE-bench-Live's "collect patch diff" snippet:
        #    git --no-pager diff HEAD --text, handling a possibly-nested .git dir)
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
    ap.add_argument("--out", default="predictions.jsonl")
    ap.add_argument("--timeout", type=int, default=1800, help="per-instance seconds")
    args = ap.parse_args()

    api_key = os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("OPENAI_API_KEY", "")
    if not api_key:
        print("[error] set ANTHROPIC_API_KEY or OPENAI_API_KEY", file=sys.stderr)
        return 1
    if not Path(LICODE_JAR_HOST).exists():
        print(f"[error] jar not found: {LICODE_JAR_HOST} (run `mvn clean package -DskipTests`)",
              file=sys.stderr)
        return 1
    if not JRE_HOST or not (Path(JRE_HOST) / "bin" / "java").exists():
        print(f"[error] host JRE not found (JRE_HOST={JRE_HOST!r}). Install Java 21 or set "
              f"LICODE_JRE to a JAVA_HOME dir whose bin/java is linux-x64.", file=sys.stderr)
        return 1
    print(f"[run] mounting host JRE {JRE_HOST} + jar into each container", file=sys.stderr)

    model_tag = MODEL_NAME + (f"+ablate={ABLATE}" if ABLATE else "")
    instances = load_instances(Path(args.subset))
    print(f"[run] {len(instances)} instances, model={model_tag}", file=sys.stderr)

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
    print("Next — run the OFFICIAL SWE-bench-Live evaluation (python-only branch, `pip install -e .`):",
          file=sys.stderr)
    print(f"  python -m swebench.harness.run_evaluation \\\n"
          f"    --dataset_name {DATASET_ID} --split {DATASET_SPLIT} --namespace {NAMESPACE} \\\n"
          f"    --predictions_path {args.out} --run_id licode-{model_tag} --max_workers 4",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
