#!/usr/bin/env bash
#
# Cross-compiles gilune-ffi for both shipped Android ABIs on a Linux CI
# runner, regenerates the Kotlin bindings from the Android binary, and
# stages the result into the app. It is the Linux counterpart of
# kernel/scripts/build-android.ps1, which stays the owner's Windows path.
#
# Most of the traps that script encodes are HOST-Windows ones: whisper-rs-sys
# guards on cfg!(target_os = "windows"), which in a build script means the
# machine doing the building, so an MSVC /utf-8 flag and an advapi32 link
# directive are injected into an Android build from Windows and from nowhere
# else. Neither fires here, and there is no Visual Studio to detect and no
# msys make to find. Three traps are the target's, not the host's, and they
# apply on every machine:
#
#   1. android.toolchain.cmake requires ANDROID_ABI, and whisper-rs-sys
#      forwards only CMAKE_*/WHISPER_*/GGML_* environment variables into
#      cmake. kernel/scripts/ndk/android.toolchain.cmake bridges that from
#      GILUNE_ANDROID_ABI, and its file name must stay
#      android.toolchain.cmake: the cmake crate recognises an NDK
#      cross-build by the toolchain file's name.
#   2. A CMakeCache left by another ABI pins the wrong one. Each ABI is
#      built in its own CARGO_TARGET_DIR, so there is no cache to clear.
#   3. whisper links the NDK's c++_shared, so libc++_shared.so must be
#      staged beside libgilune_ffi.so or the app dies at load time.
#
# Usage:  .github/scripts/build-android.sh [abi ...]      # default: both
#
# Environment: ANDROID_HOME (or ANDROID_SDK_ROOT), LIBCLANG_PATH,
# GILUNE_NDK_VERSION (optional, pins the NDK), GILUNE_ANDROID_TARGET_DIR
# (optional, where the cross-build's artefacts go).

set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || { echo "ANDROID_HOME is not set" >&2; exit 1; }

# Pinned rather than "whichever is newest": the NDK is a compiler, and a
# silently different one is a silently different binary.
ndk_version=${GILUNE_NDK_VERSION:-27.2.12479018}
ndk="$sdk/ndk/$ndk_version"
[ -d "$ndk" ] || { echo "NDK $ndk_version not installed at $ndk" >&2; exit 1; }

toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$toolchain" ] || { echo "NDK toolchain not found at $toolchain" >&2; exit 1; }

# Matches minSdk in app/composeApp/build.gradle.kts.
api=29

target_root=${GILUNE_ANDROID_TARGET_DIR:-${RUNNER_TEMP:-$repo/kernel/target}/gilune-android}

if [ "$#" -gt 0 ]; then abis=("$@"); else abis=(arm64-v8a x86_64); fi

# ABI -> Rust target triple. The NDK's sysroot library directory and its
# clang wrapper prefix are both the triple as well.
triple_of() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64)    echo x86_64-linux-android ;;
    *)         echo "unknown ABI: $1" >&2; exit 1 ;;
  esac
}

echo "SDK   $sdk"
echo "NDK   $ndk"

for abi in "${abis[@]}"; do
  triple=$(triple_of "$abi")
  rustup target add "$triple"
done

# ------------------------------------------------------------------ build

for abi in "${abis[@]}"; do
  triple=$(triple_of "$abi")
  env_triple=${triple//-/_}
  upper_triple=$(echo "$env_triple" | tr '[:lower:]' '[:upper:]')

  echo
  echo "=== $abi ($triple) + whisper ==="

  # A subshell so the cross-compile environment cannot reach the host
  # uniffi-bindgen build below, which would try to build it for Android.
  (
    export CARGO_TARGET_DIR="$target_root/$abi"

    # The NDK's per-API clang wrappers already carry --target and
    # --sysroot, which is why they are used rather than bare clang.
    export "CARGO_TARGET_${upper_triple}_LINKER=$toolchain/bin/${triple}${api}-clang"
    export "CC_${env_triple}=$toolchain/bin/${triple}${api}-clang"
    export "CXX_${env_triple}=$toolchain/bin/${triple}${api}-clang++"
    export "AR_${env_triple}=$toolchain/bin/llvm-ar"
    export "BINDGEN_EXTRA_CLANG_ARGS_${env_triple}=--target=${triple}${api} --sysroot=$toolchain/sysroot"

    # Inputs to kernel/scripts/ndk/android.toolchain.cmake (trap 1).
    export GILUNE_ANDROID_NDK="$ndk"
    export GILUNE_ANDROID_ABI="$abi"
    export GILUNE_ANDROID_PLATFORM="android-$api"
    export CMAKE_TOOLCHAIN_FILE="$repo/kernel/scripts/ndk/android.toolchain.cmake"
    # whisper.cpp predates cmake 4's minimum supported policy version.
    export CMAKE_POLICY_VERSION_MINIMUM=3.5
    # Defining these makes the cmake crate skip its own flag block; the
    # NDK toolchain file supplies target and sysroot flags.
    export CMAKE_C_FLAGS='-fPIC -w'
    export CMAKE_CXX_FLAGS='-fPIC -w'

    cargo build \
      --manifest-path "$repo/kernel/Cargo.toml" \
      -p gilune-ffi --release --target "$triple" --features whisper
  )
done

# ------------------------------------------------------------------ stage

for abi in "${abis[@]}"; do
  triple=$(triple_of "$abi")
  dest="$repo/app/composeApp/src/androidMain/jniLibs/$abi"
  mkdir -p "$dest"
  cp "$target_root/$abi/$triple/release/libgilune_ffi.so" "$dest/libgilune_ffi.so"

  # Trap 3. The NDK ships the STL unstripped (~9 MB of debug info); AGP
  # would strip it at packaging time, but stripping here keeps the staged
  # tree the same size as the one a developer carries locally.
  cp "$toolchain/sysroot/usr/lib/$triple/libc++_shared.so" "$dest/libc++_shared.so"
  "$toolchain/bin/llvm-strip" --strip-unneeded "$dest/libc++_shared.so"
done

# --------------------------------------------------------------- bindings

# Generated from the Android binary, never the desktop one: UniFFI embeds
# checksums, and reusing the wrong bindings gives a library that loads and
# then fails every call (architecture/07-compatibility.md). --library mode
# reads the metadata out of the ELF file, so the host never loads it and
# either ABI would do; x86_64 is named so the source is not incidental.
source_lib="$target_root/x86_64/x86_64-linux-android/release/libgilune_ffi.so"
[ -f "$source_lib" ] || source_lib="$target_root/${abis[0]}/$(triple_of "${abis[0]}")/release/libgilune_ffi.so"

echo
echo "Generating Kotlin bindings from $source_lib"
# uniffi-bindgen shells out to `cargo metadata` against the CURRENT
# DIRECTORY, not --manifest-path, so it must run from inside the workspace.
(
  cd "$repo/kernel"
  cargo run -p gilune-ffi --bin uniffi-bindgen -- generate \
    --library "$source_lib" --language kotlin \
    --out-dir "$repo/app/composeApp/src/androidMain/generated" --no-format
)

# ----------------------------------------------------------------- report

echo
echo 'Staged (ADR 0001 budget: < 15 MB per ABI):'
for abi in "${abis[@]}"; do
  ls -l "$repo/app/composeApp/src/androidMain/jniLibs/$abi" |
    awk -v abi="$abi" '/\.so$/ { printf "%-12s %-20s %8.2f MB\n", abi, $9, $5 / 1048576 }'
done
