#!/usr/bin/env bash
# User-space tools for Linux-hosted Windows packaging. System prerequisites:
# curl, tar, unzip, Java 17, clang/lld/LLVM 19, CMake, Ninja, libclang, wixl.
set -euo pipefail
tools=${GILUNE_WINDOWS_TOOLS:-${RUNNER_TEMP:?Set RUNNER_TEMP or GILUNE_WINDOWS_TOOLS}/gilune-windows-tools}
mkdir -p "$tools/bin" "$tools/downloads" "$tools/runtime"
tools=$(cd "$tools" && pwd)
fetch() {
  local url=$1 file=$2 checksum=$3
  if ! echo "$checksum  $file" | sha256sum --check --status 2>/dev/null; then
    curl --fail --location --silent --show-error --retry 2 "$url" -o "$file"
    echo "$checksum  $file" | sha256sum --check
  fi
}
fetch https://github.com/rust-cross/cargo-xwin/releases/download/v0.23.1/cargo-xwin-v0.23.1.x86_64-unknown-linux-musl.tar.gz \
  "$tools/downloads/cargo-xwin.tar.gz" c492c6dfb7e5ac0eee586b796e0fc950ba13077e7f0bbb046f445d71790d5360
tar -xzf "$tools/downloads/cargo-xwin.tar.gz" -C "$tools/bin"
fetch 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip' \
  "$tools/downloads/windows-jre.zip" bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352
unzip -qo "$tools/downloads/windows-jre.zip" -d "$tools/runtime"
if [[ -n ${GITHUB_PATH:-} ]]; then echo "$tools/bin" >> "$GITHUB_PATH"; fi
if [[ -n ${GITHUB_ENV:-} ]]; then
  echo "GILUNE_WINDOWS_TOOLS=$tools" >> "$GITHUB_ENV"
  echo "WINDOWS_JRE_HOME=$tools/runtime/jdk-17.0.20.1+1-jre" >> "$GITHUB_ENV"
fi
echo "Tools: $tools"
