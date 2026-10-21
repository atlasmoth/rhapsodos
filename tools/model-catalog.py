#!/usr/bin/env python3
import argparse
import base64
import json
import os
from pathlib import Path
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
    args = parser.parse_args()
    try:
        args.run(args)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Catalogue operation failed: {failure.__class__.__name__}\n")


if __name__ == "__main__":
    main()
