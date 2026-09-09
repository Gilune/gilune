import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("test-android.sh").resolve()
BASH = ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))


@unittest.skipUnless(BASH, "bash is needed for the Android workflow script")
class AndroidShardTest(unittest.TestCase):
    def test_failed_shard_stays_failed_and_all_reports_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "bin"
            binary.mkdir()
            adb = binary / "adb"
            adb.write_text("#!/bin/bash\nprintf 'device diagnostics\\n'\n", encoding="utf-8")
            adb.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text('''#!/bin/bash
for arg in "$@"; do
  case "$arg" in -Pandroid.testInstrumentationRunnerArguments.shardIndex=*) shard=${arg##*=};; esac
done
echo "$shard" >> invocations
mkdir -p composeApp/build/outputs/androidTest-results/connected composeApp/build/reports/androidTests/connected
echo "$shard" > composeApp/build/outputs/androidTest-results/connected/result.xml
echo "$shard" > composeApp/build/reports/androidTests/connected/result.html
if [ "$shard" = 1 ]; then exit 1; fi
''', encoding="utf-8")
            gradle.chmod(0o755)
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"])
            result = subprocess.run([BASH, str(SCRIPT)], cwd=root, env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertEqual(["0", "1", "2"], (root / "invocations").read_text().splitlines())
            for shard in range(3):
                output = root / "build/android-shards" / f"shard-{shard}"
                self.assertEqual(str(shard), (output / "androidTest-results/result.xml").read_text().strip())
                self.assertEqual(str(shard), (output / "androidTests/result.html").read_text().strip())
                self.assertIn("device diagnostics", (output / "device.log").read_text())


if __name__ == "__main__":
    unittest.main()
