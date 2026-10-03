#!/usr/bin/env bash
# Build the Windows MSVC kernel on Linux. Keep host generator flags isolated.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
[[ $(uname -s) == Linux ]] || { echo 'This script requires a Linux host.' >&2; exit 1; }
target=x86_64-pc-windows-msvc
# Pin the Microsoft headers as well as Rust/LLVM: newer STL headers can reject
# an otherwise unchanged compiler. Limit SDK downloads to the shipped target.
export XWIN_ARCH=x86_64
export XWIN_VERSION=17
# xwin selects the manifest ID, not the 14.44.35220 header build number.
export XWIN_CRT_VERSION=14.44.17.14
export XWIN_SDK_VERSION=10.0.26100
# Ship a self-contained kernel instead of relying on a machine-wide VC++
# redistributable. Target-scoped flags also leave the Linux generator alone.
export CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_RUSTFLAGS='-C target-feature=+crt-static -l advapi32'
build_root=${GILUNE_WINDOWS_BUILD_ROOT:-$repo/kernel/target/windows-cross}
recipe_stamp="$build_root.recipe.sha256"
output=${GILUNE_WINDOWS_OUTPUT:-$repo/app/build/windows-cross/native}
mkdir -p "$build_root" "$output"

(
  # whisper-rs-sys 0.15.0 incorrectly uses the host OS for these Windows
  # settings. Supply real target libraries; never the Android stub archive.
  export CARGO_TARGET_DIR="$build_root"
  export CMAKE_CXX_FLAGS='/utf-8'
  export CMAKE_POLICY_DEFAULT_CMP0091=NEW
  export CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
  # Also replace legacy cached /MD flags when upgrading an existing build.
  export CMAKE_C_FLAGS_RELEASE='/O2 /Ob2 /DNDEBUG'
  export CMAKE_CXX_FLAGS_RELEASE='/O2 /Ob2 /DNDEBUG'
  export CMAKE_POLICY_VERSION_MINIMUM=3.5
  export GGML_NATIVE=OFF
  # whisper-rs-sys forwards CMake variables but does not declare them as Cargo
  # inputs. Invalidate that dependency when this build recipe changes.
  recipe=$(sha256sum "${BASH_SOURCE[0]}" | cut -d' ' -f1)
  # Keep the stamp outside this target: rust-cache prunes its loose files.
  if [[ ! -f "$recipe_stamp" ]] || \
      [[ $(cat "$recipe_stamp") != "$recipe" ]]; then
    cargo clean --manifest-path "$repo/kernel/Cargo.toml" -p whisper-rs-sys \
      --release --target "$target"
  fi
  cargo xwin build --locked --release --target "$target" \
    --manifest-path "$repo/kernel/Cargo.toml" -p gilune-ffi --lib --features whisper
  printf '%s\n' "$recipe" > "$recipe_stamp"
)

cp "$build_root/$target/release/gilune_ffi.dll" "$output/gilune_ffi.dll"
(
  cd "$repo/kernel"
  cargo run --locked -p gilune-ffi --bin uniffi-bindgen -- generate \
    --library "$output/gilune_ffi.dll" --language kotlin \
    --out-dir "$output/bindings" --no-format
)
test -s "$output/bindings/uniffi/gilune_ffi/gilune_ffi.kt"
sha256sum "$output/gilune_ffi.dll" "$output/bindings/uniffi/gilune_ffi/gilune_ffi.kt"

# Compile every unit/integration executable with the same Windows toolchain.
# Execution remains a Windows gate; no Wine approximation of filesystem/UI.
test_messages="$build_root/windows-tests.jsonl"
CARGO_TARGET_DIR="$build_root" cargo xwin test --locked --workspace --no-run \
  --target "$target" --manifest-path "$repo/kernel/Cargo.toml" \
  --message-format=json > "$test_messages"
python3 "$repo/.github/scripts/export-rust-tests.py" --messages "$test_messages" \
  --repo "$repo" --output "$(dirname "$output")/rust-tests"
