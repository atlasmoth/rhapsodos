# Model manager

Rhapsodos manages curated, compatible speech packs. Settings opens a shared arrangement on both platforms: Narration, then Voice-note transcription. Each card distinguishes Download, Downloading, Paused, Checking, Ready, Active, Failed and Unavailable. Transfer completion does not imply that a model can run. Voices and transcription have explicit persistent selections; disabling transcription leaves saved recordings and pending notes intact. Android defaults to the Moonshine pack and iOS to system on-device recognition. Downloads require explicit selection after verification; missing selected packs never trigger an undisclosed alternative.

Device requirements explain the supported OS, minimum memory and storage. A successful local compatibility check establishes that the adapter can execute its public sanity input on that device. It does not establish listening quality, sustained speed, peak memory, battery usage or fleet qualification. Checking and optional work yield to narration and capture. Model changes preserve the frozen source position and user edits.

## Catalogue updates

The bundled catalogue always supports offline discovery. A user-requested Refresh can fetch a signed catalogue when the release has a configured HTTPS source and public key. Failed transfers, signatures, unsupported adapters, expired updates and rollback attempts retain the saved catalogue and all installed packs. No document, recording, transcript or provider credential is part of this request.

The native client verifies ECDSA P-256/SHA-256 before handing the exact UTF-8 payload to the shared core. Public keys use the 65-byte uncompressed X9.63 form; signatures use DER. The envelope has exactly `payload` and `signature`, both Base64. Limit the envelope to 3 MiB and the decoded payload to 2 MiB. HTTPS redirects remain on the configured host; cookies, credentials and caches are disabled. [Android Signature](https://developer.android.com/reference/java/security/Signature), [Apple P-256 public key](https://developer.apple.com/documentation/cryptokit/p256/signing/publickey/init(x963representation:))

The payload is:

```json
{
  "formatVersion": 1,
  "revision": 1,
  "publishedAt": 1791417600000,
  "expiresAt": 1794009600000,
  "packs": []
}
```

The example dates are illustrative; publication tooling generates current dates and requires a nonempty list. Revisions increase monotonically. Exact repeats are idempotent; a reused revision with altered contents is rejected. Publication permits five minutes of clock skew and at most 90 days of validity. Existing cached, verified entries remain usable offline after expiry; fresh acceptance requires unexpired publication.

`ModelPack.adapter` identifies one of the four compiled speech profiles. It defaults to the bundled pack ID. New editions use new pack IDs and retain the profile's runtime, platform, task, language, required file paths and conservative minimum requirements. Asset URLs pin Hugging Face or GitHub revisions; sizes, hashes, identifiers and paths are bounded and validated. Runtime verification still checks the downloaded model. A catalogue cannot add a new inference engine or incompatible model family.

The canonical SQLDelight catalogue row owns revision, exact verified payload, accepted time and derived entries. An update retains omitted installed or unfinished editions. It does not alter model-install rows, source documents, notes or listening progress. Existing model identities cannot silently acquire different weights, versions or execution requirements. New compatible editions can be downloaded beside a working edition and explicitly selected after verification; removing the old one remains a deliberate action.

## Release setup

No production catalogue address or trust key is configured yet. The apps use the bundled catalogue and say so. Configure live refresh only after preparing and reviewing a signed release; app implementation does not publish or host anything.

The first uploadable release is prepared in [catalogue/catalogue.json](../catalogue/catalogue.json), alongside its public verification key and [upload instructions](../catalogue/README.md). Revision 1 contains the four bundled packs and expires on 19 January 2027. Reuse its private signing key for subsequent releases; the raw GitHub URL and matching public key still need to be configured in the apps after upload.

Keep the signing secret outside the repository and protect a recoverable copy. Use a stable release key rather than temporary verification keys:

```sh
python3 tools/model-catalog.py keygen --output-directory /absolute/private/catalog-keys
python3 tools/model-catalog.py sign --private-key /absolute/private/catalog-keys/catalog-private.pem --revision 1 --output /absolute/release/catalogue.json
python3 tools/model-catalog.py verify --envelope /absolute/release/catalogue.json --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
python3 tools/model-catalog.py configure --url https://your-host.example/catalogue.json --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
```

The example host is a placeholder, not a deployed service. `configure` changes only the public URL/key in `ModelCatalogSource.kt`; review and commit those values, rebuild, then separately authorize hosting/distribution. The private key never enters the apps. `sign --catalog` accepts a reviewed pack-list file; the default is the bundled manifest. Increase the revision for each publication and renew before expiry. Signing proves publisher identity; the app separately enforces freshness and supported models.

## Ownership and verification

Shared code owns catalogue constraints and persistence. Native clients own HTTPS, cryptography, device checks, selected speech adapters and the audio/transcription transition. Selection changes cancel and join affected work before accepting later results. Generated narration and model files remain separate from durable user recordings.

Qualification must include valid and invalid signatures, expiry/rollback, offline refresh, restart, interrupted downloads, missing assets, explicit selection and stale transcription results. Temporary harnesses stay outside the repository. Executed results and remaining device/hosting gates belong in [development.md](development.md).
