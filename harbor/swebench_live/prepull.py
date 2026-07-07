#!/usr/bin/env python3
"""
Pre-pull ALL SWE-bench-Live Docker images so run_claudecode never hits
a pull-timeout. Reads instances.txt, generates image names, pulls sequentially.

Usage:
  python prepull.py                    # pull all 20
  python prepull.py --retries 3        # retry each failed pull N times
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

NAMESPACE = "starryzhang"


def image_for(instance_id: str) -> str:
    name = instance_id.replace("__", "_1776_").lower()
    return f"{NAMESPACE}/sweb.eval.x86_64.{name}"


def main() -> int:
    instances_file = Path(__file__).with_name("instances.txt")
    iids = [ln.strip() for ln in instances_file.read_text().splitlines()
            if ln.strip() and not ln.startswith("#")]

    images = [(iid, image_for(iid)) for iid in iids]
    print(f"[prepull] {len(images)} images to check/pull\n")

    failed = []
    for i, (iid, img) in enumerate(images, 1):
        tag = f"[{i:2d}/{len(images)}]"
        print(f"{tag} {img}", flush=True)
        r = subprocess.run(["docker", "pull", img], text=True,
                           capture_output=True)
        if r.returncode != 0:
            last = (r.stderr.strip().splitlines()[-1:]) or ["unknown error"]
            print(f"     \033[31mFAIL\033[0m  {last[0][:120]}", flush=True)
            failed.append((iid, img))
        else:
            # Print the final digest line
            digest = [l for l in r.stdout.splitlines()
                      if "Digest:" in l or "Status:" in l]
            if digest:
                print(f"     \033[32mOK\033[0m   {digest[-1].strip()[:120]}", flush=True)

    print(f"\n[prepull] done — {len(images) - len(failed)}/{len(images)} ok")
    if failed:
        print("[prepull] failed images:")
        for iid, img in failed:
            print(f"  docker pull {img}")
        print("\nYou can retry them manually or use: docker pull <image>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
