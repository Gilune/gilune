"""Run the complete exported Windows kernel suite without a compiler on Windows."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("Run these artifacts on Windows")
    artifacts = args.artifacts.resolve()
    repo = args.repo.resolve()
    manifest = json.loads((artifacts / "manifest.json").read_text())
    if not manifest["tests"]:
        raise ValueError("Empty test manifest")
    if manifest["cli"] not in manifest["sha256"]:
        raise ValueError("Integration-test CLI is absent from the verified artifacts")
    for name, expected in manifest["sha256"].items():
        path = (artifacts / name).resolve()
        if path.parent != artifacts or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Invalid test artifact: " + name)
    env = dict(os.environ, GILUNE_TEST_CLI=str(artifacts / manifest["cli"]),
               GILUNE_TEST_REPOSITORY=str(repo), GILUNE_CI="1")
    args.results.mkdir(parents=True, exist_ok=True)
    results = []
    for test in manifest["tests"]:
        binary = (artifacts / test["file"]).resolve()
        crate = (repo / test["crate"]).resolve()
        if binary.name not in manifest["sha256"] or binary.parent != artifacts or repo not in crate.parents:
            raise ValueError("Invalid test manifest path")
        print(f"Running {test['crate']}: {binary.name}", flush=True)
        # Reuse the owned-process watchdog; hanging child CLIs must not survive.
        code = subprocess.call([
            sys.executable, str(Path(__file__).with_name("run_checked.py").resolve()),
            "--timeout", "1200", "--output", str((args.results / binary.stem).resolve()),
            "--", str(binary)], cwd=crate, env=env)
        results.append({"test": binary.name, "exitCode": code})
        if code:
            print(f"FAILED: {binary.name} (exit {code})", flush=True)
    (args.results / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    return int(any(r["exitCode"] for r in results))


if __name__ == "__main__":
    sys.exit(main())
