#!/usr/bin/env bash
set -euo pipefail

ereader_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ereader_mode="${1:-all}"

case "$ereader_mode" in
  all|android|ios|policy) ;;
  --help|-h)
    printf '%s\n' 'Usage: tools/verify.sh [all|android|ios|policy]' 'Builds real app artifacts and checks version 0.01/build 1, app identifiers and OS floors.' 'ANDROID_APK_PATH and EREADER_DERIVED_DATA can override artifact locations.' 'Device audio, recording recovery and model endurance remain separate manual gates.'
    exit 0
    ;;
  *) printf 'Unknown verification mode: %s\n' "$ereader_mode" >&2; exit 2 ;;
esac

python3 "$ereader_root/tools/check-workspace.py"
if [[ "$ereader_mode" == policy ]]; then
  exit 0
fi

if [[ ! -f "$ereader_root/gradlew" ]]; then
  printf '%s\n' 'Gradle wrapper is missing. Complete the root build setup before compiling.' >&2
  exit 1
fi

if [[ "$ereader_mode" == all || "$ereader_mode" == android ]]; then
  bash "$ereader_root/gradlew" -p "$ereader_root" --console=plain --no-daemon :androidApp:assembleDebug :androidApp:lintDebug
  ereader_apk="${ANDROID_APK_PATH:-$ereader_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
  python3 "$ereader_root/tools/check-workspace.py" --apk "$ereader_apk"
fi

if [[ "$ereader_mode" == all || "$ereader_mode" == ios ]]; then
  command -v xcodebuild >/dev/null
  bash "$ereader_root/gradlew" -p "$ereader_root" --console=plain --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
  ereader_derived_data="${EREADER_DERIVED_DATA:-$(mktemp -d "${TMPDIR:-/tmp}/ereader-verify.XXXXXX")}"
  xcodebuild -project "$ereader_root/iosApp/EReader.xcodeproj" -scheme EReader -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath "$ereader_derived_data" CODE_SIGNING_ALLOWED=NO build
  python3 "$ereader_root/tools/check-workspace.py" --ios-app "$ereader_derived_data/Build/Products/Debug-iphonesimulator/EReader.app"
  printf 'iOS build artifacts: %s\n' "$ereader_derived_data"
fi
