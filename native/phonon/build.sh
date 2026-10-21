#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PLATFORM="${1:-all}"
REVISION=f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa
ARCHIVE_SHA256=fc99775ae12cbd49e3aa7de279b4bff342dd6ac2776feb415ed70db53068fd27
BUILD_ROOT="${PHONON_BUILD_ROOT:-$(mktemp -d "${TMPDIR:-/tmp}/rhapsodos-phonon.XXXXXX")}"

case "$PLATFORM" in all|android|apple) ;; *) echo "Use all, android or apple" >&2; exit 1 ;; esac
[[ "$(rustc --version)" == "rustc 1.99.1 (58cf8f9c3 2026-10-13)" ]] || { echo "Use Rust 1.99.1" >&2; exit 1; }
mkdir -p "$BUILD_ROOT"
BUILD_ROOT="$(cd "$BUILD_ROOT" && pwd)"
case "$BUILD_ROOT/" in "$ROOT/"*) echo "Keep build files outside the repository" >&2; exit 1 ;; esac
ARCHIVE="$BUILD_ROOT/source.tar.gz"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 "https://codeload.github.com/gradium-ai/xn-ptts/tar.gz/$REVISION" -o "$ARCHIVE"
fi
[[ "$(shasum -a 256 "$ARCHIVE" | cut -d ' ' -f 1)" == "$ARCHIVE_SHA256" ]] || { echo "Source checksum mismatch" >&2; exit 1; }
SOURCE="$(mktemp -d "$BUILD_ROOT/source.XXXXXX")"
tar -xzf "$ARCHIVE" -C "$SOURCE" --strip-components=1
cmp "$SOURCE/Cargo.lock" "$HERE/Cargo.lock"
patch -d "$SOURCE" -p1 < "$HERE/patches/require-eos.patch"
export CARGO_TARGET_DIR="$BUILD_ROOT/target"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS
cd "$SOURCE"

if [[ "$PLATFORM" != apple ]]; then
    NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 27.1.12297006}"
    [[ "$(sed -n 's/^Pkg.Revision = //p' "$NDK/source.properties")" == 27.1.12297006 ]] || { echo "Use Android NDK 27.1.12297006" >&2; exit 1; }
    NDK_BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android28-clang"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android28-clang"
    export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
    export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
    export AR_aarch64_linux_android="$NDK_BIN/llvm-ar"
    export AR_x86_64_linux_android="$NDK_BIN/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C strip=symbols -C target-cpu=generic -C link-arg=-Wl,-z,max-page-size=16384'
    export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS='-C strip=symbols -C target-cpu=x86-64 -C link-arg=-Wl,-z,max-page-size=16384'
    rustup target add aarch64-linux-android x86_64-linux-android
    for target in aarch64-linux-android x86_64-linux-android; do
        cargo build --locked --release -p ptts-ffi --target "$target"
    done
    mkdir -p "$ROOT/androidApp/src/main/jniLibs/arm64-v8a" "$ROOT/androidApp/src/main/jniLibs/x86_64"
    cp "$CARGO_TARGET_DIR/aarch64-linux-android/release/libptts_ffi.so" "$ROOT/androidApp/src/main/jniLibs/arm64-v8a/"
    cp "$CARGO_TARGET_DIR/x86_64-linux-android/release/libptts_ffi.so" "$ROOT/androidApp/src/main/jniLibs/x86_64/"
fi

if [[ "$PLATFORM" != android ]]; then
    export IPHONEOS_DEPLOYMENT_TARGET=18.0
    export MACOSX_DEPLOYMENT_TARGET=14.0
    export CARGO_TARGET_AARCH64_APPLE_IOS_RUSTFLAGS='-C strip=debuginfo -C target-cpu=generic'
    export CARGO_TARGET_AARCH64_APPLE_IOS_SIM_RUSTFLAGS='-C strip=debuginfo -C target-cpu=generic'
    export CARGO_TARGET_AARCH64_APPLE_DARWIN_RUSTFLAGS='-C strip=debuginfo -C target-cpu=generic'
    targets=(aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin)
    rustup target add "${targets[@]}"
    for target in "${targets[@]}"; do
        cargo build --locked --release -p ptts-ffi --target "$target"
    done
    mkdir "$SOURCE/headers"
    cp "$HERE/include/ptts.h" "$SOURCE/headers/"
    printf 'module PhononCore {\n    header "ptts.h"\n    export *\n}\n' > "$SOURCE/headers/module.modulemap"
    args=()
    for target in "${targets[@]}"; do
        args+=(-library "$CARGO_TARGET_DIR/$target/release/libptts_ffi.a" -headers "$SOURCE/headers")
    done
    xcodebuild -create-xcframework "${args[@]}" -output "$SOURCE/PhononCore.xcframework"
    mkdir -p "$ROOT/iosApp/Vendor"
    ditto "$SOURCE/PhononCore.xcframework" "$ROOT/iosApp/Vendor/PhononCore.xcframework"
fi
printf '%s\n' "$SOURCE" > "$BUILD_ROOT/last-source.txt"
echo "Built Phonon $REVISION; source: $SOURCE"
