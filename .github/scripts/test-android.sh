#!/usr/bin/env bash
# Three independent instrumentation processes, one APK build. Each test runs
# in exactly one shard; a failed shard is retained and is never retried.
set -euo pipefail
mkdir -p build/android-shards
status=0
adb shell getprop ro.build.fingerprint
adb shell cat /proc/meminfo | head -8
for shard in 0 1 2; do
  destination="build/android-shards/shard-$shard"
  mkdir -p "$destination"
  if ! ./gradlew :composeApp:connectedDebugAndroidTest \
      -Pgilune.kernel.profile=release \
      -Pandroid.testInstrumentationRunnerArguments.numShards=3 \
      "-Pandroid.testInstrumentationRunnerArguments.shardIndex=$shard" "$@"; then
    status=1
  fi
  # Preserve each shard before Gradle prepares the next run's output directory.
  for result in composeApp/build/outputs/androidTest-results/connected composeApp/build/reports/androidTests/connected; do
    if [ -d "$result" ]; then
      cp -R "$result" "$destination/$(basename "$(dirname "$result")")"
    fi
  done
  adb logcat -d -v threadtime > "$destination/device.log" || true
done
exit "$status"
