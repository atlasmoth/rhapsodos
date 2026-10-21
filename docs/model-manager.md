# Model manager

Rhapsodos manages curated, compatible speech packs. Both apps place **Download voices and transcription** above two concise Settings entries: **Reading voice** and **Transcribe voice notes**. That button opens a combined **Voices and transcription** manager using the same compact cards and setup flow as the focused sheets. Reading voice shows the current choice or download state and opens its focused sheet. Transcribe voice notes is a switch; enabling it offers the required download when no verified transcription pack is available. Fresh installations start with transcription off. Recording, saving and replaying notes never require this switch or a speech model.

Each focused sheet shows the voice or transcription name, language, download size and one contextual primary action. **Download & use** selects a narration voice after download and validation; **Download & enable** does the same for transcription. Existing verified downloads use **Use voice** or **Enable**. Pause, resume, retry, checking and selected/on states remain visible when relevant. Do not duplicate voice pickers, model-selection chips or instructions in Settings and the sheet. Keep requirements, runtime information, check history, manual rechecking and removal behind **Details**. Hide unconfigured catalogue controls and avoid routine privacy or qualification paragraphs in the main flow.

The initial action authorizes the complete download, integrity check, runtime check and activation sequence. Transfer completion alone never permits activation. Persist one pending choice per capability outside the sheet lifecycle; dismissal or restart must not lose it. New choices replace old pending choices. Turning transcription off or removing its pack cancels pending activation before suspending work. Pausing a transfer retains the choice for an explicit resume. Failed validation never selects a pack; retry continues through verification. Downloads started without activation intent do not create one. Existing selections remain until a replacement passes and commits through the native selection path.

Activation rechecks the current intent and capture state after asynchronous boundaries. Capture and durable document work take priority; deferred setup reports a short waiting state and continues when safe. Narration activation preserves the frozen listening cursor and does not start paused playback. Transcription changes cancel and join previous inference; stale results cannot overwrite notes or edits. Off preserves recordings, queued notes and downloaded assets.

Device requirements and compatibility results remain available in Details. A successful local check only establishes that the adapter executes its public sanity input on that device; it does not establish listening quality, sustained speed, peak memory, battery usage or fleet qualification. Checking and optional work yield to narration and capture.

## Catalogue updates

The bundled catalogue always supports offline discovery. A user-requested Refresh can fetch a signed catalogue when the release has a configured HTTPS source and public key. New catalogues are permanent and require no scheduled renewal. Failed transfers, signatures, unsupported adapters and rollback attempts retain the saved catalogue and all installed packs. No document, recording, transcript or provider credential is part of this request.

The native client verifies ECDSA P-256/SHA-256 before handing the exact UTF-8 payload to the shared core. Public keys use the 65-byte uncompressed X9.63 form; signatures use DER. The envelope has exactly `payload` and `signature`, both Base64. Limit the envelope to 3 MiB and the decoded payload to 2 MiB. For catalogue refresh, HTTPS redirects remain on the configured host; cookies, credentials and caches are disabled. Model assets follow the native download policy; iOS background transfers check the initial and final approved HTTPS hosts, while the OS follows intermediate redirects. [Android Signature](https://developer.android.com/reference/java/security/Signature), [Apple P-256 public key](https://developer.apple.com/documentation/cryptokit/p256/signing/publickey/init(x963representation:))

The payload is:

```json
{
  "formatVersion": 1,
  "revision": 4,
  "publishedAt": 1791417600000,
  "packs": []
}
```

The example date is illustrative; publication tooling generates the current publication time and requires a nonempty list. Revisions increase monotonically. Exact repeats are idempotent; a reused revision with altered contents is rejected. Publication permits five minutes of clock skew and cannot precede the last accepted publication. Omitted or null `expiresAt` means permanent. An explicitly supplied expiry still requires an unexpired interval of at most 90 days, allowing existing signed payloads to retain their stated meaning. Cached entries and downloaded models never stop working because of a catalogue date.

Signatures establish authenticity, not that a catalogue is the newest available. A permanent, correctly signed older catalogue remains acceptable to a fresh installation with no remembered revision; an existing installation rejects revisions below its last accepted revision. This is the deliberate tradeoff for eliminating renewal. Permanent validity does not guarantee that upstream model files remain hosted forever.

`ModelPack.adapter` identifies one of the four compiled speech profiles. It defaults to the bundled pack ID. New editions use new pack IDs and retain the profile's runtime, platform, task, language, required file paths and conservative minimum requirements. Asset URLs pin Hugging Face or GitHub revisions; an exception accepts an exact compiled file descriptor (URL, path, size and hash together), allowing the pinned Phonon CoreML release ZIP without trusting arbitrary release URLs; sizes, hashes, identifiers and paths are bounded and validated. Runtime verification still checks the downloaded model. A catalogue cannot add a new inference engine or incompatible model family.

New bundled profiles remain discoverable after an app update even when an older verified catalogue is cached; existing cached editions keep their identity. The canonical SQLDelight catalogue row owns revision, exact verified payload, accepted time and derived entries. An update retains omitted installed or unfinished editions only while their adapter remains supported. Discovery filters retired adapters out of cached catalogues, and native startup removes their model assets before clearing their install inventory. Documents, recordings and notes are never part of that cleanup. It does not alter model-install rows, source documents, notes or listening progress. Existing model identities cannot silently acquire different weights, versions or execution requirements. New compatible editions can be downloaded beside a working edition. Download & use selects the requested edition only after verification; removing the old one remains a deliberate action.

## Release setup

No production catalogue address or trust key is configured yet. The apps use the bundled catalogue without exposing unconfigured hosting state in the normal settings flow. Configure live refresh only after preparing and reviewing a signed release; app implementation does not publish or host anything.

The uploadable release is prepared in [catalogue/catalogue.json](../catalogue/catalogue.json), alongside its public verification key and [upload instructions](../catalogue/README.md). Revision 4 contains the four bundled packs and has no expiry. Reuse its private signing key for subsequent releases; the raw GitHub URL and matching public key still need to be configured in the apps after upload. Apps built before permanent-catalogue support must be rebuilt.

Keep the signing secret outside the repository and protect a recoverable copy. Use a stable release key rather than temporary verification keys:

```sh
python3 tools/model-catalog.py keygen --output-directory /absolute/private/catalog-keys
python3 tools/model-catalog.py sign --private-key /absolute/private/catalog-keys/catalog-private.pem --revision 1 --output /absolute/release/catalogue.json
python3 tools/model-catalog.py verify --envelope /absolute/release/catalogue.json --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
python3 tools/model-catalog.py configure --url https://your-host.example/catalogue.json --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
```

The example host is a placeholder, not a deployed service. `configure` changes only the public URL/key in `ModelCatalogSource.kt`; review and commit those values, rebuild, then separately authorize hosting/distribution. The private key never enters the apps. `sign --catalog` accepts a reviewed pack-list file; the default is the bundled manifest. Signing always creates a permanent catalogue. Increase the revision only when publishing a changed catalogue; no periodic signing or renewal is required. Signing proves publisher identity; the app separately enforces publication/revision constraints and supported models.

## Ownership and verification

Shared code owns catalogue constraints and persistence. Native clients own HTTPS, cryptography, device checks, selected speech adapters and the audio/transcription transition. Selection changes cancel and join affected work before accepting later results. Generated narration and model files remain separate from durable user recordings.

Qualification must include valid and invalid signatures, permanent validity, explicit-expiry rejection, revision rollback, offline refresh, restart, interrupted downloads, missing assets, explicit selection and stale transcription results. Temporary harnesses stay outside the repository. Executed results and remaining device/hosting gates belong in [development.md](development.md).

## Phonon narration

Settings → Reading voice offers Phonon as the only narration download. Download & use handles the transfer, quiet device check and selection as one flow. Android downloads four pinned Q8/config/tokenizer/default-voice files (65.0 MB); iOS downloads one pinned CoreML ZIP (137.1 MB) and compiles its three models locally. Both packs use a 3 GB memory guard, which is an eligibility threshold rather than a physical-device guarantee. Android requires a supported 64-bit process. The iOS app requires iOS 18.

iOS verifies the outer archive, its exact permitted paths, bounded sizes, duplicate/symlink restrictions and every inner digest before extraction into staging. Derived compiled models receive a checked receipt tied to the archive and OS. Native readiness synthesis precedes canonical activation. Android uses the existing resumable file downloader and per-file integrity checks. Both paths serialize inference with capture/transcription, discard cancelled or incomplete PCM and keep native audio time authoritative. Model code ships in the app; only assets are downloaded.

## Downloaded speech only

Narration requires a verified Phonon pack selected through an explicit Use voice or Download & use action. A fresh install opens and prepares documents and saves voice notes, but Play directs the listener to download a voice until one is ready. Removing or invalidating the selected pack pauses narration and requires download or repair; cached audio does not bypass readiness. There is no system TTS or system ASR selection or fallback. Downloaded Whisper transcription is independent of narration; unavailable transcription leaves saved recordings pending.

Kitten and Kokoro were removed by the product decision on 21 October 2026 after the recorded Phonon comparison. This simplifies the supported narration surface; it does not turn the speed/memory comparison into proof of universal listening-quality dominance. Future narration packs must justify a measured quality, language, accessibility or resource advantage against Phonon before adding runtime and gallery complexity.
