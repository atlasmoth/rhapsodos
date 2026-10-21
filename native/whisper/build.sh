#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
REVISION=d1be6fde11ac6e0407606b4e42fe72d34add8037
ARCHIVE_SHA256=645445da884df6dbd52b30f82d7be7c01d1767c938714e477e786b88d529e9f4
BUILD_ROOT="${WHISPER_BUILD_ROOT:-$(mktemp -d "${TMPDIR:-/tmp}/rhapsodos-whisper.XXXXXX")}"
NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 27.1.12297006}"
CMAKE="${WHISPER_CMAKE:?Set WHISPER_CMAKE to CMake 3.22.1}"
NINJA="${WHISPER_NINJA:?Set WHISPER_NINJA to Ninja 1.10.2}"

[[ "$(sed -n 's/^Pkg.Revision = //p' "$NDK/source.properties")" == 27.1.12297006 ]] || { echo "Use Android NDK 27.1.12297006" >&2; exit 1; }
[[ "$("$CMAKE" --version | head -1)" == "cmake version 3.22.1-g37088a8" ]] || { echo "Use Android SDK CMake 3.22.1-g37088a8" >&2; exit 1; }
[[ "$("$NINJA" --version)" == 1.10.2 ]] || { echo "Use Ninja 1.10.2" >&2; exit 1; }
mkdir -p "$BUILD_ROOT"
BUILD_ROOT="$(cd "$BUILD_ROOT" && pwd)"
case "$BUILD_ROOT/" in "$ROOT/"*) echo "Keep build files outside the repository" >&2; exit 1 ;; esac
ARCHIVE="$BUILD_ROOT/source.tar.gz"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --proto '=https' --tlsv1.2 "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/$REVISION" -o "$ARCHIVE"
fi
[[ "$(shasum -a 256 "$ARCHIVE" | cut -d ' ' -f 1)" == "$ARCHIVE_SHA256" ]] || { echo "Source checksum mismatch" >&2; exit 1; }
SOURCE="$(mktemp -d "$BUILD_ROOT/source.XXXXXX")"
tar -xzf "$ARCHIVE" -C "$SOURCE" --strip-components=1
cmp "$SOURCE/include/whisper.h" "$HERE/include/whisper.h"
for header in ggml.h ggml-cpu.h ggml-backend.h ggml-alloc.h; do
    cmp "$SOURCE/ggml/include/$header" "$HERE/include/$header"
done

for ABI in arm64-v8a x86_64; do
    TARGET="$BUILD_ROOT/$ABI"
    "$CMAKE" -S "$SOURCE" -B "$TARGET" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-28 \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=ON -DWHISPER_BUILD_IS_DEV=OFF \
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_SERVER=OFF \
        -DWHISPER_BUILD_NUMBER=1 -DWHISPER_BUILD_COMMIT="$REVISION" \
        -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF \
        -DGGML_ACCELERATE=OFF -DGGML_LLAMAFILE=OFF -DGGML_CPU_KLEIDIAI=OFF
    "$CMAKE" --build "$TARGET" --target whisper -j "${WHISPER_BUILD_JOBS:-4}"
    OUTPUT="$ROOT/androidApp/src/main/jniLibs/$ABI"
    mkdir -p "$OUTPUT"
    for library in libwhisper.so libggml.so libggml-base.so libggml-cpu.so; do
        cp "$TARGET/bin/$library" "$OUTPUT/$library"
        "$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" --strip-unneeded "$OUTPUT/$library"
    done
done
printf '%s\n' "$SOURCE" > "$BUILD_ROOT/last-source.txt"
echo "Built Whisper $REVISION; source: $SOURCE"
