import importlib.util
import csv
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("windows_package", Path(__file__).with_name("package-windows-linux.py"))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)
NS = {"w": package.WIX}


class WindowsPackageTest(unittest.TestCase):
    def test_rejects_versions_that_windows_installer_cannot_order(self):
        for value in ("0.1.0", "1.0", "1.0.1-rc1", "256.0.0", "1.256.0", "1.0.65536"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                package.version(value)
        self.assertEqual("255.255.65535", package.version("255.255.65535"))

    def test_component_identity_survives_version_changes_and_added_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            (bundle / "lib").mkdir(parents=True)
            for name in ("program.jar", "gilune.ico", "lib/name & unicode-é.jar"):
                (bundle / name).write_text("fixture")
            def components(release):
                path = root / (release + ".wxs")
                package.write_wix(bundle, path, release)
                tree = ET.parse(path)
                result = {}
                for component in tree.findall(".//w:Component", NS):
                    file = component.find("w:File", NS)
                    if file is not None:
                        result[file.attrib["Source"]] = component.attrib["Guid"]
                return result, tree
            first, _ = components("1.0.7")
            (bundle / "lib/new.jar").write_text("new")
            second, tree = components("1.0.8")
            self.assertTrue(first.items() <= second.items())
            self.assertEqual(4, len(second))
            self.assertEqual(len(second), len(set(second.values())))
            self.assertEqual(package.UPGRADE_CODE, tree.find("w:Product", NS).attrib["UpgradeCode"])
            self.assertIsNotNone(tree.find(".//w:MajorUpgrade", NS))

    def test_launcher_resource_carries_the_installer_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "launcher.rc"
            package.write_launcher(root / "space & unicode-é", path, "1.0.7")
            content = path.read_text(encoding="utf-8")
            self.assertIn("FILEVERSION 1,0,7,0", content)
            self.assertIn('VALUE "ProductVersion", "1.0.7"', content)
            self.assertIn("space & unicode-é", content)


MSI_TOOLS = all(shutil.which(tool) for tool in ("llvm-rc-19", "lld-link-19", "wixl", "msiinfo"))
MSI_TOOLS = MSI_TOOLS and importlib.util.find_spec("pefile") is not None


@unittest.skipUnless(MSI_TOOLS, "Linux MSI regression needs LLVM 19, wixl, msitools and pefile")
class WindowsFileVersionTest(unittest.TestCase):
    def test_msi_preserves_real_pe_versions_and_only_hashes_unversioned_files(self):
        import pefile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "payload with spaces"
            bundle.mkdir()
            (bundle / "gilune.ico").write_bytes(b"test icon stream")
            (bundle / "readme.txt").write_text("unversioned data", encoding="utf-8")

            def binary(name, numbers=None):
                resource = root / (name + ".rc")
                content = "1 RCDATA { 1, 2, 3 }\n"
                if numbers:
                    content = ("1 VERSIONINFO\nFILEVERSION " + numbers +
                               "\nPRODUCTVERSION 99,0,0,0\nBEGIN\nEND\n")
                resource.write_text(content, encoding="utf-8")
                compiled = root / (name + ".res")
                subprocess.run(["llvm-rc-19", "/fo", str(compiled), str(resource)], check=True)
                subprocess.run(["lld-link-19", "/dll", "/noentry", "/machine:x64",
                                "/out:" + str(bundle / name), str(compiled)], check=True)

            binary("Gilune.exe", "1,0,7,0")
            binary("runtime.dll", "17,0,20,101")
            binary("unversioned.dll")
            self.assertEqual("1.0.7.0", package.file_version(bundle / "Gilune.exe"))
            self.assertEqual("17.0.20.101", package.file_version(bundle / "runtime.dll"))
            self.assertIsNone(package.file_version(bundle / "unversioned.dll"))
            self.assertIsNone(package.file_version(bundle / "readme.txt"))
            (bundle / "invalid.dll").write_bytes(b"not a PE file")
            with self.assertRaises(pefile.PEFormatError):
                package.file_version(bundle / "invalid.dll")
            (bundle / "invalid.dll").unlink()

            source = root / "installer.wxs"
            package.write_wix(bundle, source, "1.0.7")
            installer = root / "candidate.msi"
            subprocess.run(["wixl", "-a", "x64", "-o", str(installer), str(source)], check=True)

            def table(name):
                content = subprocess.check_output(["msiinfo", "export", str(installer), name], text=True)
                rows = list(csv.reader(io.StringIO(content), delimiter="\t"))
                return [dict(zip(rows[0], row)) for row in rows[3:]]

            files = {row["FileName"]: row for row in table("File")}
            self.assertEqual("1.0.7.0", files["Gilune.exe"]["Version"])
            self.assertEqual("17.0.20.101", files["runtime.dll"]["Version"])
            self.assertEqual("", files["unversioned.dll"]["Version"])
            hashes = {row["File_"] for row in table("MsiFileHash")}
            self.assertNotIn(files["Gilune.exe"]["File"], hashes)
            self.assertNotIn(files["runtime.dll"]["File"], hashes)
            self.assertIn(files["unversioned.dll"]["File"], hashes)
            self.assertIn(files["readme.txt"]["File"], hashes)


if __name__ == "__main__":
    if "--require-msi-tools" in sys.argv:
        sys.argv.remove("--require-msi-tools")
        if not MSI_TOOLS:
            sys.exit("The MSI regression requires LLVM 19, wixl, msitools and python3-pefile")
    unittest.main()
