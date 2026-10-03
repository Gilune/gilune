"""Keep hosted builds on Linux and exercise the version passed to all packages."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


WORKFLOWS = Path(__file__).resolve().parents[1] / "workflows"
BASH = ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))


class LinuxWorkflowTest(unittest.TestCase):
    def test_every_hosted_runner_is_linux(self):
        for workflow in WORKFLOWS.glob("*.yml"):
            with self.subTest(workflow=workflow.name):
                runners = re.findall(r"^\s+runs-on:\s*(.+)$", workflow.read_text(encoding="utf-8"), re.MULTILINE)
                self.assertTrue(runners, "Expected at least one explicit hosted runner")
                for runner in runners:
                    self.assertRegex(runner.strip("'\""), r"^ubuntu-[a-z0-9.-]+$")

    @unittest.skipUnless(BASH, "bash is needed for workflow version decisions")
    def test_actual_version_step_handles_manual_defaults_branches_and_tags(self):
        workflow = (WORKFLOWS / "build.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Decide the version\n", 1)[1]
        match = re.search(r"^        run: \|\n((?: {10}.*\n|[ \t]*\n)+)", step, re.MULTILINE)
        self.assertIsNotNone(match)
        script = textwrap.dedent(match[1])
        for event, ref, requested, expected in (
            ("workflow_dispatch", "refs/heads/ci/review", "", "1.0.0"),
            ("workflow_dispatch", "refs/heads/ci/review", "1.0.7", "1.0.7"),
            ("push", "refs/heads/main", "", ""),
            ("push", "refs/tags/v1.0.8", "", "1.0.8"),
            ("workflow_dispatch", "refs/heads/ci/review", "not-a-version", None),
        ):
            with self.subTest(event=event, ref=ref, requested=requested), tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / "outputs"
                env = dict(os.environ, GITHUB_EVENT_NAME=event, REF=ref, REQUESTED=requested,
                           RUN_NUMBER="123", GITHUB_OUTPUT=output.as_posix())
                result = subprocess.run([BASH, "-c", script], env=env,
                                        capture_output=True, text=True, timeout=15)
                if expected is None:
                    self.assertNotEqual(0, result.returncode)
                    continue
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                properties = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual(expected, properties["version"])
                self.assertEqual("true" if ref.startswith("refs/tags/") else "false",
                                 properties["is_release"])
                self.assertEqual(f"-Pgilune.version={expected} -Pgilune.versionCode=123" if expected else "",
                                 properties["gradle_args"])


if __name__ == "__main__":
    unittest.main()
