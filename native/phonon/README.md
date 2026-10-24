# Phonon native runtime

Normal app builds use the checked-in precompiled libraries; rebuilding the runtime is only needed when changing its source or toolchain. Android uses CPU inference and iOS uses Core ML through the C ABI. Models are separate downloads owned and verified by the app; this runtime does not fetch them or upload input.

Source: [xn-ptts v0.4.0](https://github.com/gradium-ai/xn-ptts/tree/f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa), commit `f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa`. [provenance.json](provenance.json) records source/archive hashes, toolchains and binary hashes. [build.sh](build.sh) checks the pinned source and Cargo lockfile, then applies [require-eos.patch](patches/require-eos.patch); upstream source stays outside this repository.

## Rebuild

Use an Apple Silicon Mac, Rust **1.99.1**, Android NDK **27.1.12297006** and Xcode **27.1 (27A9275)**. Set `CARGO_HOME`, `RUSTUP_HOME` and `PATH` to the chosen Rust installation, then run from the repository root:

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.1.12297006"
export PHONON_BUILD_ROOT="$(mktemp -d /tmp/rhapsodos-phonon-build.XXXXXX)"
native/phonon/build.sh all
conda run -n dl python native/phonon/generate_notices.py \
  --source "$(cat "$PHONON_BUILD_ROOT/last-source.txt")"
```

`android` and `apple` can replace `all`. The script installs Rust targets and needs the pinned source/Cargo dependencies. Outputs are `androidApp/src/main/jniLibs/{arm64-v8a,x86_64}/libptts_ffi.so` and `iosApp/Vendor/PhononCore.xcframework` (ARM64 device, simulator and macOS host slices). Targets are Android API 28 with 16 KiB ELF alignment, iOS 18 and macOS 14. Revalidate changed binaries/toolchains and update provenance; compilation alone does not qualify physical-device performance.

## Ownership and completion

One serialized owner controls each handle; never synthesize, change voice or free it concurrently. Read borrowed C error strings before another failure on that handle. App diagnostics must not log prompts or raw runtime error strings.

The patch fails synthesis with `speech did not reach EOS before the frame limit` when no end-of-speech signal arrives. Audio callbacks can precede failure: buffer the entire requested passage and commit/play only a successful, non-cancelled result. On that error, source-preserving smaller chunks may be retried; discard partial attempts and never drop text. EOS is a completion guard, not proof of pronunciation accuracy.

A callback returning `false` cancels generation, but upstream can return partial audio normally. Application cancellation state remains authoritative; discard that result.

## Licenses

The runtime is [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE). Ship [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) in both apps; it includes the locked dependency closure, Rust standard library and compiler-builtins notices. [dependency-licenses.json](dependency-licenses.json), [license-sources.json](license-sources.json) and `licenses/` retain exact attribution and supplemental texts, including objc2's authors and MIT terms. Model and voice licensing is separate; see [third-party.md](../../docs/third-party.md).
