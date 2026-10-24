#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import struct
import subprocess
import sys
import zipfile
from model_https import https_url


IGNORED = {".git", ".gradle", ".kotlin", ".idea", ".swiftpm", "build", "DerivedData", "Pods", "node_modules", ".venv", "__pycache__"}
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".swift", ".py", ".pyi", ".m", ".mm", ".h", ".hh", ".hpp", ".hxx", ".cpp", ".cc", ".c", ".cxx", ".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts", ".sh", ".sql", ".sq", ".cs", ".fs", ".fsx", ".go", ".rs", ".dart", ".rb", ".scala", ".groovy"}
UNIT_DIRECTORIES = {"test", "tests", "unittest", "unittests", "unit_test", "unit_tests", "unit-test", "unit-tests"}
INTEGRATION_DIRECTORIES = {"androidTest", "androidInstrumentedTest", "androidDeviceTest", "integrationTest", "integrationTests", "integration_test", "integration_tests", "UITests", "ui_test", "ui_tests", "e2e"}


def integration_directory(name):
    return name in INTEGRATION_DIRECTORIES or re.search(r"(?:(?:UI|Ui)|Integration|E2E)Tests?$", name) is not None


def unit_directory(name):
    return name.lower() in UNIT_DIRECTORIES or re.search(r"(?:UnitTests?|Tests?)$", name) is not None


def unit_filename(stem):
    return re.search(r"(?:UnitTests?|Tests?|Specs?)$", stem) is not None or re.match(r"(?:UnitTests?|Tests?|Specs?)[A-Z_]", stem) is not None or re.search(r"(?:^|[._-])(?:unit[._-]?)?(?:tests?|specs?)(?:$|[._-])", stem, re.IGNORECASE) is not None


def unit_sources(root):
    found = []
    for directory, children, files in os.walk(root):
        children[:] = sorted(child for child in children if child not in IGNORED)
        for name in sorted(files):
            path = Path(directory) / name
            if path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            relative = path.relative_to(root)
            if any(integration_directory(part) for part in relative.parts[:-1]) or re.search(r"(?:UI|Ui)Tests?$", path.stem):
                continue
            if any(unit_directory(part) for part in relative.parts[:-1]) or unit_filename(path.stem):
                found.append(str(relative))
    return found


def sdk_directory():
    return Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")


def android_metadata(path, args):
    candidates = list((sdk_directory() / "build-tools").glob("*/aapt"))
    if not candidates:
        raise ValueError("No installed Android build-tools aapt found; set ANDROID_HOME or ANDROID_SDK_ROOT")
    aapt = max(candidates, key=lambda item: tuple(int(value) for value in re.findall(r"\d+", item.parent.name)))
    result = subprocess.run([str(aapt), "dump", "badging", str(path)], check=True, capture_output=True, text=True)
    package_line = next(line for line in result.stdout.splitlines() if line.startswith("package:"))
    package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    minimum = re.search(r"^sdkVersion:'([^']+)'", result.stdout, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'([^']+)'", result.stdout, re.MULTILINE)
    permissions = set(re.findall(r"^uses-permission[^:]*: name='([^']+)'", result.stdout, re.MULTILINE))
    label = re.search(r"^application-label:'([^']+)'", result.stdout, re.MULTILINE)
    metadata = {"application_id": package.get("name"), "display_name": label.group(1) if label else None, "version": package.get("versionName"), "build": package.get("versionCode"), "min_sdk": minimum.group(1) if minimum else None, "target_sdk": target.group(1) if target else None, "internet_permission": "android.permission.INTERNET" in permissions}
    expected = {"application_id": args.application_id, "display_name": args.display_name, "version": args.version, "build": args.build, "min_sdk": str(args.android_min), "target_sdk": str(args.android_target), "internet_permission": True}
    require_metadata("Android APK", metadata, expected)
    metadata["arm64_native_16k_alignment"] = native_alignment(path)
    return metadata


def native_alignment(path):
    count = 0
    with path.open("rb") as handle, zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith("lib/arm64-v8a/") or not entry.filename.endswith(".so"):
                continue
            count += 1
            handle.seek(entry.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", handle.read(4))
            data_offset = entry.header_offset + 30 + name_length + extra_length
            if entry.compress_type != zipfile.ZIP_STORED or data_offset % 16_384:
                raise ValueError("An ARM64 library is not packaged for 16 KB pages: " + entry.filename)
            data = archive.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError("Unexpected ARM64 library format: " + entry.filename)
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, number = struct.unpack_from("<HH", data, 54)
            alignments = [struct.unpack_from("<Q", data, offset + index * size + 48)[0]
                          for index in range(number) if struct.unpack_from("<I", data, offset + index * size)[0] == 1]
            if not alignments or any(value < 16_384 or value % 16_384 for value in alignments):
                raise ValueError("An ARM64 library has incompatible ELF load alignment: " + entry.filename)
    if count == 0:
        raise ValueError("The APK is missing its qualified ARM64 native libraries")
    return {"libraries": count, "status": "passed"}


def ios_metadata(path, args):
    with (path / "Info.plist").open("rb") as handle:
        info = plistlib.load(handle)
    metadata = {"application_id": info.get("CFBundleIdentifier"), "display_name": info.get("CFBundleDisplayName"), "version": info.get("CFBundleShortVersionString"), "build": str(info.get("CFBundleVersion", "")), "min_ios": info.get("MinimumOSVersion")}
    expected = {"application_id": args.application_id, "display_name": args.display_name, "version": args.version, "build": args.build, "min_ios": args.ios_min}
    require_metadata("iOS app", metadata, expected)
    return metadata


def require_metadata(label, actual, expected):
    mismatches = {key: {"actual": actual.get(key), "expected": value} for key, value in expected.items() if actual.get(key) != value}
    if mismatches:
        raise ValueError(f"{label} metadata mismatch: {json.dumps(mismatches, sort_keys=True)}")


def check_assets(root):
    packs = json.loads((root / "docs/model-catalog.json").read_text())
    source = (root / "shared/src/commonMain/kotlin/com/ereader/shared/ModelCatalog.kt").read_text()
    embedded = json.loads(source.split('"""', 2)[1].replace("${'$'}", "$"))
    if embedded != packs:
        raise ValueError("Shared model catalog differs from its reviewable manifest")
    ids = set()
    for pack in packs:
        if pack["id"] in ids or not re.fullmatch(r"[a-z0-9-]+", pack["id"]):
            raise ValueError("Duplicate or invalid model pack ID")
        ids.add(pack["id"])
        paths = set()
        archive = pack.get("archive")
        if pack["totalBytes"] != (archive["bytes"] if archive else sum(asset["bytes"] for asset in pack["files"])):
            raise ValueError("Model pack byte total differs from assets")
        if archive and (not archive["path"].endswith(".zip") or "/" in archive["path"]):
            raise ValueError("Invalid model archive filename")
        for asset in pack["files"] + ([archive] if archive else []):
            path = Path(asset["path"])
            if path.is_absolute() or ".." in path.parts or asset["path"] in paths or asset["bytes"] <= 0:
                raise ValueError("Unsafe or invalid model asset")
            paths.add(asset["path"])
            if not re.fullmatch(r"[0-9a-f]{64}", asset["sha256"]):
                raise ValueError("Model asset is missing a pinned checksum or HTTPS URL")
            https_url(asset["url"])
    fonts = json.loads((root / "docs/font-manifest.json").read_text())
    names = {"inter": ("inter.ttf", "Inter.ttf"), "spacegrotesk": ("space_grotesk.ttf", "SpaceGrotesk.ttf"), "spacemono": ("space_mono.ttf", "SpaceMono.ttf")}
    for font in fonts:
        android, ios = names[font["family"]]
        for path in (root / "androidApp/src/main/res/font" / android, root / "iosApp/Resources/Fonts" / ios):
            data = path.read_bytes()
            if len(data) != font["bytes"] or hashlib.sha256(data).hexdigest() != font["sha256"]:
                raise ValueError("Bundled font differs from its pinned manifest")
    ocr = json.loads((root / "docs/ocr-manifest.json").read_text())["model"]
    data = (root / ocr["path"]).read_bytes()
    if len(data) != ocr["bytes"] or hashlib.sha256(data).hexdigest() != ocr["sha256"]:
        raise ValueError("Bundled OCR model differs from its pinned manifest")
    return {"model_packs": len(packs), "font_families": len(fonts), "bundled_ocr_models": 1, "status": "passed"}


def main():
    parser = argparse.ArgumentParser(description="Check repository policy and optionally inspect real built app metadata; this does not run device qualification.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path, help="Built APK to inspect with installed Android aapt")
    parser.add_argument("--ios-app", type=Path, help="Built .app bundle to inspect")
    parser.add_argument("--version", default="0.01")
    parser.add_argument("--build", default="1")
    parser.add_argument("--application-id", default="com.osuji.ereaderapp")
    parser.add_argument("--display-name", default="Rhapsodos")
    parser.add_argument("--android-min", type=int, default=28)
    parser.add_argument("--android-target", type=int, default=37)
    parser.add_argument("--ios-min", default="18.0")
    args = parser.parse_args()
    report = {"unit_source_policy": "not_run", "android_artifact": "not_requested", "ios_artifact": "not_requested", "device_qualification": "not_run"}
    try:
        prohibited = unit_sources(args.root.resolve())
        if prohibited:
            raise ValueError("Unit-test source files are prohibited: " + ", ".join(prohibited))
        report["unit_source_policy"] = "passed"
        report["pinned_assets"] = check_assets(args.root.resolve())
        if args.apk:
            report["android_artifact"] = android_metadata(args.apk.resolve(), args)
        if args.ios_app:
            report["ios_artifact"] = ios_metadata(args.ios_app.resolve(), args)
    except (ValueError, OSError, subprocess.CalledProcessError, StopIteration, struct.error, zipfile.BadZipFile) as error:
        report["error"] = str(error)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
