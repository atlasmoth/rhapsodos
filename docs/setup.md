# Setup

Clone the repository and run the commands below from its root directory. The apps are **Rhapsodos**, with application/bundle ID `com.osuji.ereaderapp`, version `0.01`, build `1`.

## Requirements

| Component | Development requirement |
| --- | --- |
| Java | JDK 17 for Gradle, including the shared build invoked by Xcode |
| Android tools | Android Studio or Android SDK command-line tools; SDK Platform `37.1` for the app and `37.0` for shared Android code |
| Android native tools | NDK `27.1.12297006` and CMake `3.22.1` |
| Android run target | API 28 or newer, ARM64 or x86_64; allocate at least 4 GB RAM to a speech-testing emulator |
| iOS tools | Apple Silicon Mac with Xcode 27.x; the recorded toolchain is Xcode 27.1 (`27A9275`) |
| iOS run target | ARM64 iPhone/iPad simulator, or a physical device running iOS 18 or newer |

The Android SDK versions are build requirements; Android API 28 and iOS 18 are the app minimums. Speech packs also enforce their memory and runtime requirements.

Use the checked-in Gradle wrapper and Xcode project. Speech-runtime binaries are already checked in; normal app builds do not require Rust or rebuilding the vendor frameworks. Initial dependency resolution and model downloads need an internet connection.

Set `JAVA_HOME` to JDK 17 and select JDK 17 as Android Studio's Gradle JDK. On macOS with JDK 17 installed:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
java -version
```

## Android

Open the repository root in Android Studio. Its SDK Manager can install the required platforms, NDK and CMake. With `sdkmanager` available, the equivalent package installation is:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" \
  "platforms;android-37.1" "ndk;27.1.12297006" "cmake;3.22.1"
```

Configure the SDK location through `ANDROID_HOME` or the ignored root `local.properties` file that Android Studio creates. Accept the required SDK licenses during setup.

Start a supported emulator, or connect a device with USB debugging enabled. Select the `androidApp` run configuration in Android Studio, or build and install from the terminal:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
adb shell am start -n com.osuji.ereaderapp/.MainActivity
```

The APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Keep one target connected for these commands; use Android Studio's device selector when working with multiple targets.

## iOS

Open the checked-in project:

```sh
open iosApp/EReader.xcodeproj
```

Select the **EReader** scheme and an ARM64 iPhone or iPad simulator, then Run. Let Xcode resolve the pinned Swift packages. The **Build SharedCore** phase invokes Gradle automatically, including catalogue configuration generation; keep JDK 17 available to that build environment.

For a physical device, select your development team under **Signing & Capabilities**, configure the required provisioning, and select the connected device. Distribution signing remains your responsibility.

An equivalent simulator build from the terminal uses the established signing and Swift-module settings:

```sh
xcodebuild -project iosApp/EReader.xcodeproj -scheme EReader \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/build \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- \
  SWIFT_ENABLE_EXPLICIT_MODULES=NO build
```

With one simulator booted, install and launch that product:

```sh
xcrun simctl install booted iosApp/build/Build/Products/Debug-iphonesimulator/EReader.app
xcrun simctl launch booted com.osuji.ereaderapp
```

## Optional catalogue configuration

A fresh clone works with the bundled catalogue and its explicit model downloads. Remote catalogue refresh is disabled until you configure both public settings in the build environment or an ignored root `.env`:

```dotenv
RHAPSODOS_CATALOG_URL=
RHAPSODOS_CATALOG_PUBLIC_KEY=
```

Use `.env.example` as a starting point. Environment values override `.env` independently for each setting, including explicit empty values. An empty URL disables refresh; a configured URL requires a valid Base64 uncompressed P-256 public key. Both apps receive the same generated configuration during their normal builds.

Each publisher owns their signing keypair and signed catalogue. There is no default build trust key. Keep the private key outside the repository and app configuration. Follow [release setup](model-manager.md#release-setup) for signing/configuration and [model archives](model-archives.md) for preparing and hosting compatible packs.

Follow [First listen](../README.md#first-listen) to download voices and transcription, import a PDF, and record a passage-linked note.

### Catalogue tools

Packaging and signing use Python 3.11+ and the OpenSSL CLI. The examples use a Conda environment named `dl`. With Conda installed, create it once if it does not already exist:

```sh
conda create -n dl python=3.12 openssl=3
```

## Checks and troubleshooting

Use Python 3.11 or newer for the repository's Python tools. No third-party Python packages are required. From a configured development environment:

```sh
tools/verify.sh android
tools/verify.sh ios
```

These commands build the apps and inspect their metadata and repository policy. Device audio, microphone capture and sustained performance still need device checks.

- **Gradle cannot find Java or uses the wrong version:** check `java -version`, `JAVA_HOME`, and Android Studio's Gradle JDK. Xcode's build phase also needs JDK 17.
- **SDK, NDK or CMake missing:** confirm the package IDs above and the SDK location in `ANDROID_HOME` or `local.properties`.
- **Xcode reports explicit-module or SharedCore import errors:** use `SWIFT_ENABLE_EXPLICIT_MODULES=NO` as above, or set the equivalent target build setting, and retain the normal Gradle build phase.

Architecture and contribution rules are in [ARCHITECTURE.md](../ARCHITECTURE.md) and [AGENTS.md](../AGENTS.md). UI expectations and dependency notices are in [design](design.md) and [third-party notices](third-party.md).
