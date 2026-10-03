"""Let a release tag own the expensive jobs for a simultaneously pushed branch."""

import os
from pathlib import Path
import re
import subprocess


# Keep in step with build.yml's installer-version validation. An unusable tag
# must not suppress branch coverage when its own version job will fail.
RELEASE_TAG = re.compile(r"v[1-9][0-9]*\.[0-9]+\.[0-9]+")


def build_route(event, ref, sha, repository="."):
    if event != "push" or not ref.startswith("refs/heads/"):
        return True, "Release-tag or manual run: build all packages on Linux and run Linux/Android suites."

    # Query the event SHA, never the current branch tip (it may have moved).
    # --points-at resolves annotated as well as lightweight tags. A failed
    # query raises instead of silently reporting a successful skipped build.
    # git tag alone accepts an unknown full SHA and returns an empty list.
    subprocess.run(
        ["git", "rev-parse", "--verify", f"{sha}^{{commit}}"], cwd=repository,
        check=True, capture_output=True, text=True,
    )
    tags = subprocess.run(
        ["git", "tag", "--points-at", sha], cwd=repository,
        check=True, capture_output=True, text=True,
    ).stdout.splitlines()
    releases = sorted(tag for tag in tags if RELEASE_TAG.fullmatch(tag))
    if releases:
        return False, f"Release tag(s) {', '.join(releases)} own this commit's build."
    return True, "Untagged branch change: Linux kernel, lint and desktop tests only; no packages."


def validation_mode(event, ref, sha, repository="."):
    build, reason = build_route(event, ref, sha, repository)
    if not build:
        return "skip", reason
    full = event == "workflow_dispatch" or ref.startswith("refs/tags/")
    return ("full" if full else "quick"), reason


def main():
    mode, reason = validation_mode(
        os.environ["GITHUB_EVENT_NAME"], os.environ["GITHUB_REF"],
        os.environ["GITHUB_SHA"],
    )
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        output.write(f"build={str(mode != 'skip').lower()}\n")
        output.write(f"full={str(mode == 'full').lower()}\n")
    print(reason)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write(f"{reason}\n")


if __name__ == "__main__":
    main()
