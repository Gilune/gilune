<#
.SYNOPSIS
  Cross-compiles gilune-ffi for Android (both shipped ABIs), regenerates the
  Kotlin bindings from the Android binary, and stages the result into the app.

.DESCRIPTION
  ADR 0017 MB0. Run it from anywhere; it works out its own paths.

      pwsh kernel/scripts/build-android.ps1
      pwsh kernel/scripts/build-android.ps1 -NoWhisper      # capture-only fallback
      pwsh kernel/scripts/build-android.ps1 -Abi x86_64     # emulator only, faster

  What it produces:

    app/composeApp/src/androidMain/jniLibs/<abi>/libgilune_ffi.so
    app/composeApp/src/androidMain/jniLibs/<abi>/libc++_shared.so
    app/composeApp/src/androidMain/generated/uniffi/gilune_ffi/gilune_ffi.kt

  Bindings are generated PER TARGET from that target's own binary: UniFFI
  embeds checksums that differ between the desktop DLL and the Android .so, so
  reusing the desktop bindings gives a library that loads and then fails every
  call (architecture/07-compatibility.md, spikes/s1-kernel-boundary/README.md).

.PREREQUISITES
  Everything below is discovered automatically; the script fails with a named
  error if a piece is missing.

    * Android SDK at %LOCALAPPDATA%\Android\Sdk with an NDK under ndk\<version>.
      NOTE: this machine carries a STALE ANDROID_HOME=C:\Android\android-sdk
      environment variable. The script overrides it per process and never
      writes it back.
    * Rust targets: rustup target add aarch64-linux-android x86_64-linux-android
    * cmake on PATH (whisper builds only).
    * GNU make. There is no ninja and no Visual Studio on this machine, so the
      cmake generator is "Unix Makefiles" driven by msys2's make.exe. The NDK
      toolchain file governs the compiler either way; the NMake/VS-generator
      traps from the desktop build do not apply here.
    * LLVM (scoop) for libclang — whisper-rs-sys runs bindgen on the host.

.NOTES
  Traps this script exists to encode, all of them found the hard way:

  1. cmake 4.x (scoop) refuses whisper.cpp's older cmake_minimum_required
     unless CMAKE_POLICY_VERSION_MINIMUM=3.5.
  2. android.toolchain.cmake requires ANDROID_ABI, but whisper-rs-sys forwards
     only CMAKE_*/WHISPER_*/GGML_* environment variables into cmake. Left
     unset the NDK file defaults to armeabi-v7a and dies with "unknown target
     CPU 'armv7-a'" while nominally building x86_64. kernel/scripts/ndk/
     android.toolchain.cmake bridges that; see the comments in it.
  3. whisper-rs-sys guards two things on cfg!(target_os = "windows"), which in
     a BUILD SCRIPT means the HOST, not the target. Cross-compiling from
     Windows therefore injects an MSVC-only /utf-8 flag and a link directive
     for advapi32 into an Android build:
       - /utf-8 is suppressed by defining CMAKE_C_FLAGS / CMAKE_CXX_FLAGS
         ourselves, which makes the cmake crate skip its own flag block
         (cmake-0.1.58 src/lib.rs:730).
       - -ladvapi32 is satisfied with an empty stub archive. Nothing on Android
         references its symbols: the whisper.cpp code needing it is behind
         _WIN32 and is never compiled here.
  4. A failed cmake configure leaves a poisoned CMakeCache.txt that pins the
     wrong ANDROID_ABI. The script clears the whisper-rs-sys build directory
     before each configure rather than debugging a stale cache twice.
  5. Build paths stay SHORT (-TargetDir, default C:\gilune-android). ggml's
     object paths nest deeply and MAX_PATH truncation shows up as cmake
     try-compiles failing for no stated reason.
  6. whisper links the NDK's c++_shared, so libc++_shared.so must be staged
     into jniLibs beside libgilune_ffi.so or the app dies at load time.
#>

[CmdletBinding()]
param(
    # Which ABIs to build. Both by default.
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string[]] $Abi = @('arm64-v8a', 'x86_64'),

    # Build without local transcription. Capture never depends on it.
    [switch] $NoWhisper,

    # Short build path; see note 5 above.
    [string] $TargetDir = 'C:\gilune-android',

    # Android API level. Matches minSdk in app/composeApp/build.gradle.kts.
    [int] $ApiLevel = 29
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- paths ----

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifest = Join-Path $repo 'kernel\Cargo.toml'
$jniRoot = Join-Path $repo 'app\composeApp\src\androidMain\jniLibs'
$bindingsOut = Join-Path $repo 'app\composeApp\src\androidMain\generated'

# The stale ANDROID_HOME on this machine points at an SDK that no longer
# exists. Override it for this process only.
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk" }
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$ndkRoot = Join-Path $sdk 'ndk'
if (-not (Test-Path $ndkRoot)) { throw "No NDK installed under $ndkRoot" }
$ndk = (Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending |
        Select-Object -First 1).FullName
$toolchain = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64'
if (-not (Test-Path $toolchain)) { throw "NDK toolchain not found at $toolchain" }

Write-Host "SDK   $sdk"
Write-Host "NDK   $ndk"

# ABI -> (rust target, NDK tool prefix, sysroot lib dir)
$abis = @{
    'arm64-v8a' = @{ Target = 'aarch64-linux-android'; Prefix = 'aarch64-linux-android'; SysLib = 'aarch64-linux-android' }
    'x86_64'    = @{ Target = 'x86_64-linux-android'; Prefix = 'x86_64-linux-android'; SysLib = 'x86_64-linux-android' }
}

# ------------------------------------------------------------ toolchain ----

$installed = (rustup target list --installed) -split "`r?`n"
foreach ($name in $Abi) {
    $t = $abis[$name].Target
    if ($installed -notcontains $t) {
        Write-Host "Installing Rust target $t"
        rustup target add $t
        if ($LASTEXITCODE -ne 0) { throw "rustup target add $t failed" }
    }
}

if (-not $NoWhisper) {
    # cmake generator backend. No ninja and no Visual Studio here, so GNU make
    # from msys2 drives "Unix Makefiles"; the NDK toolchain file still governs
    # the compiler, so this choice is only about who runs the recipes.
    $make = @(
        'C:\Users\David\scoop\apps\msys2\current\usr\bin\make.exe',
        (Join-Path $env:USERPROFILE 'scoop\apps\msys2\current\usr\bin\make.exe')
    ) | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $make) {
        $cmd = Get-Command make, mingw32-make, ninja -ErrorAction SilentlyContinue |
               Select-Object -First 1
        if ($cmd) { $make = $cmd.Source }
    }
    if (-not $make) {
        throw 'No make/ninja found for the cmake generator. Install msys2 (scoop install msys2) or pass -NoWhisper.'
    }

    $libclang = @(
        (Join-Path $env:USERPROFILE 'scoop\apps\llvm\current\bin'),
        $env:LIBCLANG_PATH
    ) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'libclang.dll')) } | Select-Object -First 1
    if (-not $libclang) {
        throw 'libclang.dll not found (scoop install llvm), needed by whisper-rs-sys bindgen. Or pass -NoWhisper.'
    }

    Write-Host "make  $make"
    Write-Host "clang $libclang"

    # make finds its own runtime beside itself, but its recipes shell out.
    $env:PATH = "$(Split-Path $make);$env:PATH"

    # Empty archive standing in for the Windows-only advapi32; see note 3.
    $stub = Join-Path $TargetDir 'stub'
    New-Item -ItemType Directory -Force $stub | Out-Null
    Remove-Item -Force (Join-Path $stub 'libadvapi32.a') -ErrorAction SilentlyContinue
    Push-Location $stub
    try {
        & (Join-Path $toolchain 'bin\llvm-ar.exe') crs libadvapi32.a
        if ($LASTEXITCODE -ne 0) { throw 'llvm-ar failed to create the advapi32 stub' }
    } finally { Pop-Location }
}

# ---------------------------------------------------------------- build ----

$built = @{}

foreach ($name in $Abi) {
    $target = $abis[$name].Target
    $prefix = $abis[$name].Prefix
    $env_target = $target.Replace('-', '_')

    Write-Host ''
    Write-Host "=== $name ($target)$(if ($NoWhisper) { '' } else { ' + whisper' }) ==="

    $env:CARGO_TARGET_DIR = $TargetDir
    $env:PATH = "$toolchain\bin;$env:PATH"

    # Linker and C/C++ compilers. The NDK's per-API .cmd wrappers already carry
    # --target and --sysroot, which is why they are used rather than bare clang.
    Set-Item "env:CARGO_TARGET_$($env_target.ToUpper())_LINKER" "$toolchain\bin\$prefix$ApiLevel-clang.cmd"
    Set-Item "env:CC_$env_target" "$toolchain\bin\$prefix$ApiLevel-clang.cmd"
    Set-Item "env:CXX_$env_target" "$toolchain\bin\$prefix$ApiLevel-clang++.cmd"
    Set-Item "env:AR_$env_target" "$toolchain\bin\llvm-ar.exe"

    $features = @()
    if (-not $NoWhisper) {
        $features = @('--features', 'whisper')

        # Inputs to kernel/scripts/ndk/android.toolchain.cmake (note 2).
        $env:GILUNE_ANDROID_NDK = $ndk
        $env:GILUNE_ANDROID_ABI = $name
        $env:GILUNE_ANDROID_PLATFORM = "android-$ApiLevel"

        $env:CMAKE_TOOLCHAIN_FILE = Join-Path $PSScriptRoot 'ndk\android.toolchain.cmake'
        $env:CMAKE_GENERATOR = 'Unix Makefiles'
        $env:CMAKE_MAKE_PROGRAM = $make
        $env:CMAKE_POLICY_VERSION_MINIMUM = '3.5'   # note 1
        # Defining these suppresses the cmake crate's own flag block, and with
        # it the MSVC /utf-8 that whisper-rs-sys injects on a Windows host
        # (note 3). The NDK toolchain file supplies target and sysroot flags.
        $env:CMAKE_C_FLAGS = '-fPIC -w'
        $env:CMAKE_CXX_FLAGS = '-fPIC -w'

        $env:LIBCLANG_PATH = $libclang
        Set-Item "env:BINDGEN_EXTRA_CLANG_ARGS_$env_target" `
            "--target=$prefix$ApiLevel --sysroot=$toolchain\sysroot"

        $env:RUSTFLAGS = "-L $stub"                 # note 3, advapi32

        # note 4: never configure over a cache pinned to another ABI. Only wipe
        # when the cache disagrees, so a rerun does not rebuild whisper.cpp.
        Get-ChildItem (Join-Path $TargetDir "$target\release\build") `
            -Filter 'whisper-rs-sys-*' -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $cache = Join-Path $_.FullName 'out\build\CMakeCache.txt'
                $ok = (Test-Path $cache) -and
                      ((Get-Content $cache) -match "^ANDROID_ABI:.*=$([regex]::Escape($name))$")
                if (-not $ok) { Remove-Item $_.FullName -Recurse -Force }
            }
    }

    cargo build --manifest-path $manifest -p gilune-ffi --release --target $target @features
    if ($LASTEXITCODE -ne 0) {
        throw "cargo build failed for $target. If whisper is the cause, rerun with -NoWhisper: capture does not depend on transcription."
    }

    $built[$name] = Join-Path $TargetDir "$target\release\libgilune_ffi.so"
}

# --------------------------------------------------------------- stage ----

foreach ($name in $Abi) {
    $dest = Join-Path $jniRoot $name
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item $built[$name] (Join-Path $dest 'libgilune_ffi.so') -Force

    # whisper links c++_shared, so the STL must travel with it (note 6). The
    # NDK ships it unstripped (~9 MB of debug info); AGP would strip it at
    # packaging time, but jniLibs is a gitignored staging dir developers
    # carry locally, so strip it here and keep every checkout small.
    $stl = Join-Path $toolchain "sysroot\usr\lib\$($abis[$name].SysLib)\libc++_shared.so"
    if (Test-Path $stl) {
        $staged = Join-Path $dest 'libc++_shared.so'
        Copy-Item $stl $staged -Force
        & (Join-Path $toolchain 'bin\llvm-strip.exe') --strip-unneeded $staged
        if ($LASTEXITCODE -ne 0) { throw "llvm-strip failed on $staged" }
    }
}

# ------------------------------------------------------------ bindings ----

# Generated from the Android binary, never the desktop DLL. The uniffi-bindgen
# host binary must not inherit the Android cross-compile environment, or cargo
# will try to build it for Android.
foreach ($v in 'CARGO_TARGET_DIR', 'CMAKE_TOOLCHAIN_FILE', 'CMAKE_GENERATOR',
                'CMAKE_MAKE_PROGRAM', 'CMAKE_POLICY_VERSION_MINIMUM',
                'CMAKE_C_FLAGS', 'CMAKE_CXX_FLAGS', 'RUSTFLAGS') {
    Remove-Item "env:$v" -ErrorAction SilentlyContinue
}

$source = if ($built.ContainsKey('arm64-v8a')) { $built['arm64-v8a'] } else { $built[$Abi[0]] }
Write-Host ''
Write-Host "Generating Kotlin bindings from $source"
# uniffi-bindgen shells out to `cargo metadata` against the CURRENT DIRECTORY,
# not --manifest-path, so it must be run from inside the workspace.
Push-Location (Join-Path $repo 'kernel')
try {
    cargo run -p gilune-ffi --bin uniffi-bindgen -- `
        generate --library $source --language kotlin --out-dir $bindingsOut --no-format
    if ($LASTEXITCODE -ne 0) { throw 'uniffi-bindgen failed' }
} finally { Pop-Location }

# ------------------------------------------------------------- report ----

Write-Host ''
Write-Host 'Staged (ADR 0001 budget: < 15 MB per ABI):'
foreach ($name in $Abi) {
    Get-ChildItem (Join-Path $jniRoot $name) -Filter '*.so' | ForEach-Object {
        '{0,-12} {1,-20} {2,8:N2} MB' -f $name, $_.Name, ($_.Length / 1MB)
    }
}
Write-Host ''
Write-Host 'Next: cd app; .\gradlew :composeApp:compileDebugKotlinAndroid'
