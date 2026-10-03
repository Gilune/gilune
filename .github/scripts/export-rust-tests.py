"""Export Cargo's actual test executables, preserving their crate working dirs."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def export(messages, repo, output):
    records = [json.loads(line) for line in messages.read_text().splitlines() if line]
    if not any(r.get("reason") == "build-finished" and r.get("success") for r in records):
        raise ValueError("Cargo did not report a successful build")
    tests = []
    cli = None
    output.mkdir(parents=True, exist_ok=True)
    names = set()
    for row in records:
        if row.get("reason") != "compiler-artifact" or not row.get("executable"):
            continue
        source = Path(row["executable"])
        if row["profile"]["test"]:
            directory = Path(row["manifest_path"]).parent.resolve().relative_to(repo)
            tests.append({"file": source.name, "crate": directory.as_posix()})
        elif row["target"]["name"] == "gilune-cli":
            cli = source.name
        else:
            continue
        if source.suffix != ".exe" or source.name in names:
            raise ValueError("Invalid or duplicate Windows executable: " + str(source))
        names.add(source.name)
        shutil.copy2(source, output / source.name)
    if not tests or not cli:
        raise ValueError("Cargo output must include tests and the integration-test CLI")
    manifest = {"tests": tests, "cli": cli, "sha256": {
        name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in sorted(names)}}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(tests)} test executables and the CLI")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--messages", type=Path, required=True)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.messages, args.repo.resolve(), args.output)
