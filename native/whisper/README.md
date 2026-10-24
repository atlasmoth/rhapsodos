# Whisper native runtime

Normal Android app builds link the checked-in CPU libraries; rebuilding the runtime is only needed when changing its source or toolchain. iOS uses its separate WhisperKit/Core ML adapter. Model downloads and verification belong to the app; this library does not download models or upload recordings.

Source: [whisper.cpp v1.9.5](https://github.com/ggml-org/whisper.cpp/tree/d1be6fde11ac6e0407606b4e42fe72d34add8037), commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`. [provenance.json](provenance.json) records source/archive hashes, toolchains and binary hashes. [build.sh](build.sh) checks the pinned archive and retained headers; upstream source, examples and tests stay outside this repository.

## Rebuild

Use an Apple Silicon Mac, Android NDK **27.1.12297006**, Android SDK CMake **3.22.1-g37088a8** and Ninja **1.10.2**. Run from the repository root:

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.1.12297006"
export WHISPER_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
export WHISPER_NINJA="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja"
export WHISPER_BUILD_ROOT="$(mktemp -d /tmp/rhapsodos-whisper-build.XXXXXX)"
native/whisper/build.sh
```

The script fetches the pinned source and writes `libwhisper.so`, `libggml.so`, `libggml-base.so` and `libggml-cpu.so` to `androidApp/src/main/jniLibs/{arm64-v8a,x86_64}/`. Targets are API 28, generic CPUs and 16 KiB ELF alignment. OpenMP, host-specific instructions and optional accelerators are disabled; NDK C++ support is statically linked. Revalidate changed binaries/toolchains and update provenance; compilation alone does not qualify physical-device performance.

## Native ownership

One serialized owner loads, transcribes and frees a context; never free it during inference. Pass bounded PCM windows and check the cancellation generation before accepting output. The native abort callback cancels graph computation; discard partial text from every failed or cancelled window.

With the pinned CPU backend and OpenMP disabled, GGML invokes abort on worker zero, the thread that entered inference. The Android bridge relies on this to use the calling thread's JNI environment. Recheck that invariant before changing backend or enabling OpenMP.

Disable Whisper/GGML logging before loading models and transcript/progress printing in decode parameters. Do not log recordings, paths or transcript text.

## Licenses

Whisper.cpp and GGML use the upstream [MIT license](LICENSE). Ship [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt), including the [Android NDK LLVM runtime notices](LLVM-NOTICE.txt). [license-sources.json](license-sources.json) records exact attribution sources/hashes. OpenAI model weights retain their separate MIT notice in the app; see [third-party.md](../../docs/third-party.md).
