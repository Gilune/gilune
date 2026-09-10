"""Exercise routing against real Git refs without network or third-party modules."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from build_route import build_route, validation_mode, main


class BuildRouteTest(unittest.TestCase):
    def setUp(self):
        # Git object files are read-only on Windows: TemporaryDirectory's
        # cleanup handles that without changing files outside this directory.
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "--quiet")
        self.git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty",
                 "-m", "First commit")
        self.sha = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.repo, check=True,
            capture_output=True, text=True,
        ).stdout.strip()

    def route(self, event="push", ref="refs/heads/main", sha=None):
        return build_route(event, ref, sha or self.sha, self.repo)[0]

    def test_untagged_main_builds(self):
        self.assertTrue(self.route())

    def test_only_tags_and_manual_runs_request_full_validation(self):
        for event, ref, expected in (
            ("push", "refs/heads/main", "quick"),
            ("push", "refs/heads/ci/reliability-review", "quick"),
            ("push", "refs/tags/v1.0.7", "full"),
            ("workflow_dispatch", "refs/heads/main", "full"),
            ("workflow_dispatch", "refs/heads/ci/reliability-review", "full"),
        ):
            with self.subTest(event=event, ref=ref):
                self.assertEqual(expected, validation_mode(event, ref, self.sha, self.repo)[0])

    def test_tagged_branch_skips_both_validation_modes(self):
        self.git("tag", "v1.0.7")
        self.assertEqual("skip", validation_mode("push", "refs/heads/main", self.sha, self.repo)[0])

    def test_lightweight_release_tag_suppresses_branch_build(self):
        self.git("tag", "v1.0.7")
        self.assertFalse(self.route())

    def test_annotated_release_tag_suppresses_branch_build(self):
        self.git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "tag", "-a", "v1.0.7", "-m", "Release")
        self.assertFalse(self.route())

    def test_ci_branch_also_defers_to_release_tag(self):
        self.git("tag", "v1.0.7")
        self.assertFalse(self.route(ref="refs/heads/ci/builds"))

    def test_unusable_and_unrelated_tags_do_not_suppress_branch_build(self):
        for tag in ("v0.9.0", "v1.0.7-rc1", "v1.0", "v1.0.7.1", "v01.0.7", "backup"):
            self.git("tag", tag)
        self.assertTrue(self.route())

    def test_tag_on_other_commit_does_not_suppress_build(self):
        self.git("tag", "v1.0.6")
        self.git("-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty",
                 "-m", "Next commit")
        self.assertTrue(self.route(sha=self.git("rev-parse", "HEAD")))
        # A queued run still checks its original SHA after the branch moves.
        self.assertFalse(self.route())

    def test_release_tag_always_builds(self):
        self.git("tag", "v1.0.7")
        self.assertTrue(self.route(ref="refs/tags/v1.0.7"))

    def test_manual_run_can_rebuild_tagged_commit(self):
        self.git("tag", "v1.0.7")
        self.assertTrue(self.route(event="workflow_dispatch"))

    def test_missing_commit_fails_instead_of_skipping(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.route(sha="0" * 40)

    def test_cli_writes_workflow_output_and_summary(self):
        output = self.repo / "output"
        summary = self.repo / "summary"
        with patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": self.sha, "GITHUB_OUTPUT": str(output),
            "GITHUB_STEP_SUMMARY": str(summary),
        }):
            main()
        self.assertEqual("build=true\nfull=true\n", output.read_text(encoding="utf-8"))
        self.assertIn("manual run", summary.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
