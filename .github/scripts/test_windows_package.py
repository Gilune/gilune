import importlib.util
import csv
import io
from pathlib import Path
import re
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

    def test_runtime_copy_leaves_out_the_large_heap_class_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = root / "jre/bin/server"
            server.mkdir(parents=True)
            for name in ("jvm.dll", "classes.jsa", "classes_nocoops.jsa"):
                (server / name).write_text(name)
            package.copy_runtime(root / "jre", root / "runtime")
            self.assertEqual(["classes.jsa", "jvm.dll"],
                             sorted(p.name for p in (root / "runtime/bin/server").iterdir()))
            # A runtime that no longer ships the file is a stale rule, not a
            # silent pass.
            (server / "classes_nocoops.jsa").unlink()
            with self.assertRaises(FileNotFoundError):
                package.copy_runtime(root / "jre", root / "again")

    def test_launcher_heap_cap_keeps_the_dropped_cache_unused(self):
        # The cache left out above serves only heaps of 32 GB and more.
        launcher = (package.REPO / ".github/windows-launcher/src/main.rs").read_text(encoding="utf-8")
        caps = re.findall(r'"-Xmx(\d+)([mMgG])"', launcher)
        self.assertEqual(1, len(caps), caps)
        amount, unit = caps[0]
        megabytes = int(amount) * (1024 if unit in "gG" else 1)
        self.assertLess(megabytes, 32 * 1024)

    def test_component_identity_survives_version_changes_and_added_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            (bundle / "lib").mkdir(parents=True)
            for name in ("program.jar", "gilune.ico", "lib/name & unicode-é.jar"):
                (bundle / name).write_text("fixture")
            def components(release):
                path = root / (release + ".wxs")
                package.write_wix(bundle, path, release, "per-machine")
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
                package.write_wix(bundle, path, "1.0.7", "per-machine")
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
            package.write_wix(bundle, path, "1.0.7", "per-machine")
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

    def test_per_user_package_touches_the_firewall_only_when_started_as_administrator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            bundle.mkdir()
            (bundle / "gilune.ico").write_text("fixture")
            trees = {}
            for scope in package.SCOPES:
                path = root / (scope + ".wxs")
                package.write_wix(bundle, path, "1.2.3", scope)
                trees[scope] = ET.parse(path)
            # The same actions as the per-machine package: one rule, Private
            # only, every earlier rule for the programs cleared first.
            user, machine = ({a.attrib["Id"]: a.attrib for a in trees[t].findall(".//w:CustomAction", NS)}
                             for t in ("per-user", "per-machine"))
            self.assertEqual(machine, user)
            steps = {t: {c.attrib["Action"]: c for c in trees[t].findall(".//w:InstallExecuteSequence/w:Custom", NS)}
                     for t in package.SCOPES}
            self.assertEqual(set(steps["per-machine"]), set(steps["per-user"]))
            # Every scheduled step that runs netsh waits for an install with
            # administrator rights; the in-app update has none, so it neither
            # adds nor deletes a rule.
            for name, step in steps["per-user"].items():
                with self.subTest(name=name):
                    if name == "GiluneNetshPath":
                        self.assertIsNone(step.text, "formatting a path needs no rights")
                        continue
                    self.assertEqual(f'Privileged AND ({steps["per-machine"][name].text})', step.text)
                    self.assertEqual(steps["per-machine"][name].attrib, step.attrib)

    def test_per_user_package_needs_no_elevation_and_refuses_an_all_users_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "Gilune"
            (bundle / "lib").mkdir(parents=True)
            for name in ("Gilune.exe", "gilune.ico", "lib/program.jar"):
                (bundle / name).write_text("fixture")
            trees = {}
            with unittest.mock.patch.object(package, "file_version", lambda _path: None):
                for scope in package.SCOPES:
                    path = root / (scope + ".wxs")
                    package.write_wix(bundle, path, "1.2.1", scope)
                    trees[scope] = ET.parse(path)
            user, machine = trees["per-user"], trees["per-machine"]
            self.assertEqual("perUser", user.find(".//w:Package", NS).attrib["InstallScope"])
            self.assertEqual("perMachine", machine.find(".//w:Package", NS).attrib["InstallScope"])
            # The files live under the profile.
            local = user.find(".//w:Directory[@Id='LocalAppDataFolder']", NS)
            self.assertIsNotNone(local)
            programs = local.find("w:Directory", NS)
            self.assertEqual("Programs", programs.attrib["Name"])
            self.assertEqual("INSTALLDIR", programs.find("w:Directory", NS).attrib["Id"])
            self.assertIsNone(user.find(".//w:Directory[@Id='ProgramFiles64Folder']", NS))
            # Profile-folder components key on a per-user registry value.
            file_components = [c for c in user.findall(".//w:Component", NS) if c.find("w:File", NS) is not None]
            self.assertEqual(3, len(file_components))
            for component in file_components:
                self.assertNotIn("KeyPath", component.find("w:File", NS).attrib)
                key = component.find("w:RegistryValue", NS)
                self.assertEqual(("HKCU", "yes", component.attrib["Id"]),
                                 (key.attrib["Root"], key.attrib["KeyPath"], key.attrib["Name"]))
            for component in machine.findall(".//w:Component", NS):
                file = component.find("w:File", NS)
                if file is not None:
                    self.assertEqual("yes", file.attrib["KeyPath"])
            cleanup = {t: [c for c in trees[t].findall(".//w:Component", NS) if c.attrib["Id"] == "MenuCleanup"][0]
                       for t in package.SCOPES}
            self.assertEqual("HKCU", cleanup["per-user"].find("w:RegistryValue", NS).attrib["Root"])
            self.assertEqual("HKLM", cleanup["per-machine"].find("w:RegistryValue", NS).attrib["Root"])
            # Different places, different component identities.
            guids = {t: {c.attrib["Id"]: c.attrib["Guid"] for c in trees[t].findall(".//w:Component", NS)}
                     for t in package.SCOPES}
            self.assertEqual(set(guids["per-user"]), set(guids["per-machine"]))
            for component_id in guids["per-user"]:
                self.assertNotEqual(guids["per-user"][component_id], guids["per-machine"][component_id])
            # It cannot remove an all-users copy, so it refuses to install beside one.
            searches = user.findall(".//w:Property[@Id='GILUNE_ALLUSERS']/w:RegistrySearch", NS)
            self.assertEqual(("HKLM", "Software\\Gilune", "Installed", "yes"),
                             (searches[0].attrib["Root"], searches[0].attrib["Key"], searches[0].attrib["Name"], searches[0].attrib["Win64"]))
            # The jpackage releases wrote no such value; their product
            # registrations are searched by product code instead.
            self.assertEqual(
                {"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + code for code in package.JPACKAGE_PRODUCTS.values()},
                {s.attrib["Key"] for s in searches[1:]})
            self.assertIn("{AAE0D767-8197-38BD-8519-19579879B9FE}", package.JPACKAGE_PRODUCTS.values())
            for search in searches[1:]:
                self.assertEqual(("HKLM", "DisplayName", "raw", "yes"),
                                 (search.attrib["Root"], search.attrib["Name"], search.attrib["Type"], search.attrib["Win64"]))
            conditions = {c.text: c.attrib["Message"] for c in user.findall("w:Product/w:Condition", NS)}
            self.assertEqual(package.ALL_USERS_MESSAGE, conditions["NOT GILUNE_ALLUSERS"])
            self.assertNotIn("NOT GILUNE_ALLUSERS", {c.text for c in machine.findall("w:Product/w:Condition", NS)})
            # The shortcut is the same plain one in both.
            for tree in trees.values():
                self.assertTrue(tree.find(".//w:Shortcut", NS).attrib["Target"].startswith("[#"))
            with self.assertRaises(ValueError):
                package.write_wix(bundle, root / "bad.wxs", "1.2.1", "everyone")

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
            package.write_wix(bundle, source, "1.0.7", "per-machine")
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
            # RemoveExistingProducts sits immediately after InstallInitialize: a
            # documented slot, inside the transaction. Left to wixl it followed
            # the custom actions and Windows Installer refused the package.
            self.assertEqual(int(sequence["InstallInitialize"]["Sequence"]) + 1,
                             int(sequence["RemoveExistingProducts"]["Sequence"]))
            self.assertLess(int(sequence["RemoveExistingProducts"]["Sequence"]), int(sequence["InstallFiles"]["Sequence"]))
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual("NOT UPGRADINGPRODUCTCODE", sequence[name]["Condition"])
            self.assertIn("ALLUSERS", {row["Property"] for row in table("Property")})

            # The per-user package, as Windows Installer reads it: no ALLUSERS,
            # the no-privileges word-count bit, registry key paths, the
            # all-users search and the condition that refuses it.
            user_source = root / "per-user.wxs"
            package.write_wix(bundle, user_source, "1.0.7", "per-user")
            installer = root / "per-user.msi"
            subprocess.run(["wixl", "-a", "x64", "-o", str(installer), str(user_source)], check=True)
            self.assertNotIn("ALLUSERS", {row["Property"] for row in table("Property")})
            summary = subprocess.check_output(["msiinfo", "suminfo", str(installer)], text=True)
            source_flags = int(re.search(r"^Source:\s*(\d+)", summary, re.MULTILINE)[1])
            self.assertTrue(source_flags & 8, f"the no-privileges bit is not set in source flags {source_flags}")
            machine_summary = subprocess.check_output(["msiinfo", "suminfo", str(root / "candidate.msi")], text=True)
            self.assertFalse(int(re.search(r"^Source:\s*(\d+)", machine_summary, re.MULTILINE)[1]) & 8)
            components = {row["Component"]: row for row in table("Component")}
            registry = {row["Registry"]: row for row in table("Registry")}
            files = {row["FileName"]: row for row in table("File")}
            for name in ("Gilune.exe", "runtime.dll", "readme.txt"):
                component = components[files[name]["Component_"]]
                self.assertIn(component["KeyPath"], registry, name + " keys on its file, not a registry value")
                self.assertTrue(int(component["Attributes"]) & 4, "msidbComponentAttributesRegistryKeyPath")
                self.assertEqual("Software\\Gilune\\Components", registry[component["KeyPath"]]["Key"])
                self.assertEqual("1", registry[component["KeyPath"]]["Root"], "HKCU")
            directories = {row["Directory"]: row for row in table("Directory")}
            self.assertEqual("LocalAppDataFolder", directories["GiluneUserPrograms"]["Directory_Parent"])
            self.assertEqual("GiluneUserPrograms", directories["INSTALLDIR"]["Directory_Parent"])
            # The firewall actions exactly as the per-machine package has them,
            # each scheduled only for an install with administrator rights; the
            # no-privileges bit above means double-clicking never asks for them.
            self.assertEqual(custom, {row["Action"]: row for row in table("CustomAction")})
            user_sequence = {row["Action"]: row for row in table("InstallExecuteSequence")}
            for name in ("GiluneFirewallRemove", "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher"):
                self.assertEqual("Privileged AND (NOT UPGRADINGPRODUCTCODE)", user_sequence[name]["Condition"])
            for name in ("GiluneFirewallAdd", "GiluneFirewallRollback"):
                self.assertEqual('Privileged AND (NOT REMOVE~="ALL")', user_sequence[name]["Condition"])
            self.assertEqual("", user_sequence["GiluneNetshPath"]["Condition"])
            user_order = [int(user_sequence[a]["Sequence"]) for a in (
                "InstallInitialize", "RemoveExistingProducts", "InstallFiles", "GiluneFirewallRemove",
                "GiluneFirewallRemoveJavaw", "GiluneFirewallRemoveLauncher", "GiluneFirewallRollback",
                "GiluneFirewallAdd", "InstallFinalize")]
            self.assertEqual(user_order, sorted(user_order))
            self.assertEqual({"GILUNE_ALLUSERS"}, {row["Property"] for row in table("AppSearch")})
            self.assertEqual(1 + len(package.JPACKAGE_PRODUCTS), len(table("AppSearch")))
            locators = {row["Key"]: row for row in table("RegLocator")}
            self.assertEqual(1 + len(package.JPACKAGE_PRODUCTS), len(locators))
            self.assertEqual(("2", "Installed"), (locators["Software\\Gilune"]["Root"], locators["Software\\Gilune"]["Name"]))
            for code in package.JPACKAGE_PRODUCTS.values():
                locator = locators["SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + code]
                self.assertEqual(("2", "DisplayName"), (locator["Root"], locator["Name"]))
            self.assertIn("NOT GILUNE_ALLUSERS", {row["Condition"] for row in table("LaunchCondition")})
            # The per-machine package's steps stay unconditional on rights.
            self.assertEqual('NOT REMOVE~="ALL"', sequence["GiluneFirewallAdd"]["Condition"])
            self.assertEqual('NOT REMOVE~="ALL"', sequence["GiluneFirewallRollback"]["Condition"])


if __name__ == "__main__":
    if "--require-msi-tools" in sys.argv:
        sys.argv.remove("--require-msi-tools")
        if not MSI_TOOLS:
            sys.exit("The MSI regression requires LLVM 19, wixl, msitools and python3-pefile")
    unittest.main()
