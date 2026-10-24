"""Assemble a Windows runtime and MSI on Linux from separately built artifacts.

Requires cargo-xwin, LLVM 19, wixl and python3-pefile. Output must be new.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET

UPGRADE_CODE = "c077b799-5193-4ce3-b93a-411c67e29e37"
# Published jpackage MSIs that explicitly allow downgrades. Their incoming
# RemoveExistingProducts invokes our cached MSI with this product code.
LEGACY_DOWNGRADE_PRODUCTS = {"1.0.3": "{AAE0D767-8197-38BD-8519-19579879B9FE}"}
# The jpackage-built all-users releases. Every later all-users install wrote
# HKLM\Software\Gilune\Installed; these two did not, so a per-user package
# recognises them by their product registration instead. Windows Installer's
# own related-product search skips products of the other context.
JPACKAGE_PRODUCTS = {"1.0.1": "{982A4DDD-BAD3-3E27-A87A-52B95E3EF200}",
                     "1.0.3": LEGACY_DOWNGRADE_PRODUCTS["1.0.3"]}
NAMESPACE = uuid.UUID(UPGRADE_CODE)
WIX = "http://schemas.microsoft.com/wix/2006/wi"
REPO = Path(__file__).resolve().parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def version(value):
    if not re.fullmatch(r"[1-9][0-9]*\.[0-9]+\.[0-9]+", value):
        raise ValueError("Version must be MAJOR.MINOR.PATCH, with MAJOR at least 1")
    major, minor, patch = map(int, value.split("."))
    if major > 255 or minor > 255 or patch > 65535:
        raise ValueError("Version exceeds Windows Installer limits")
    return value


def ident(prefix, path):
    return prefix + hashlib.sha256(path.encode("utf-8")).hexdigest()[:24]


def file_version(path):
    """Read the binary's numeric version, not its product/display version."""
    if path.suffix.lower() not in (".exe", ".dll"):
        return None
    # wixl 0.103 does not harvest PE versions. Missing File.Version entries
    # make MSI prefer an existing versioned file, even during a major upgrade.
    # Import only during packaging so routing/version checks need no PE reader.
    import pefile
    with pefile.PE(str(path), fast_load=True) as image:
        image.parse_data_directories(
            directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_RESOURCE"]])
        versions = getattr(image, "VS_FIXEDFILEINFO", [])
        if not versions:
            return None
        info = versions[0]
        if info.Signature != 0xFEEF04BD:
            raise ValueError("Invalid fixed file version: " + str(path))
        return ".".join(str(part) for part in (
            info.FileVersionMS >> 16, info.FileVersionMS & 0xFFFF,
            info.FileVersionLS >> 16, info.FileVersionLS & 0xFFFF))


def write_launcher(bundle, destination, release):
    icon = str(bundle / "gilune.ico").replace("\\", "\\\\").replace('"', '\\"')
    numeric = release.replace(".", ",") + ",0"
    destination.write_text(f'''1 ICON "{icon}"
1 VERSIONINFO
FILEVERSION {numeric}
PRODUCTVERSION {numeric}
FILEOS 0x40004L
FILETYPE 0x1L
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904B0"
    BEGIN
      VALUE "FileDescription", "Gilune"
      VALUE "FileVersion", "{release}"
      VALUE "ProductName", "Gilune"
      VALUE "ProductVersion", "{release}"
      VALUE "OriginalFilename", "Gilune.exe"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x409, 1200
  END
END
''', encoding="utf-8")


def build_launcher(bundle, stage, release):
    resource = stage / "launcher.res"
    write_launcher(bundle, stage / "launcher.rc", release)
    subprocess.run(["llvm-rc-19", "/fo", str(resource), str(stage / "launcher.rc")], check=True)
    target_dir = REPO / "app/build/windows-launcher"
    env = dict(os.environ, GILUNE_APP_VERSION=release, XWIN_ARCH="x86_64",
               XWIN_VERSION="17", XWIN_CRT_VERSION="14.44.17.14", XWIN_SDK_VERSION="10.0.26100",
               CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS="-C target-feature=+crt-static")
    subprocess.run(["cargo", "xwin", "rustc", "--locked", "--release",
                    "--target", "x86_64-pc-windows-msvc", "--target-dir", str(target_dir),
                    "--manifest-path", str(REPO / ".github/windows-launcher/Cargo.toml"),
                    "--", "-C", "link-arg=" + str(resource)], env=env, check=True)
    shutil.copy2(target_dir / "x86_64-pc-windows-msvc/release/gilune-windows-launcher.exe", bundle / "Gilune.exe")


def node(parent, name, **attrs):
    return ET.SubElement(parent, "{" + WIX + "}" + name, attrs)


ICON_ID = "GiluneIcon.ico"
FIREWALL_RULE = "Gilune"
# wixl 0.103 builds EXE custom actions from a property (type 50) but not from
# a directory, so an immediate type 51 action formats the netsh path first.
NETSH_PROPERTY = "GILUNE_NETSH"
FIREWALL_DELETE = f'advfirewall firewall delete rule name="{FIREWALL_RULE}"'
# Every rule Windows ever made for the two programs the install has shipped,
# whatever its name or action. A Block row from a cancelled or non-administrator
# prompt on an earlier release beats any Allow, so it has to go.
FIREWALL_PROGRAMS = {
    "GiluneFirewallRemoveJavaw": r"[INSTALLDIR]runtime\bin\javaw.exe",
    "GiluneFirewallRemoveLauncher": "[INSTALLDIR]Gilune.exe",
}
FIREWALL_DELETE_PROGRAM = 'advfirewall firewall delete rule name=all program="{}"'
FIREWALL_ADD = (f'advfirewall firewall add rule name="{FIREWALL_RULE}" dir=in action=allow '
                'enable=yes profile=private program="[INSTALLDIR]Gilune.exe"')


def firewall_rules(product, sequence, only_when=None):
    """Allow the installed launcher through Windows Firewall on private networks.

    The rule is added by the installer, running as the system, so the user never
    meets the Windows prompt and a non-administrator can use sync (ADR 0019 § 2:
    Private profile only). `only_when` is a further condition on every scheduled
    step: the per-user package passes `Privileged`, so the rule is added only by
    an install someone started as administrator and an install without that
    right (the in-app update) touches no rule. The install owns every rule for
    its programs from here on: before adding, it deletes the named rule and
    every rule for the launcher or the earlier launcher's javaw.exe — netsh
    duplicates by name, and a Block row left by a dismissed prompt on an
    earlier release wins over any Allow. Uninstall deletes the same set. A product being replaced by a newer one
    leaves the rules alone (UPGRADINGPRODUCTCODE) so the ordering of
    RemoveExistingProducts cannot remove what the newer product just added.
    netsh failures never fail the install: without the rule the user is back to
    the Windows prompt.
    """
    node(product, "CustomAction", Id="GiluneNetshPath", Property=NETSH_PROPERTY,
         Value="[System64Folder]netsh.exe")
    removals = [("GiluneFirewallRemove", FIREWALL_DELETE)] + [
        (action, FIREWALL_DELETE_PROGRAM.format(program)) for action, program in FIREWALL_PROGRAMS.items()]
    actions = [(action, command, "deferred") for action, command in removals]
    actions += [("GiluneFirewallRollback", FIREWALL_DELETE, "rollback"),
                ("GiluneFirewallAdd", FIREWALL_ADD, "deferred")]
    for action, command, execute in actions:
        node(product, "CustomAction", Id=action, Property=NETSH_PROPERTY, ExeCommand=command,
             Execute=execute, Impersonate="no", Return="ignore")
    # Immediate, in the costing pass, so the path is known before the deferred
    # actions are written into the installation script.
    node(sequence, "Custom", Action="GiluneNetshPath", After="CostFinalize", Before="InstallInitialize")

    def when(condition):
        return condition if only_when is None else f"{only_when} AND ({condition})"
    previous = "InstallFiles"
    for action, _command in removals:
        node(sequence, "Custom", Action=action, After=previous).text = when("NOT UPGRADINGPRODUCTCODE")
        previous = action
    # Rollback is scheduled before the action it undoes.
    node(sequence, "Custom", Action="GiluneFirewallRollback", After=previous).text = when(
        'NOT REMOVE~="ALL"')
    node(sequence, "Custom", Action="GiluneFirewallAdd", After="GiluneFirewallRollback").text = when(
        'NOT REMOVE~="ALL"')


# Where the app installs. `per-machine` is Program Files with an elevated
# installer, which adds the firewall rule itself but makes every update ask
# for administrator approval. `per-user` is %LOCALAPPDATA%\Programs\Gilune
# and asks for no elevation, so the in-app updater installs silently. When
# someone starts it as administrator anyway (the first install on a managed
# laptop), it adds the same firewall rule; otherwise the rule is the app's
# own one-time elevated step (`Gilune.exe --allow-firewall`). The rule names
# a path every update keeps, so updates without elevation leave it in place;
# an uninstall without elevation cannot remove it and leaves it behind,
# naming a launcher that is gone. A per-user package cannot remove an
# all-users one, so it refuses to install beside it; the app's updater
# removes the all-users copy first, with one elevated uninstall.
SCOPES = ("per-machine", "per-user")
ALL_USERS_MESSAGE = ("Gilune is installed for all users of this computer. Remove that copy first, "
                     "from Settings > Apps, or let the installed Gilune update itself.")


def write_wix(bundle, destination, release, scope):
    if scope not in SCOPES:
        raise ValueError("scope must be one of " + ", ".join(SCOPES))
    per_user = scope == "per-user"
    ET.register_namespace("", WIX)
    root = ET.Element("{" + WIX + "}Wix")
    product = node(root, "Product", Id="*", Name="Gilune", Language="1033",
                   Version=release, Manufacturer="Gilune", UpgradeCode=UPGRADE_CODE)
    node(product, "Package", InstallerVersion="500", Compressed="yes",
         InstallScope="perUser" if per_user else "perMachine")
    if per_user:
        # Written by every per-machine install's MenuCleanup component; the
        # search reads the 64-bit view those x64 packages wrote to.
        search = node(product, "Property", Id="GILUNE_ALLUSERS")
        node(search, "RegistrySearch", Id="GiluneAllUsersSearch", Root="HKLM", Key="Software\\Gilune",
             Name="Installed", Type="raw", Win64="yes")
        for legacy_version, product_code in JPACKAGE_PRODUCTS.items():
            node(search, "RegistrySearch", Id="GiluneAllUsers" + legacy_version.replace(".", "_"), Root="HKLM",
                 Key="SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\" + product_code,
                 Name="DisplayName", Type="raw", Win64="yes")
        node(product, "Condition", Message=ALL_USERS_MESSAGE).text = "NOT GILUNE_ALLUSERS"
    node(product, "Media", Id="1", Cabinet="payload.cab", EmbedCab="yes")
    node(product, "MajorUpgrade", DowngradeErrorMessage="A newer version of Gilune is installed.")
    sequence = node(product, "InstallExecuteSequence")
    # wixl gives RemoveExistingProducts no position of its own: it is numbered
    # after whatever the dependency sort put before it, which was InstallFiles
    # until custom actions followed InstallFiles and Windows Installer refused
    # the package (error 2613). Immediately after InstallInitialize is one of
    # the documented slots and keeps the old product's removal inside the
    # transaction, so a failed upgrade rolls the old product back. Both bounds
    # are needed: wixl's sort only places an action where a dependency forces it.
    node(sequence, "RemoveExistingProducts", After="InstallInitialize", Before="ProcessComponents")
    for legacy_version, product_code in LEGACY_DOWNGRADE_PRODUCTS.items():
        if tuple(map(int, release.split("."))) > tuple(map(int, legacy_version.split("."))):
            # MajorUpgrade protects installs of this MSI over newer products.
            # Also protect this product's removal by an already-published MSI
            # whose own upgrade table permits downgrades. Ordinary uninstall
            # has no UPGRADINGPRODUCTCODE; future upgrades have another code.
            node(product, "Condition", Message="A newer version of Gilune is installed.").text = (
                f'NOT (UPGRADINGPRODUCTCODE ~= "{product_code}")')
    # Windows Installer writes the icon to its own folder under this Id and the
    # shell reads the file by its extension, so the Id carries one.
    node(product, "Icon", Id=ICON_ID, SourceFile=str(bundle / "gilune.ico"))
    node(product, "Property", Id="ARPPRODUCTICON", Value=ICON_ID)
    # netsh needs the system. A per-machine install always has it; a per-user
    # one has it only when started as administrator (`Privileged`).
    firewall_rules(product, sequence, only_when="Privileged" if per_user else None)
    top = node(product, "Directory", Id="TARGETDIR", Name="SourceDir")
    if per_user:
        program = node(node(top, "Directory", Id="LocalAppDataFolder"), "Directory", Id="GiluneUserPrograms", Name="Programs")
    else:
        program = node(top, "Directory", Id="ProgramFiles64Folder")
    install = node(program, "Directory", Id="INSTALLDIR", Name="Gilune")
    # Components differ by where they put their files, so the two scopes never
    # share a component identity.
    component_namespace = uuid.uuid5(NAMESPACE, "per-user") if per_user else NAMESPACE
    registry_root = "HKCU" if per_user else "HKLM"
    menu = node(top, "Directory", Id="ProgramMenuFolder")
    menu_dir = node(menu, "Directory", Id="GiluneMenu", Name="Gilune")
    feature = node(product, "Feature", Id="Complete", Title="Gilune", Level="1")
    directories = {".": install}
    for path in sorted(bundle.rglob("*")):
        if path.is_symlink():
            raise ValueError("Symlinks are not supported in Windows payloads")
        rel = path.relative_to(bundle)
        parent = directories[rel.parent.as_posix()]
        if path.is_dir():
            directories[rel.as_posix()] = node(parent, "Directory", Id=ident("D", rel.as_posix()), Name=path.name)
            continue
        component_id = ident("C", rel.as_posix())
        component = node(parent, "Component", Id=component_id,
                         Guid=str(uuid.uuid5(component_namespace, rel.as_posix())), Win64="yes")
        attributes = {}
        if embedded_version := file_version(path):
            attributes["DefaultVersion"] = embedded_version
        if per_user:
            # A component under the user profile keys on a per-user registry
            # value, not on its file (Windows Installer's rule for profile
            # folders), so each user's own install state is what is checked.
            node(component, "RegistryValue", Root="HKCU", Key="Software\\Gilune\\Components",
                 Name=component_id, Type="integer", Value="1", KeyPath="yes")
        else:
            attributes["KeyPath"] = "yes"
        file_node = node(component, "File", Id=ident("F", rel.as_posix()),
                         Source=str(path), Name=path.name, DiskId="1", **attributes)
        if rel.as_posix() == "Gilune.exe":
            # A plain shortcut to the installed file. wixl 0.103 writes the
            # owning feature as the target when none is given, which makes
            # the shortcut advertised: Start then resolves it through the
            # Installer, shows no logo and offers no "Run as administrator".
            # (`Advertise="no"` is the WiX default; wixl only logs
            # "unimplemented" for it, so the attribute is left out.)
            node(file_node, "Shortcut", Id="StartMenuShortcut", Directory="GiluneMenu",
                 Name="Gilune", WorkingDirectory="INSTALLDIR", Icon=ICON_ID,
                 Target="[#" + file_node.attrib["Id"] + "]")
        node(feature, "ComponentRef", Id=component_id)
    # Remove the application menu folder only if empty; user data is never part
    # of this payload and must never appear in installer cleanup instructions.
    cleanup = node(menu_dir, "Component", Id="MenuCleanup",
                   Guid=str(uuid.uuid5(component_namespace, "menu-cleanup")), Win64="yes")
    node(cleanup, "RegistryValue", Root=registry_root, Key="Software\\Gilune", Name="Installed",
         Type="integer", Value="1", KeyPath="yes")
    node(cleanup, "RemoveFolder", Id="RemoveMenu", On="uninstall")
    node(feature, "ComponentRef", Id="MenuCleanup")
    ET.ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("libs", "native", "runtime", "output"):
        parser.add_argument("--" + arg, type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--scope", required=True, choices=SCOPES)
    args = parser.parse_args()
    release = version(args.version)
    for needed in (args.libs / "composeApp-desktop.jar", args.native / "gilune_ffi.dll",
                   args.native / "bindings/uniffi/gilune_ffi/gilune_ffi.kt",
                   args.runtime / "bin/javaw.exe", args.runtime / "release"):
        if not needed.is_file() or needed.stat().st_size == 0:
            raise ValueError("Missing or empty input: " + str(needed))
    runtime_release = (args.runtime / "release").read_text()
    if 'OS_NAME="Windows"' not in runtime_release or not any(
            f'OS_ARCH="{arch}"' in runtime_release for arch in ("amd64", "x86_64")):
        raise ValueError("Runtime must be Windows x64")
    native_runtimes = list(args.libs.glob("skiko-awt-runtime-*.jar"))
    if len(native_runtimes) != 1 or "windows-x64" not in native_runtimes[0].name:
        raise ValueError("Exactly one Windows x64 Skiko runtime is required")
    metadata = dict(line.split("=", 1) for line in
                    (args.libs / "export.properties").read_text().splitlines() if line)
    if metadata.get("version") != release or metadata.get("target") != "windows-x64":
        raise ValueError("Exported application version/target does not match the installer")
    if metadata.get("bindings.sha256") != digest(args.native / "bindings/uniffi/gilune_ffi/gilune_ffi.kt"):
        raise ValueError("Application was compiled against different native bindings")
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Output already exists; use a fresh directory: " + str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="windows-package-", dir=output.parent) as tmp:
        stage = Path(tmp)
        bundle = stage / "Gilune"
        bundle.mkdir()
        shutil.copytree(args.libs, bundle / "lib")
        shutil.copytree(args.runtime, bundle / "runtime")
        (bundle / "resources").mkdir()
        shutil.copy2(args.native / "gilune_ffi.dll", bundle / "resources/gilune_ffi.dll")
        shutil.copy2(REPO / "LICENSE", bundle / "LICENSE.txt")
        shutil.copy2(REPO / "app/composeApp/src/desktopMain/resources/gilune-v2.ico", bundle / "gilune.ico")
        build_launcher(bundle, stage, release)
        manifest = {
            "commit": args.commit, "version": release, "target": "windows-x64", "scope": args.scope,
            "bindingsSha256": digest(args.native / "bindings/uniffi/gilune_ffi/gilune_ffi.kt"),
            "files": {p.relative_to(bundle).as_posix(): digest(p) for p in sorted(bundle.rglob("*")) if p.is_file()},
        }
        (bundle / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        write_wix(bundle, stage / "installer.wxs", release, args.scope)
        subprocess.run(["wixl", "-a", "x64", "-o", str(stage / ("Gilune-" + release + ".msi")),
                        str(stage / "installer.wxs")], check=True)
        # Do not retain temporary absolute paths as reusable build instructions.
        (stage / "launcher.rc").unlink()
        (stage / "launcher.res").unlink()
        (stage / "installer.wxs").unlink()
        shutil.copytree(stage, output)
    print(output)


if __name__ == "__main__":
    main()
