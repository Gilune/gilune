import importlib.util
from pathlib import Path
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
            for name in ("Gilune.exe", "gilune.ico", "lib/name & unicode-é.jar"):
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


if __name__ == "__main__":
    unittest.main()
