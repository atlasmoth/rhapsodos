# Uploadable model catalogue

Upload [catalogue.json](catalogue.json) unchanged beside the four ZIPs, using the object name `catalogue.json`. The intended stable address is `https://files.ctclctr.com/catalogue.json`. Serve it publicly over HTTPS as `application/json`, preferably with `Cache-Control: no-cache` so launch requests can see later revisions. The signed JSON is the only additional file required by the apps.

- Revision: **7**
- Published: **24 October 2026, 02:04:14 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `bc481add32a9a94725c09fc768fd4521b5171fbf53ca0e42896169926e4b4fc7`

This release contains four new `hosted1` editions: Phonon narration and Whisper Base English transcription for Android and iOS. [archive-packs.json](archive-packs.json) is the reviewable pack list used to sign it; [archives.json](archives.json) records the verified ZIP digests and sizes. The English Phonon voices share one model download per platform. Three packs use the generic archive transfer; iOS Phonon keeps its official CoreML ZIP path. The installed model bytes, profiles and execution adapters are unchanged.

All four URLs at `https://files.ctclctr.com/<archive filename>` were downloaded completely and matched the prepared release: 442,563,639 bytes total. Each returned HTTPS 200 without redirects and supported an exact HTTP 206 byte-range response. Preserve these ZIP bytes at these addresses. Different future bytes require new filenames/URLs and edition IDs; the catalogue JSON itself stays at its stable address and receives a higher signed revision.

[catalog-public-x963.txt](catalog-public-x963.txt) is the existing public verification key for app configuration. The private signing key remains outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup; never upload the private key. The existing key was reused, and OpenSSL verified this signature.

## Connect the apps after catalogue upload

Confirm that the hosted JSON matches this file, then run with the project's conda `dl` Python:

```sh
python tools/model-catalog.py configure \
  --url https://files.ctclctr.com/catalogue.json \
  --public-key catalogue/catalog-public-x963.txt
python tools/model-catalog.py bundle --catalog catalogue/archive-packs.json
```

Use the actual uploaded address if different. Configuration and bundling have not yet been applied. Both apps still have an unset remote source and their current upstream bundled entries. Bundle this same pack list with the initial endpoint configuration so fresh installs show the hosted editions, while existing installed/in-flight editions retain their descriptors. Then rebuild and verify both apps against the live catalogue, including download, restart and offline recovery. Existing signed cached catalogues do not automatically select a new voice or download a replacement.

For future compatible releases, prepare a new reviewed pack list and sign it with the existing private key and a higher catalogue revision. Do not edit the signed envelope by hand. See [the archive release workflow](../docs/model-archives.md) and [model manager](../docs/model-manager.md) for the full contract. Catalogue publication and app/device qualification remain separate from these archive-host and signature checks.
