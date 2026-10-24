# Model catalogue release

The owner uploaded [catalogue.json](catalogue.json) beside the four ZIPs. The live HTTPS response matches this signed revision exactly and is served as `application/json`. Keep its address stable for future signed updates; the apps receive it through build-time configuration rather than a tracked endpoint constant. The signed JSON is the only additional file required by the apps.

- Revision: **7**
- Published: **24 October 2026, 02:04:14 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `bc481add32a9a94725c09fc768fd4521b5171fbf53ca0e42896169926e4b4fc7`

This release contains four new `hosted1` editions: Phonon narration and Whisper Base English transcription for Android and iOS. [archive-packs.json](archive-packs.json) is the reviewable pack list used to sign it; [archives.json](archives.json) records the verified ZIP digests and sizes. The English Phonon voices share one model download per platform. Three packs use the generic archive transfer; iOS Phonon keeps its official CoreML ZIP path. The installed model bytes, profiles and execution adapters are unchanged.

All four URLs at `https://files.ctclctr.com/<archive filename>` were downloaded completely and matched the prepared release: 442,563,639 bytes total. Each returned HTTPS 200 without redirects and supported an exact HTTP 206 byte-range response. Preserve these ZIP bytes at these addresses. Different future bytes require new filenames/URLs and edition IDs; the catalogue JSON itself stays at its stable address and receives a higher signed revision.

[catalog-public-x963.txt](catalog-public-x963.txt) is verification metadata for this existing revision-7 release, not a default app trust key. The original publisher's private signing key remains outside this repository with owner-only permissions. That key was reused, and OpenSSL verified this signature. Each clone publishing its own catalogue generates its own keypair; keep a secure private-key backup and never upload it.

## App configuration

Configure both `RHAPSODOS_CATALOG_URL` and `RHAPSODOS_CATALOG_PUBLIC_KEY`, the latter containing Base64 uncompressed P-256 X9.63 bytes. The build environment overrides ignored root `.env` independently per field, including explicitly empty values. Empty URL disables remote refresh; a nonempty URL requires a valid public key. There is no default build key, and `.env.example` leaves both settings blank. Gradle generates shared configuration for Android and iOS. Neither value is secret in the built app.

Follow [release setup](../docs/model-manager.md#release-setup) to generate external private/public key files, sign and verify your own revision-1 catalogue, then configure its hosted URL. `configure --url … --public-key /absolute/path/catalog-public-x963.txt` accepts any valid P-256 public key and atomically writes both local settings while preserving unrelated lines. It does not upload files or change tracked release artifacts. The original owner's local URL and key remain configured without rotation. A fork installed alongside the original app needs a separate app identity and fresh app-data container.

The reviewed [archive-packs.json](archive-packs.json) is now bundled for fresh offline discovery. Existing installed/in-flight editions retain their exact descriptors. The separate [source-packs.json](source-packs.json) preserves upstream inputs for reproducing the archives. Catalogue updates never select a new voice or download replacements automatically.

Rebuild both apps after changing the catalogue address or configured public key. Compatible model URLs can change through a higher signed catalogue revision at the existing address without rebuilding. Native verification evidence and limits are recorded in [development.md](../docs/development.md).

For future compatible releases from the same publisher, prepare a new reviewed pack list and sign it with that publisher's existing private key and a higher catalogue revision. Do not edit the signed envelope by hand. See [the archive release workflow](../docs/model-archives.md) and [model manager](../docs/model-manager.md) for the full contract. Catalogue publication and app/device qualification remain separate from these archive-host and signature checks.
