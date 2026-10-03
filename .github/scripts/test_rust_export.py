import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("rust_export", Path(__file__).with_name("export-rust-tests.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class RustExportTest(unittest.TestCase):
    def test_exports_all_tests_and_cli_with_relocatable_crate_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            crate = root / "kernel/crates/example"
            crate.mkdir(parents=True)
            rows = []
            for name, test in (("unit-abc", True), ("integration-def", True), ("gilune-cli", False)):
                path = root / (name + ".exe")
                path.write_bytes(name.encode())
                rows.append({"reason": "compiler-artifact", "executable": str(path),
                             "manifest_path": str(crate / "Cargo.toml"),
                             "profile": {"test": test}, "target": {"name": name}})
            rows.append({"reason": "build-finished", "success": True})
            messages = root / "messages.jsonl"
            messages.write_text("\n".join(map(json.dumps, rows)))
            output = root / "export"
            exporter.export(messages, root, output)
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual(2, len(manifest["tests"]))
            self.assertEqual({"kernel/crates/example"}, {t["crate"] for t in manifest["tests"]})
            self.assertEqual("gilune-cli.exe", manifest["cli"])
            self.assertEqual(3, len(manifest["sha256"]))
            # Failed builds cannot replace a previously valid manifest.
            before = (output / "manifest.json").read_bytes()
            messages.write_text(json.dumps({"reason": "build-finished", "success": False}))
            with self.assertRaises(ValueError):
                exporter.export(messages, root, output)
            self.assertEqual(before, (output / "manifest.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
