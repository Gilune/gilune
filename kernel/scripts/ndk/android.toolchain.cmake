# Gilune's wrapper around the NDK's own android.toolchain.cmake.
#
# Why this file exists, and why it is named exactly this:
#
#   * whisper-rs-sys's build script forwards environment variables into cmake
#     only when their names begin with CMAKE_, WHISPER_ or GGML_. ANDROID_ABI —
#     the one variable android.toolchain.cmake actually requires — matches none
#     of those, and left unset the NDK file silently defaults to armeabi-v7a
#     and then fails the compiler test with "unknown target CPU 'armv7-a'".
#     This wrapper reads the ABI from the environment instead.
#   * The cmake crate decides it is cross-compiling with the NDK by looking at
#     the *file name* of CMAKE_TOOLCHAIN_FILE (cmake-0.1.58 src/lib.rs, fn
#     uses_android_ndk). Named anything else, it would stop suppressing its own
#     host compiler flags and fight the toolchain file. Hence the directory,
#     not the file name, is what distinguishes this from the NDK's.
#
# The NDK's file remains the authority; this only supplies inputs to it.
#
# Required environment: GILUNE_ANDROID_NDK, GILUNE_ANDROID_ABI,
# GILUNE_ANDROID_PLATFORM. kernel/scripts/build-android.ps1 sets all three.

if(NOT DEFINED ANDROID_ABI)
  set(ANDROID_ABI "$ENV{GILUNE_ANDROID_ABI}")
endif()

if(NOT DEFINED ANDROID_PLATFORM)
  set(ANDROID_PLATFORM "$ENV{GILUNE_ANDROID_PLATFORM}")
endif()

if(ANDROID_ABI STREQUAL "")
  message(FATAL_ERROR "GILUNE_ANDROID_ABI is not set in the environment")
endif()

# Windows hands paths over with backslashes; include() wants cmake separators.
file(TO_CMAKE_PATH "$ENV{GILUNE_ANDROID_NDK}" _gilune_ndk)

if(_gilune_ndk STREQUAL "")
  message(FATAL_ERROR "GILUNE_ANDROID_NDK is not set in the environment")
endif()

include("${_gilune_ndk}/build/cmake/android.toolchain.cmake")
