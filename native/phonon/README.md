# Phonon native runtime

Rhapsodos uses the C ABI from [xn-ptts v0.4.0](https://github.com/gradium-ai/xn-ptts/tree/f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa), commit `f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa`. Android executes the CPU implementation; Apple executes Core ML. Model weights are downloadable assets and are not part of these libraries.

`build.sh` downloads that exact source archive, verifies its SHA-256 and lockfile, applies the one local patch and builds the libraries. The repository contains the header, dependency lockfile, patch, licenses and generated native binaries; upstream source and tests remain outside the repository.

## Rebuild

Use an Apple Silicon Mac, Rust **1.99.1**, Android NDK **27.1.12297006**, and Xcode **27.1 (27A9275)**. The checked-in artifacts were built with those versions. Android uses API 28, generic ARM64/x86-64 instructions and 16 KiB ELF segment alignment. Apple uses an iOS 18 deployment target and macOS 14 for the host qualification slice. Rust's precompiled standard-library objects can declare an older minimum OS within the static archive.

Set `CARGO_HOME`, `RUSTUP_HOME` and `PATH` to the chosen isolated Rust installation. Then:

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.1.12297006"
export PHONON_BUILD_ROOT="$(mktemp -d /tmp/rhapsodos-phonon-build.XXXXXX)"
native/phonon/build.sh all
conda run -n dl python native/phonon/generate_notices.py \
  --source "$(cat "$PHONON_BUILD_ROOT/last-source.txt")"
```

`android` and `apple` are also valid build arguments. The script installs the required Rust target standard libraries into that Rust installation. It requires network access to the pinned public source and Cargo dependencies; the resulting inference library does not download models or upload input. The application owns downloads and validation.

Outputs:

- `androidApp/src/main/jniLibs/{arm64-v8a,x86_64}/libptts_ffi.so`
- `iosApp/Vendor/PhononCore.xcframework`, containing device ARM64, simulator ARM64 and host macOS ARM64 static libraries with module `PhononCore`.

`provenance.json` records source, toolchain, binary sizes and SHA-256 values for the reviewed build. Rebuilding with a different SDK or toolchain requires renewed validation and a new manifest; the recipe does not claim cross-toolchain byte identity.

## Completion and cancellation contract

`patches/require-eos.patch` changes both CPU synthesis paths and the Core ML driver to return an error when their frame limit is reached without detecting the model's end-of-speech signal. Previously that condition could return success with incomplete audio. The C ABI remains unchanged. The stable error is:

```
speech did not reach EOS before the frame limit
```

Audio callbacks can precede an error. Callers must buffer an entire requested passage and commit/play it only after a successful, non-cancelled result. On this specific error the application may retry smaller source-preserving chunks; it must not keep the partial attempt or silently drop source text. EOS detection is a completion guard, not proof that every word was pronounced correctly.

A callback returning `false` cancels generation. Upstream returns the partial result normally; application cancellation state remains authoritative and that partial result must be discarded. One handle has one serialized owner; do not synthesize, change the voice or free it concurrently. C errors are borrowed strings and must be read before another failure on the same handle.

## Licenses and diagnostics

The runtime is MIT OR Apache-2.0. `THIRD_PARTY_NOTICES.txt` contains the normal dependency closure for all five compiled targets (148 packages), plus Rust standard-library and compiler-builtins notices. `dependency-licenses.json` records versions, locked checksums, target membership, attribution and license-text hashes. Missing crate-packaged license texts are supplemented from pinned upstream revisions in `license-sources.json`; those small source texts are retained in `licenses/`. objc2's upstream licensing explanation is supplemented with its declared authors and the selected MIT terms. Ship the notices in both apps. Model and voice licenses remain separate.

The compiled C API does not initialize a tracing subscriber or print prompts. The unlinked CLI contains progress printing. Runtime tracing sites concern public model assets, language policy and voice metadata; app adapters map runtime failures to generic user-facing errors without logging raw input or error strings. A panic remains an exceptional Rust failure, not an ordinary diagnostic path.

## Validation of this build

External integration harnesses on 2026-10-21 exercised the final patched ARM64 Android library on an API 37 ARM64 emulator with 16 KiB pages and the final macOS Core ML CPU slice. Both completed the same ten public synthetic corpus texts, produced nonempty finite PCM, cancelled after the first callback, then synthesized successfully on the same handle. Raising the fixture's EOS threshold to `1e9` forced a frame-limit failure: both returned `false` with the exact error above instead of accepting the partial audio. Fixture modifications and harness sources remained outside the repository.

All Android ELF LOAD segments have `0x4000` alignment. The iOS device objects declare minimum 18.0; simulator objects declare 14.0 or 18.0. The other app integration checks are recorded with the platform implementation. x86-64 was compiled and structurally inspected, but was not executed. These checks do not qualify battery, thermals, sustained playback or physical low-memory phones. Physical-device qualification remains required.
