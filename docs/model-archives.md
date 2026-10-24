# Model archive release

Publish four ZIPs: one narration pack and one transcription pack for each platform. An app downloads only its platform's selected packs.

| Archive | Content |
| --- | --- |
| `phonon-31m-en-android.zip` | Android Phonon narration and 38 English voices |
| `phonon-31m-en-coreml.zip` | iOS Phonon narration and the same English voice choices |
| `whisper-base-en-android.zip` | Android Whisper Base English transcription |
| `whisper-base-en-coreml.zip` | iOS Whisper Base English transcription |

## Package and verify

Set up the [catalogue tools](setup.md#catalogue-tools), then run from the repository root using conda `dl`. Output and cache directories must be outside the repository; these paths are placeholders:

```sh
conda run -n dl python tools/model-archives.py build \
  --output /absolute/release/models \
  --cache /absolute/cache/rhapsodos-models
conda run -n dl python tools/model-archives.py verify \
  --directory /absolute/release/models
```

The packager verifies pinned inputs from [source-packs.json](../catalogue/source-packs.json), builds three reproducible ZIPs and copies the official iOS Phonon ZIP unchanged. This source manifest is separate from the apps' [bundled catalogue](model-catalog.json). Optional repeated `--reuse-root /absolute/installed/pack` arguments reuse local files after verification.

The output includes `SHA256SUMS`, `release.json` provenance, a model card and license texts. Publish the supporting notices alongside the ZIPs; they are not additional model downloads. Before redistributing named Phonon voices, resolve the voice-asset licensing question recorded in [third-party.md](third-party.md).

## Upload and bind

Upload the ZIPs to any public HTTPS host: R2, Hugging Face, S3 or your own server. Use durable versioned or content-addressed URLs and preserve published bytes. Downloads must allow unauthenticated HTTPS GET; byte-range support enables efficient resume. Catalogue URLs are public, so do not include private credentials.

Bind the actual hosted addresses into a pack list:

```sh
conda run -n dl python tools/model-archives.py bind \
  --directory /absolute/release/models \
  --base-url https://models.example.com/releases/release1 \
  --edition archive1 \
  --output /absolute/release/archive-packs.json
```

`--base-url` appends each archive filename. For separate hosts or paths, replace it with `--urls /absolute/release/urls.json`: a JSON object mapping exactly the four filenames above to their full HTTPS URLs. Prefer permanent addresses over expiring download links.

`--edition` creates immutable pack IDs. Use a fresh suffix whenever URLs, bytes or edition details change: up to 32 lowercase letters, digits or hyphens, beginning with a letter or digit. `bind` verifies the local release again; it does not upload or sign anything. Check that the hosted bytes match the verified release before publishing the catalogue.

Follow [release setup](model-manager.md#release-setup) to sign and verify this pack list, publish the signed JSON and configure your own URL and public key. Later publications from the same publisher use the same key and a higher revision. Updating remote discovery does not require rebundling or rebuilding an already configured app.

## Installation guarantees

Each archive edition downloads one ZIP without a per-file network fallback. Both apps check the outer SHA-256 and size, reject unsafe or undeclared ZIP contents, verify extracted files and run the native model check before atomic activation. Failed or cancelled installation preserves the working edition. Downloaded model assets remain separate from documents and recordings.
