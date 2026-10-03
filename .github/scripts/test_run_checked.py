import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("run_checked.py").resolve()


class CheckedRunTest(unittest.TestCase):
    def test_preserves_command_failure_and_output(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(SCRIPT), "--output", directory,
                                     "--", sys.executable, "-c", "print('original failure'); exit(7)"],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(7, result.returncode)
            self.assertIn("original failure", (Path(directory) / "command.log").read_text())

    def test_deadline_captures_and_stops_the_owned_process_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            # The unrelated process must survive the watchdog's cleanup.
            unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
            try:
                code = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); time.sleep(60)"
                result = subprocess.run([sys.executable, str(SCRIPT), "--timeout", "1",
                                         "--output", directory, "--", sys.executable, "-c", code],
                                        capture_output=True, text=True, timeout=35)
                self.assertEqual(124, result.returncode, result.stdout + result.stderr)
                processes = json.loads((Path(directory) / "processes.json").read_text())
                self.assertGreaterEqual(len(processes), 2)
                self.assertNotIn(unrelated.pid, [pid for pid, _ in processes])
                self.assertIsNone(unrelated.poll())
                # Completion also verifies the child released the inherited pipe.
            finally:
                unrelated.terminate()
                unrelated.wait(timeout=10)


if __name__ == "__main__":
    unittest.main()
