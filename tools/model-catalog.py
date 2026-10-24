#!/usr/bin/env python3
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]


def openssl(*args, data=None):
    return subprocess.run(["openssl", *map(str, args)], input=data, check=True, capture_output=True).stdout


def public_key(private_key):
    der = openssl("ec", "-in", private_key, "-pubout", "-outform", "DER")
    prefix = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    if not der.startswith(prefix) or len(der) != len(prefix) + 65 or der[len(prefix)] != 4:
        raise ValueError("Catalogue keys must use P-256")
    return base64.b64encode(der[len(prefix):]).decode("ascii")


def keygen(args):
    directory = args.output_directory.resolve()
    if directory == ROOT or ROOT in directory.parents:
        raise ValueError("Keep signing secrets outside the repository")
    directory.mkdir(parents=True, exist_ok=True)
    private = directory / "catalog-private.pem"
    with private.open("xb") as handle:
        os.chmod(private, 0o600)
        handle.write(openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout"))
    public = directory / "catalog-public-x963.txt"
    with public.open("x") as handle:
        handle.write(public_key(private) + "\n")
    print(f"Created signing key outside the repository: {directory}")


def sign(args):
    packs = json.loads(args.catalog.read_text())
    if not isinstance(packs, list) or not 1 <= len(packs) <= 64 or args.revision < 1:
        raise ValueError("Supply a nonempty pack list and a positive revision")
    for pack in packs:
        pack.setdefault("adapter", pack["id"])
    now = int(time.time() * 1000)
    payload = json.dumps({"formatVersion": 1, "revision": args.revision, "publishedAt": now,
                          "packs": packs},
                         ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    if len(payload) > 2 * 1024 * 1024:
        raise ValueError("Catalogue exceeds its 2 MiB payload limit")
    public_key(args.private_key)
    signature = openssl("dgst", "-sha256", "-sign", args.private_key, data=payload)
    envelope = {"payload": base64.b64encode(payload).decode("ascii"),
                "signature": base64.b64encode(signature).decode("ascii")}
    args.output.write_text(json.dumps(envelope, separators=(",", ":")) + "\n")
    print(f"Signed revision {args.revision}: {args.output}")


def verify(args):
    raw = args.envelope.read_bytes()
    if len(raw) > 3 * 1024 * 1024:
        raise ValueError("Catalogue envelope exceeds 3 MiB")
    envelope = json.loads(raw)
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    key = base64.b64decode(args.public_key.read_text().strip(), validate=True)
    if len(key) != 65 or key[0] != 4 or len(payload) > 2 * 1024 * 1024 or not 8 <= len(signature) <= 80:
        raise ValueError("Invalid catalogue payload, signature or public key")
    with tempfile.TemporaryDirectory(prefix="rhapsodos-catalog-") as work:
        work = Path(work)
        der = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200") + key
        (work / "key.der").write_bytes(der)
        (work / "key.pem").write_bytes(openssl("pkey", "-pubin", "-inform", "DER", "-in", work / "key.der", "-outform", "PEM"))
        (work / "signature.der").write_bytes(signature)
        openssl("dgst", "-sha256", "-verify", work / "key.pem", "-signature", work / "signature.der", data=payload)
    data = json.loads(payload.decode("utf-8"))
    print(f"Verified signature for revision {data['revision']}; app acceptance also validates models, publication and revision.")


def configure(args):
    url = urlsplit(args.url)
    if url.scheme != "https" or not url.hostname or url.username or url.password or url.fragment or url.port not in (None, 443):
        raise ValueError("Use a public HTTPS catalogue address without credentials")
    key = base64.b64decode(args.public_key.read_text().strip(), validate=True)
    if len(key) != 65 or key[0] != 4:
        raise ValueError("Use the uncompressed P-256 public key")
    encoded = base64.b64encode(key).decode("ascii")
    def kotlin_string(value):
        return json.dumps(value).replace("$", "\\$")
    path = ROOT / "shared/src/commonMain/kotlin/com/ereader/shared/ModelCatalogSource.kt"
    path.write_text("package com.ereader.shared\n\nobject ModelCatalogSource {\n"
                    f"    const val httpsUrl: String = {kotlin_string(args.url)}\n"
                    f"    const val publicKeyX963: String = {kotlin_string(encoded)}\n" + "}\n")
    print("Configured public catalogue trust. Rebuild and review before distributing the apps.")


def bundle(args):
    manifest = ROOT / "docs/model-catalog.json"
    source = ROOT / "shared/src/commonMain/kotlin/com/ereader/shared/ModelCatalog.kt"
    current = json.loads(manifest.read_text())
    packs = json.loads(args.catalog.read_text())
    source_text = source.read_text()
    retained_match = re.search(r'val retainedPacks: List<ModelPack> = Json.decodeFromString\("""\s*(.*?)\s*"""\)', source_text, re.S)
    if retained_match:
        retained = json.loads(retained_match.group(1))
    elif "val retainedPacks: List<ModelPack> = emptyList()" in source_text:
        retained = []
    else:
        raise ValueError("Cannot read retained installed-edition descriptors")
    previous_editions = {pack["id"]: pack for pack in retained + current}
    profiles = {pack.get("adapter", pack["id"]): pack for pack in current}
    if not isinstance(packs, list) or len(packs) != len(profiles):
        raise ValueError("Supply one reviewed edition for each current speech adapter")
    if len({pack["id"] for pack in packs}) != len(packs) or {pack.get("adapter", pack["id"]) for pack in packs} != set(profiles):
        raise ValueError("Model identities or adapters are duplicated or missing")
    pinned = re.compile(r"https://huggingface\.co/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/resolve/[a-f0-9]{40}/[A-Za-z0-9_./-]+")
    for pack in packs:
        profile = profiles[pack.get("adapter", pack["id"])]
        for key in ("name", "capability", "platform", "runtime", "languageTags", "minimumOS", "minimumMemoryMB", "license", "licenseUrl", "description", "voices"):
            if pack.get(key) != profile.get(key):
                raise ValueError("Bundling archives must preserve the reviewed model profile")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,95}", pack["id"]) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}", pack["version"]):
            raise ValueError("Invalid edition identity")
        if pack["id"] in previous_editions and pack != previous_editions[pack["id"]]:
            raise ValueError("A changed model edition needs a new identity")
        expected = {item["path"]: item for item in profile["files"]}
        if len(pack["files"]) != len(expected) or {item["path"] for item in pack["files"]} != set(expected):
            raise ValueError("Archive payload paths differ from the reviewed adapter")
        for item in pack["files"]:
            original = expected[item["path"]]
            if item["bytes"] != original["bytes"] or item["sha256"] != original["sha256"]:
                raise ValueError("Archive packaging must preserve every reviewed payload digest")
            if item["url"] != original["url"] and not pinned.fullmatch(item["url"]):
                raise ValueError("Use a commit-pinned Hugging Face asset URL")
        archive = pack.get("archive")
        if archive:
            if (pack.get("adapter") == "phonon-english-coreml" or not re.fullmatch(r"[A-Za-z0-9_.-]+\.zip", archive["path"])
                    or not pinned.fullmatch(archive["url"]) or not re.fullmatch(r"[a-f0-9]{64}", archive["sha256"])
                    or not 0 < archive["bytes"] <= 1_073_741_824):
                raise ValueError("Invalid archive descriptor")
        if pack["totalBytes"] != (archive["bytes"] if archive else sum(item["bytes"] for item in pack["files"])):
            raise ValueError("Download size does not match the transport")
        for item in pack["files"] + ([archive] if archive else []):
            if any(part in (".", "..") for part in urlsplit(item["url"]).path.split("/")):
                raise ValueError("Unsafe asset URL")
    encoded = json.dumps(packs, indent=2, ensure_ascii=True)
    incoming_ids = {pack["id"] for pack in packs}
    retained = [pack for pack in previous_editions.values()
                if pack["id"] not in incoming_ids and pack.get("adapter", pack["id"]) in profiles]
    retained_encoded = json.dumps(retained, indent=2, ensure_ascii=True)
    if "$" in encoded or "$" in retained_encoded:
        raise ValueError("Unexpected interpolation character in catalogue")
    retained_source = ('Json.decodeFromString("""\n' + retained_encoded + '\n""")') if retained else "emptyList()"
    kotlin = ('package com.ereader.shared\n\nimport kotlinx.serialization.json.Json\n\ninternal object ModelCatalog {\n'
              '    val packs: List<ModelPack> = Json.decodeFromString("""\n' + encoded + '\n""")\n'
              '    val retainedPacks: List<ModelPack> = ' + retained_source + '\n}\n')
    old_manifest, old_source = manifest.read_bytes(), source.read_bytes()
    try:
        manifest.write_text(encoded + "\n")
        source.write_text(kotlin)
    except OSError:
        manifest.write_bytes(old_manifest)
        source.write_bytes(old_source)
        raise
    print("Updated the bundled manifest and shared catalogue. Review, validate and rebuild both apps before release.")


def main():
    parser = argparse.ArgumentParser(description="Sign and configure Rhapsodos model catalogue releases")
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("keygen")
    generate.add_argument("--output-directory", type=Path, required=True)
    generate.set_defaults(run=keygen)
    release = commands.add_parser("sign")
    release.add_argument("--catalog", type=Path, default=ROOT / "docs/model-catalog.json")
    release.add_argument("--private-key", type=Path, required=True)
    release.add_argument("--revision", type=int, required=True)
    release.add_argument("--output", type=Path, required=True)
    release.set_defaults(run=sign)
    check = commands.add_parser("verify")
    check.add_argument("--envelope", type=Path, required=True)
    check.add_argument("--public-key", type=Path, required=True)
    check.set_defaults(run=verify)
    configuration = commands.add_parser("configure")
    configuration.add_argument("--url", required=True)
    configuration.add_argument("--public-key", type=Path, required=True)
    configuration.set_defaults(run=configure)
    bundled = commands.add_parser("bundle")
    bundled.add_argument("--catalog", type=Path, required=True)
    bundled.set_defaults(run=bundle)
    args = parser.parse_args()
    try:
        args.run(args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Catalogue operation failed: {failure.__class__.__name__}\n")


if __name__ == "__main__":
    main()
