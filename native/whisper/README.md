# Whisper native runtime

Android uses the local CPU C API from [whisper.cpp v1.9.5](https://github.com/ggml-org/whisper.cpp/tree/d1be6fde11ac6e0407606b4e42fe72d34add8037), commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`. Model weights remain separately downloaded assets. The library does not download models or upload recordings.

`build.sh` fetches the exact source archive, verifies its SHA-256, checks the retained public headers and builds the Whisper target with its GGML CPU dependencies. Upstream source, examples and tests stay outside the repository. Only the four required shared libraries are packaged; Parakeet and other inference backends are excluded.

## Rebuild

Use an Apple Silicon Mac, Android NDK **27.1.12297006**, CMake **3.22.1** and Ninja **1.10.2**:

```sh
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.1.12297006"
export WHISPER_CMAKE="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
export WHISPER_NINJA="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja"
export WHISPER_BUILD_ROOT="$(mktemp -d /tmp/rhapsodos-whisper-build.XXXXXX)"
native/whisper/build.sh
```

The runtime targets API 28, generic ARM64/x86-64 CPUs and 16 KiB ELF segment alignment. OpenMP, host-specific instructions, optional accelerators and downloaded kernel dependencies are disabled. C++ standard-library support is statically linked by the NDK. The build requires network only for the pinned source archive. Artifact hashes and toolchain details are recorded in `provenance.json`; another toolchain requires renewed validation.

## Native ownership

One serialized owner loads, transcribes and frees a context. Never free a context during inference. The app passes bounded PCM windows and checks its cancellation generation before accepting output. A native abort callback cancels graph computation; partial text from any cancelled or failed window must be discarded.

With the pinned CPU backend and OpenMP disabled, GGML invokes its abort callback on worker zero, the thread that entered inference. This permits the Android bridge to check its coroutine cancellation callback through the calling thread's JNI environment. Changing the backend or enabling OpenMP requires rechecking that invariant.

The app disables Whisper and GGML logging before loading a model and disables transcript/progress printing in decode parameters. Do not enable ordinary logging of recordings, paths or transcript text.

## Licenses

Whisper.cpp and GGML use the upstream MIT license. `THIRD_PARTY_NOTICES.txt` includes that license and the Android NDK's LLVM runtime notices. OpenAI model weights retain their separate MIT notice in the app. `license-sources.json` records the exact sources and hashes. Native library execution on an emulator does not qualify physical-device speed, battery, thermal behavior or low-memory reliability.
