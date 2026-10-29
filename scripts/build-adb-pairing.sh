#!/bin/bash
# Builds libadbpairing.so (app/src/main/cpp/adb_pairing.cpp) for the ABIs the app ships and puts
# the result under app/src/main/jniLibs. Run by hand when the source changes; Gradle does not.
#
#   NDK=/path/to/ndk BORINGSSL=/path/to/unzipped/boringssl-aar scripts/build-adb-pairing.sh
#
# BORINGSSL is the unzipped prefab AAR io.github.vvb2060.ndk:boringssl (static libcrypto + headers).
set -euo pipefail

: "${NDK:?set NDK to the Android NDK directory}"
: "${BORINGSSL:?set BORINGSSL to the unzipped boringssl prefab AAR}"

root="$(cd "$(dirname "$0")/.." && pwd)"
module="$BORINGSSL/prefab/modules/crypto_static"
bin="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
suffix=""
[ -f "$bin/clang++.exe" ] && suffix=".cmd"

build() {
    local abi="$1" triple="$2"
    local out="$root/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    "$bin/${triple}33-clang++$suffix" \
        -shared -fPIC -O2 -std=c++17 -fvisibility=hidden -ffunction-sections -fdata-sections \
        -fno-exceptions -fno-rtti \
        -I "$module/include" \
        "$root/app/src/main/cpp/adb_pairing.cpp" \
        "$module/libs/android.$abi/libcrypto_static.a" \
        -llog -static-libstdc++ \
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--strip-all \
        -o "$out/libadbpairing.so"
    ls -l "$out/libadbpairing.so"
}

build arm64-v8a aarch64-linux-android
build x86_64 x86_64-linux-android
