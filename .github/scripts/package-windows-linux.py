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


def write_wix(bundle, destination, release):
    ET.register_namespace("", WIX)
    def node(parent, name, **attrs):
        return ET.SubElement(parent, "{" + WIX + "}" + name, attrs)
    root = ET.Element("{" + WIX + "}Wix")
    product = node(root, "Product", Id="*", Name="Gilune", Language="1033",
                   Version=release, Manufacturer="Gilune", UpgradeCode=UPGRADE_CODE)
    node(product, "Package", InstallerVersion="500", Compressed="yes", InstallScope="perMachine")
    node(product, "Media", Id="1", Cabinet="payload.cab", EmbedCab="yes")
    node(product, "MajorUpgrade", DowngradeErrorMessage="A newer version of Gilune is installed.")
    node(product, "Icon", Id="GiluneIcon", SourceFile=str(bundle / "gilune.ico"))
    node(product, "Property", Id="ARPPRODUCTICON", Value="GiluneIcon")
    top = node(product, "Directory", Id="TARGETDIR", Name="SourceDir")
    program = node(top, "Directory", Id="ProgramFiles64Folder")
    install = node(program, "Directory", Id="INSTALLDIR", Name="Gilune")
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
                         Guid=str(uuid.uuid5(NAMESPACE, rel.as_posix())), Win64="yes")
        attributes = {}
        if embedded_version := file_version(path):
            attributes["DefaultVersion"] = embedded_version
        file_node = node(component, "File", Id=ident("F", rel.as_posix()),
                         Source=str(path), Name=path.name, DiskId="1", KeyPath="yes",
                         **attributes)
        if rel.as_posix() == "Gilune.exe":
            node(file_node, "Shortcut", Id="StartMenuShortcut", Directory="GiluneMenu",
                 Name="Gilune", WorkingDirectory="INSTALLDIR", Icon="GiluneIcon", Advertise="yes")
        node(feature, "ComponentRef", Id=component_id)
    # Remove the application menu folder only if empty; user data is never part
    # of this payload and must never appear in installer cleanup instructions.
    cleanup = node(menu_dir, "Component", Id="MenuCleanup",
                   Guid=str(uuid.uuid5(NAMESPACE, "menu-cleanup")), Win64="yes")
    node(cleanup, "RegistryValue", Root="HKLM", Key="Software\\Gilune", Name="Installed",
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
            "commit": args.commit, "version": release, "target": "windows-x64",
            "bindingsSha256": digest(args.native / "bindings/uniffi/gilune_ffi/gilune_ffi.kt"),
            "files": {p.relative_to(bundle).as_posix(): digest(p) for p in sorted(bundle.rglob("*")) if p.is_file()},
        }
        (bundle / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        write_wix(bundle, stage / "installer.wxs", release)
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
