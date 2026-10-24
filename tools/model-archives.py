#!/usr/bin/env python3
import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import urllib.parse
import urllib.request
import zipfile
from model_https import HTTPSRedirectHandler, https_url, request_url


ROOT = Path(__file__).resolve().parents[1]
ARCHIVES = {
    "android-phonon-english": "phonon-31m-en-android.zip",
    "phonon-english-coreml": "phonon-31m-en-coreml.zip",
    "android-whisper-base-en": "whisper-base-en-android.zip",
    "whisper-base-en-coreml": "whisper-base-en-coreml.zip",
}
CHUNK = 1024 * 1024


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def matches(path, descriptor):
    return (path.is_file() and not path.is_symlink()
            and path.stat().st_size == descriptor["bytes"]
            and digest(path) == descriptor["sha256"])


def safe_path(value):
    path = PurePosixPath(value)
    if (not value or path.is_absolute() or str(path) != value or "\\" in value
            or any(part in (".", "..") for part in path.parts)
            or any(ord(character) < 32 for character in value)):
        raise ValueError(f"Unsafe asset path: {value!r}")
    return value


def read_catalog(path):
    packs = json.loads(path.read_text())
    return validate_catalog(packs)


def validate_catalog(packs):
    if not isinstance(packs, list) or {pack["id"] for pack in packs} != set(ARCHIVES) or len(packs) != 4:
        raise ValueError("Expected the four reviewed source packs")
    for pack in packs:
        paths = [safe_path(item["path"]) for item in pack["files"]]
        folded = {path.casefold() for path in paths}
        if len(paths) != len(folded) or "archive" in pack:
            raise ValueError("Expected unique unpacked source descriptors")
        if any(str(parent).casefold() in folded for path in paths for parent in PurePosixPath(path).parents):
            raise ValueError("Asset file and directory paths collide")
        for item in pack["files"]:
            if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) or not 0 < item["bytes"] < 2**31:
                raise ValueError("Invalid expected asset size or digest")
            https_url(item["url"])
    return packs


def download(item, cache, reuse):
    target = cache / item["sha256"]
    if matches(target, item):
        return target
    for directory in reuse:
        candidate = directory / item["path"]
        if matches(candidate, item):
            shutil.copyfile(candidate, target)
            return target
    partial = target.with_suffix(".partial")
    request = urllib.request.Request(request_url(item["url"]), headers={"User-Agent": "Rhapsodos-Model-Packager/1"})
    opener = urllib.request.build_opener(HTTPSRedirectHandler())
    try:
        with opener.open(request, timeout=120) as response, partial.open("wb") as output:
            https_url(response.url)
            count = 0
            while block := response.read(CHUNK):
                count += len(block)
                if count > item["bytes"]:
                    raise ValueError(f"Oversized upstream asset: {item['path']}")
                output.write(block)
        if not matches(partial, item):
            raise ValueError(f"Upstream asset failed integrity verification: {item['path']}")
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def create_archive(destination, files, cache):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED, allowZip64=False) as archive:
        for item in sorted(files, key=lambda item: item["path"]):
            info = zipfile.ZipInfo(item["path"], date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.file_size = item["bytes"]
            with (cache / item["sha256"]).open("rb") as source, archive.open(info, "w") as output:
                shutil.copyfileobj(source, output, CHUNK)


def verify_zip(path, files):
    expected = {item["path"]: item for item in files}
    if len({safe_path(item["path"]).casefold() for item in files}) != len(files):
        raise ValueError("Archive payload paths collide")
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        entries = archive.infolist()
        if len(entries) != len(expected) or {entry.filename for entry in entries} != set(expected):
            raise ValueError("Archive has missing, duplicate or unexpected entries")
        for entry in entries:
            safe_path(entry.filename)
            item = expected[entry.filename]
            if (entry.is_dir() or entry.create_system != 3
                    or entry.external_attr >> 16 != stat.S_IFREG | 0o644
                    or entry.flag_bits & ~0x800 or entry.extra or entry.comment
                    or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    or entry.file_size != item["bytes"]):
                raise ValueError(f"Unexpected ZIP metadata: {entry.filename}")
            raw.seek(entry.header_offset)
            header = raw.read(30)
            values = struct.unpack("<IHHHHHIIIHH", header)
            if values[0] != 0x04034B50 or values[2] != entry.flag_bits or values[10] != 0:
                raise ValueError("Unexpected local ZIP header")
            name = raw.read(values[9]).decode("utf-8" if entry.flag_bits & 0x800 else "cp437")
            if name != entry.filename or values[6:9] != (entry.CRC, entry.compress_size, entry.file_size):
                raise ValueError("Local and central ZIP headers disagree")
            checksum = hashlib.sha256()
            total = 0
            with archive.open(entry) as source:
                while block := source.read(CHUNK):
                    total += len(block)
                    if total > item["bytes"]:
                        raise ValueError("Expanded entry exceeds declared size")
                    checksum.update(block)
            if total != item["bytes"] or checksum.hexdigest() != item["sha256"]:
                raise ValueError(f"Archive entry failed integrity verification: {entry.filename}")


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def verify_upstream_archive(path):
    prefix = "phonon-31m-en-coreml/"
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 96 or len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("Unexpected upstream ZIP inventory")
        for entry in entries:
            safe_path(entry.filename.removesuffix("/"))
            if (not entry.filename.startswith(prefix) or entry.flag_bits & 1
                    or stat.S_IFMT(entry.external_attr >> 16) not in (stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError("Unsafe upstream ZIP entry")
        bundle_entry = archive.getinfo(prefix + "bundle.json")
        if bundle_entry.file_size > 65_536:
            raise ValueError("Oversized upstream manifest")
        expected = json.loads(archive.read(bundle_entry))["files"]
        paths = {prefix + safe_path(item["path"]) for item in expected}
        if (len(expected) != len(paths) or paths & {prefix + "bundle.json", prefix + "source.json"}
                or {entry.filename for entry in entries if not entry.is_dir()}
                != paths | {prefix + "bundle.json", prefix + "source.json"}):
            raise ValueError("Unexpected upstream ZIP files")
        total = 0
        for item in expected:
            entry = archive.getinfo(prefix + item["path"])
            if not 0 < entry.file_size == item["size"] <= 64_000_000:
                raise ValueError("Unexpected upstream entry size")
            total += entry.file_size
            with archive.open(entry) as source:
                if hashlib.file_digest(source, "sha256").hexdigest() != item["sha256"]:
                    raise ValueError("Upstream archive inner digest mismatch")
        if total > 200_000_000 or archive.testzip() is not None:
            raise ValueError("Invalid upstream ZIP content")


def read_release(directory):
    release = json.loads((directory / "release.json").read_text())
    if release["formatVersion"] != 1 or len(release["packs"]) != 4:
        raise ValueError("Unrecognized local release metadata")
    validate_catalog([item["source"] for item in release["packs"]])
    for item in release["packs"]:
        descriptor = item["archive"]
        if descriptor["path"] != ARCHIVES[item["source"]["id"]]:
            raise ValueError("Unexpected archive name")
        if item["upstreamArchive"] is not (item["source"]["id"] == "phonon-english-coreml"):
            raise ValueError("Unexpected archive format")
        path = directory / descriptor["path"]
        if not matches(path, descriptor):
            raise ValueError(f"Archive failed integrity verification: {path.name}")
        if not item["upstreamArchive"]:
            verify_zip(path, item["source"]["files"])
        elif descriptor["sha256"] != item["source"]["files"][0]["sha256"]:
            raise ValueError("Upstream CoreML ZIP was changed")
        else:
            verify_upstream_archive(path)
    return release


def build(args):
    packs = read_catalog(args.catalog)
    output = args.output.resolve()
    cache = args.cache.resolve()
    if any(path == ROOT or ROOT in path.parents for path in (output, cache)):
        raise ValueError("Keep model output and cache outside the source repository")
    output.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)
    sources = {item["sha256"]: item for pack in packs for item in pack["files"]}
    with ThreadPoolExecutor(max_workers=4) as workers:
        list(workers.map(lambda item: download(item, cache, args.reuse_root), sources.values()))
    release = {"formatVersion": 1, "sourceCatalogSHA256": digest(args.catalog), "packs": []}
    for pack in packs:
        upstream = pack["id"] == "phonon-english-coreml"
        destination = output / ARCHIVES[pack["id"]]
        partial = destination.with_suffix(".partial")
        try:
            if upstream:
                shutil.copyfile(cache / pack["files"][0]["sha256"], partial)
                verify_upstream_archive(partial)
            else:
                create_archive(partial, pack["files"], cache)
                verify_zip(partial, pack["files"])
            partial.replace(destination)
        finally:
            partial.unlink(missing_ok=True)
        release["packs"].append({
            "source": pack, "upstreamArchive": upstream,
            "archive": {"path": destination.name, "sha256": digest(destination), "bytes": destination.stat().st_size},
        })
        print(f"Verified {destination.name}: {destination.stat().st_size:,} bytes", flush=True)
    write_json(output / "release.json", release)
    (output / "SHA256SUMS").write_text("".join(
        f"{item['archive']['sha256']}  {item['archive']['path']}\n" for item in release["packs"]))
    licenses = output / "licenses"
    licenses.mkdir(exist_ok=True)
    for source, name in [
        (ROOT / "native/phonon/LICENSE-APACHE", "Apache-2.0.txt"),
        (ROOT / "iosApp/Resources/Licenses/OpenAI_Whisper.txt", "OpenAI-Whisper-MIT.txt"),
        (ROOT / "iosApp/Resources/Licenses/argmax-oss-swift.txt", "Argmax-MIT.txt"),
    ]:
        shutil.copyfile(source, licenses / name)
    (output / "README.md").write_text(release_readme(release))
    read_release(output)


def release_readme(release):
    rows = "\n".join(f"| `{item['archive']['path']}` | {item['archive']['bytes']:,} |"
                     for item in release["packs"])
    return f"""---
language:
- en
tags:
- rhapsodos
- text-to-speech
- automatic-speech-recognition
- on-device
---
# Rhapsodos offline speech packs

Four platform-specific archives for Rhapsodos. Model execution remains on the device. These are deployment formats of upstream models, not newly trained models. The byte-for-byte upstream inputs, pinned URLs, sizes and SHA-256 digests are recorded in `release.json`; outer digests are in `SHA256SUMS`.

| Archive | Bytes |
| --- | ---: |
{rows}

Android Phonon contains Q8 weights, configuration, tokenizer and 38 voice profiles. iOS Phonon is the unchanged official v0.4.0 CoreML archive. Android Whisper is Base English Q8; iOS Whisper is the Base English CoreML conversion and its tokenizer. The three newly created ZIPs use stored entries, fixed timestamps and regular-file permissions. Only the 65 expected payload files are placed in those ZIPs; release metadata and notices stay alongside them. The official iOS Phonon archive retains its upstream structure.

Phonon 31M English is Apache-2.0. Whisper is MIT; Argmax conversions are MIT. License texts are under `licenses/`. Model and voice-profile licensing are distinct: the pinned `gradium/phonon-voices` repository has no separate license declaration recorded in this project. **Confirm permission to redistribute the named voice profiles before publicly uploading the Phonon archives.** Do not treat this packaging step as resolving that existing release gate. Do not declare one blanket license over the entire repository.

Sources: [Gradium Phonon 31M English](https://huggingface.co/gradium/phonon-31m-en/tree/52433c46975bfa13144c56204c9f4ee1c5b148aa), [Gradium voice profiles](https://huggingface.co/gradium/phonon-voices/tree/da6c9ec0d47be8df5c87c730a3aa97187f3229c3), [official CoreML release](https://github.com/gradium-ai/xn-ptts/releases/tag/v0.4.0), [whisper.cpp assets](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1), [Argmax WhisperKit CoreML](https://huggingface.co/argmaxinc/whisperkit-coreml/tree/0f63a7800b00dd0226abd051b906c246e1907482). `release.json` also records the tokenizer's independent revision.

## Publication handoff

Nothing has been uploaded by the packaging tool. After the redistribution gate is resolved, upload the four ZIPs and these documentation/checksum files to your chosen HTTPS host. Use versioned or content-addressed paths and keep each release available. In the Rhapsodos checkout run `tools/model-archives.py bind --directory /absolute/path/to/this/folder --base-url HTTPS_RELEASE_DIRECTORY --edition archive1 --output /absolute/path/to/packs.json` using the project's conda `dl` Python. Alternatively, `--urls /absolute/path/to/urls.json` accepts a JSON object mapping the four archive filenames to exact HTTPS URLs. Use a fresh edition suffix whenever URLs or other edition data change. The catalogue's SHA-256 digests pin the downloaded bytes independently of the host. Sign the resulting pack list with `tools/model-catalog.py sign --catalog /absolute/path/to/packs.json` and the existing release signing key, advancing the catalogue revision. Review/apply the bound manifest to the bundled catalogue and rebuild the apps for fresh-install discovery, or publish the signed catalogue at the configured refresh address. Keep the private key outside this folder and away from all upload destinations.

Compatibility checks establish that the app can open a pack. They do not establish sustained quality, performance, battery life or physical-device qualification.
"""


def bind(args):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", args.edition):
        raise ValueError("Use a lowercase edition suffix of at most 32 letters, digits or hyphens")
    if args.base_url is not None:
        base = https_url(args.base_url)
        urls = {filename: urllib.parse.urlunsplit((base.scheme, base.netloc,
                base.path.rstrip("/") + "/" + urllib.parse.quote(filename), base.query, ""))
                for filename in ARCHIVES.values()}
    else:
        urls = json.loads(args.urls.read_text())
        if not isinstance(urls, dict) or set(urls) != set(ARCHIVES.values()):
            raise ValueError("Map exactly the four archive filenames to HTTPS URLs")
    for url in urls.values():
        https_url(url)
    release = read_release(args.directory)
    packs = []
    for item in release["packs"]:
        pack = copy.deepcopy(item["source"])
        pack["adapter"] = pack["id"]
        pack["id"] += "-" + args.edition
        pack["version"] += "-" + args.edition
        if len(pack["id"]) > 96 or len(pack["version"]) > 96:
            raise ValueError("The edition suffix makes a model identity too long")
        descriptor = copy.deepcopy(item["archive"])
        descriptor["url"] = urls[descriptor["path"]]
        if item["upstreamArchive"]:
            pack["files"] = [descriptor]
        else:
            pack["archive"] = descriptor
        pack["totalBytes"] = descriptor["bytes"]
        packs.append(pack)
    write_json(args.output, packs)
    print(f"Wrote bound pack list: {args.output}")


def main():
    parser = argparse.ArgumentParser(description="Package and verify the four Rhapsodos model archives")
    commands = parser.add_subparsers(dest="command", required=True)
    package = commands.add_parser("build")
    package.add_argument("--catalog", type=Path, default=ROOT / "catalogue/source-packs.json")
    package.add_argument("--output", type=Path, required=True)
    package.add_argument("--cache", type=Path, required=True)
    package.add_argument("--reuse-root", type=Path, action="append", default=[])
    package.set_defaults(run=build)
    check = commands.add_parser("verify")
    check.add_argument("--directory", type=Path, required=True)
    check.set_defaults(run=lambda args: (read_release(args.directory), print("Verified all four archives and expected payloads")))
    publication = commands.add_parser("bind")
    publication.add_argument("--directory", type=Path, required=True)
    addresses = publication.add_mutually_exclusive_group(required=True)
    addresses.add_argument("--base-url", help="HTTPS directory containing all four archives")
    addresses.add_argument("--urls", type=Path, help="JSON object mapping archive filenames to exact HTTPS URLs")
    publication.add_argument("--edition", required=True, help="Fresh immutable edition suffix, such as archive1")
    publication.add_argument("--output", type=Path, required=True)
    publication.set_defaults(run=bind)
    args = parser.parse_args()
    try:
        args.run(args)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, struct.error) as failure:
        parser.exit(1, f"Model archive operation failed: {failure}\n")


if __name__ == "__main__":
    main()
