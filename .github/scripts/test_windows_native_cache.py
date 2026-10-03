"""Exercise recipe invalidation across cold, restored and changed native builds."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("build-windows-linux.sh").resolve()
BASH = ("C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash"))


@unittest.skipUnless(BASH, "bash is needed for the Windows cross-build script")
class WindowsNativeCacheTest(unittest.TestCase):
    def test_restored_dependencies_survive_but_recipe_changes_invalidate_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / ".github/scripts/build-windows-linux.sh"
            script.parent.mkdir(parents=True)
            shutil.copyfile(SCRIPT, script)
            (root / "kernel").mkdir()
            binary = root / "bin"
            binary.mkdir()

            def stub(name, content):
                path = binary / name
                path.write_text("#!/bin/bash\nset -eu\n" + content, encoding="utf-8")
                path.chmod(0o755)

            # Exercise the real shell orchestration without a compiler/SDK.
            stub("uname", "echo Linux\n")
            stub("python3", 'echo export >> "$BUILD_REVIEW_CALLS.tests"\n')
            stub("cargo", '''
if [[ $1 == clean ]]; then
  echo clean >> "$BUILD_REVIEW_CALLS"
  exit 0
fi
if [[ $1 == xwin && $2 == build ]]; then
  if [[ ${BUILD_REVIEW_FAIL_BUILD:-0} == 1 ]]; then exit 17; fi
  dest="$CARGO_TARGET_DIR/x86_64-pc-windows-msvc/release"
  mkdir -p "$dest"
  echo library > "$dest/gilune_ffi.dll"
elif [[ $1 == xwin && $2 == test ]]; then
  echo compile >> "$BUILD_REVIEW_CALLS.tests"
elif [[ $1 == run ]]; then
  while [[ $1 != --out-dir ]]; do shift; done
  mkdir -p "$2/uniffi/gilune_ffi"
  echo bindings > "$2/uniffi/gilune_ffi/gilune_ffi.kt"
fi
''')
            target = root / "app/build/windows-cross-target"
            calls = root / "calls"
            bash_env = root / "bash-env"
            # Git Bash prepends its own utilities while importing Windows PATH.
            bash_env.write_text('export PATH="$(cd "$BUILD_REVIEW_BIN" && pwd):$PATH"\n', encoding="utf-8")
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                       BASH_ENV=bash_env.as_posix(), BUILD_REVIEW_BIN=binary.as_posix(),
                       GILUNE_WINDOWS_BUILD_ROOT=target.as_posix(),
                       GILUNE_WINDOWS_OUTPUT=(root / "app/build/windows-cross/native").as_posix(),
                       BUILD_REVIEW_CALLS=calls.as_posix())

            def run(expected=0, **overrides):
                result = subprocess.run([BASH, str(script)], cwd=root,
                                        env=dict(env, **overrides),
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)

            run()
            self.assertEqual(["clean"], calls.read_text().splitlines())
            self.assertFalse(Path(str(calls) + ".tests").exists())
            self.assertTrue((root / "app/build/windows-cross/native/gilune_ffi.dll").is_file())
            self.assertTrue((root / "app/build/windows-cross/native/bindings/uniffi/gilune_ffi/gilune_ffi.kt").is_file())
            # rust-cache removes loose target-root files before saving. Model
            # that cleanup, then restore/build with the unchanged recipe.
            for path in target.iterdir():
                if path.is_file():
                    path.unlink()
            run()
            self.assertEqual(["clean"], calls.read_text().splitlines())
            run(GILUNE_WINDOWS_BUILD_TESTS="1")
            self.assertEqual(["compile", "export"], Path(str(calls) + ".tests").read_text().splitlines())
            run(2, GILUNE_WINDOWS_BUILD_TESTS="invalid")

            # A changed recipe must still invalidate the C++ dependency. A
            # failed build must not mark the new recipe as successfully built.
            with script.open("a", encoding="utf-8") as output:
                output.write("\n# Changed native compiler recipe.\n")
            run(17, BUILD_REVIEW_FAIL_BUILD="1")
            run()
            self.assertEqual(["clean", "clean", "clean"], calls.read_text().splitlines())
            run()
            self.assertEqual(["clean", "clean", "clean"], calls.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
