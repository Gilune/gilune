import importlib.util
import csv
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import unittest.mock
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

    def test_start_menu_shortcut_targets_the_installed_launcher_itself(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            bundle.mkdir()
            for name in ("Gilune.exe", "gilune.ico", "program.jar"):
                (bundle / name).write_text("fixture")
            path = root / "installer.wxs"
            # The fixture launcher is not a PE file; the version reader is
            # covered by the real-MSI regression below.
            with unittest.mock.patch.object(package, "file_version", lambda _path: None):
                package.write_wix(bundle, path, "1.0.7")
            tree = ET.parse(path)
            shortcuts = tree.findall(".//w:Shortcut", NS)
            self.assertEqual(1, len(shortcuts))
            shortcut = shortcuts[0]
            launcher = [f for f in tree.findall(".//w:File", NS) if f.attrib["Name"] == "Gilune.exe"]
            self.assertEqual(1, len(launcher))
            # wixl writes the feature as the target when none is given, which
            # is an advertised shortcut: no logo in Start search and no "Run as
            # administrator". The target names the installed file instead.
            self.assertEqual("[#" + launcher[0].attrib["Id"] + "]", shortcut.attrib["Target"])
            self.assertNotIn("Advertise", shortcut.attrib)
            self.assertEqual("INSTALLDIR", shortcut.attrib["WorkingDirectory"])
            icon = tree.find(".//w:Icon", NS)
            self.assertTrue(icon.attrib["Id"].endswith(".ico"), icon.attrib)
            self.assertEqual(icon.attrib["Id"], shortcut.attrib["Icon"])
            arp = [p for p in tree.findall(".//w:Property", NS) if p.attrib["Id"] == "ARPPRODUCTICON"]
            self.assertEqual(icon.attrib["Id"], arp[0].attrib["Value"])

    def test_firewall_rule_is_added_for_private_networks_and_removed_at_uninstall(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            bundle.mkdir()
            (bundle / "gilune.ico").write_text("fixture")
            path = root / "installer.wxs"
            package.write_wix(bundle, path, "1.0.7")
            tree = ET.parse(path)
            actions = {a.attrib["Id"]: a.attrib for a in tree.findall(".//w:CustomAction", NS)}
            self.assertEqual({"GiluneNetshPath", "GiluneFirewallRemove", "GiluneFirewallRemoveJavaw",
                              "GiluneFirewallRemoveLauncher", "GiluneFirewallRollback",
                              "GiluneFirewallAdd"}, set(actions))
            # The netsh path is a property formatted by an immediate action:
            # wixl 0.103 has no Directory-sourced EXE custom actions.
            self.assertEqual("[System64Folder]netsh.exe", actions["GiluneNetshPath"]["Value"])
            # Rules Windows made for either program (a Block from a dismissed
            # prompt on 1.1.0 beats any Allow) go before the Allow is added.
            self.assertEqual(r'advfirewall firewall delete rule name=all program="[INSTALLDIR]runtime\bin\javaw.exe"',
                             actions["GiluneFirewallRemoveJavaw"]["ExeCommand"])
            self.assertEqual('advfirewall firewall delete rule name=all program="[INSTALLDIR]Gilune.exe"',
                             actions["GiluneFirewallRemoveLauncher"]["ExeCommand"])
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher",
                         "GiluneFirewallRollback", "GiluneFirewallAdd"):
                action = actions[name]
                self.assertEqual(package.NETSH_PROPERTY, action["Property"])
                self.assertEqual("no", action["Impersonate"], "netsh needs the system, not the user")
                self.assertEqual("ignore", action["Return"], "a firewall that will not take a rule must not fail the install")
                self.assertIn("firewall", action["ExeCommand"])
            add = actions["GiluneFirewallAdd"]["ExeCommand"]
            self.assertIn('name="Gilune"', add)
            self.assertIn("profile=private", add)
            self.assertNotIn("public", add)
            self.assertIn("dir=in action=allow enable=yes", add)
            self.assertIn('program="[INSTALLDIR]Gilune.exe"', add)
            self.assertEqual("deferred", actions["GiluneFirewallAdd"]["Execute"])
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual("deferred", actions[name]["Execute"])
            self.assertEqual("rollback", actions["GiluneFirewallRollback"]["Execute"])
            self.assertEqual(actions["GiluneFirewallRemove"]["ExeCommand"], actions["GiluneFirewallRollback"]["ExeCommand"])
            self.assertIn('delete rule name="Gilune"', actions["GiluneFirewallRemove"]["ExeCommand"])
            steps = {c.attrib["Action"]: c for c in tree.findall(".//w:InstallExecuteSequence/w:Custom", NS)}
            self.assertEqual("InstallFiles", steps["GiluneFirewallRemove"].attrib["After"])
            self.assertEqual("GiluneFirewallRemove", steps["GiluneFirewallRemoveJavaw"].attrib["After"])
            self.assertEqual("GiluneFirewallRemoveJavaw", steps["GiluneFirewallRemoveLauncher"].attrib["After"])
            self.assertEqual("GiluneFirewallRemoveLauncher", steps["GiluneFirewallRollback"].attrib["After"])
            self.assertEqual("GiluneFirewallRollback", steps["GiluneFirewallAdd"].attrib["After"])
            # Removal leaves the rules alone while a newer product replaces this
            # one, whatever order RemoveExistingProducts runs in; adding never
            # happens during removal.
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual("NOT UPGRADINGPRODUCTCODE", steps[name].text)
            self.assertEqual('NOT REMOVE~="ALL"', steps["GiluneFirewallAdd"].text)
            self.assertEqual('NOT REMOVE~="ALL"', steps["GiluneFirewallRollback"].text)
            self.assertIsNone(steps["GiluneNetshPath"].text)

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
            conditions = {row["Condition"] for row in table("LaunchCondition")}
            self.assertIn(
                'NOT (UPGRADINGPRODUCTCODE ~= "{AAE0D767-8197-38BD-8519-19579879B9FE}")',
                conditions)
            sequence = {row["Action"]: row for row in table("InstallExecuteSequence")}
            self.assertEqual("", sequence["LaunchConditions"]["Condition"])
            self.assertLess(int(sequence["LaunchConditions"]["Sequence"]),
                            int(sequence["InstallInitialize"]["Sequence"]))
            # A plain shortcut: the Shortcut table's target is the file, not
            # the feature wixl would otherwise write (an advertised shortcut).
            shortcuts = table("Shortcut")
            self.assertEqual(1, len(shortcuts))
            self.assertEqual("[#" + files["Gilune.exe"]["File"] + "]", shortcuts[0]["Target"])
            self.assertEqual(package.ICON_ID, shortcuts[0]["Icon_"])
            self.assertEqual({package.ICON_ID}, {row["Name"] for row in table("Icon")})
            # Firewall custom actions as Windows Installer will read them:
            # type 51 sets the property; type 50 runs the property's exe,
            # deferred (0x400) without impersonation (0x800), continuing on
            # failure (0x40); rollback adds 0x100.
            custom = {row["Action"]: row for row in table("CustomAction")}
            self.assertEqual("51", custom["GiluneNetshPath"]["Type"])
            self.assertEqual(package.NETSH_PROPERTY, custom["GiluneNetshPath"]["Source"])
            self.assertEqual(str(50 | 0x400 | 0x800 | 0x40), custom["GiluneFirewallAdd"]["Type"])
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual(str(50 | 0x400 | 0x800 | 0x40), custom[name]["Type"])
            self.assertEqual(str(50 | 0x400 | 0x100 | 0x800 | 0x40), custom["GiluneFirewallRollback"]["Type"])
            for name in ("GiluneFirewallAdd", "GiluneFirewallRemove", "GiluneFirewallRemoveJavaw",
                         "GiluneFirewallRemoveLauncher", "GiluneFirewallRollback"):
                self.assertEqual(package.NETSH_PROPERTY, custom[name]["Source"])
            self.assertEqual(package.FIREWALL_ADD, custom["GiluneFirewallAdd"]["Target"])
            self.assertEqual(package.FIREWALL_DELETE, custom["GiluneFirewallRemove"]["Target"])
            self.assertEqual(r'advfirewall firewall delete rule name=all program="[INSTALLDIR]runtime\bin\javaw.exe"',
                             custom["GiluneFirewallRemoveJavaw"]["Target"])
            # Scheduled inside the transaction, in the order deletes, rollback,
            # add, each with its condition.
            order = [int(sequence[a]["Sequence"]) for a in (
                "InstallInitialize", "InstallFiles", "GiluneFirewallRemove", "GiluneFirewallRemoveJavaw",
                "GiluneFirewallRemoveLauncher", "GiluneFirewallRollback", "GiluneFirewallAdd", "InstallFinalize")]
            self.assertEqual(order, sorted(order))
            self.assertEqual(len(order), len(set(order)))
            self.assertLess(int(sequence["GiluneNetshPath"]["Sequence"]), int(sequence["InstallInitialize"]["Sequence"]))
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual("NOT UPGRADINGPRODUCTCODE", sequence[name]["Condition"])
            self.assertEqual('NOT REMOVE~="ALL"', sequence["GiluneFirewallAdd"]["Condition"])
            self.assertEqual('NOT REMOVE~="ALL"', sequence["GiluneFirewallRollback"]["Condition"])


if __name__ == "__main__":
    if "--require-msi-tools" in sys.argv:
        sys.argv.remove("--require-msi-tools")
        if not MSI_TOOLS:
            sys.exit("The MSI regression requires LLVM 19, wixl, msitools and python3-pefile")
    unittest.main()
