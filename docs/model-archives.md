# Model archive release

The four speech packs can be distributed as four ZIP files. `tools/model-archives.py` produces the three new archives from the exact source descriptors in [model-catalog.json](model-catalog.json) and copies the official iOS Phonon ZIP unchanged. It never uploads files, signs a catalogue or invents a live hosting address.

| Archive | Content |
| --- | --- |
| `phonon-31m-en-android.zip` | 41 files: shared Q8 model, configuration, tokenizer and 38 speaker profiles |
| `phonon-31m-en-coreml.zip` | Unchanged official CoreML release, including all English speakers |
| `whisper-base-en-android.zip` | Base English Q8 model |
| `whisper-base-en-coreml.zip` | 23 CoreML and tokenizer files |

The three new ZIPs contain only their exact catalogue payload paths at the archive root. Entries are stored without recompression, sorted by path, fixed to the ZIP epoch and marked as regular `0644` files. There are no directory entries, extra fields, data descriptors or ZIP64 records. This keeps the archives reproducible across compression-library versions and suitable for bounded native extraction. The unchanged official Phonon ZIP retains its own verified manifest and native preparation path.

## Prepare locally

Use the project's conda `dl` environment. Output and cache directories must remain outside the source repository:

```sh
conda run -n dl python tools/model-archives.py build \
  --output /Users/atlasmoth/Downloads/Rhapsodos-HuggingFace \
  --cache /Users/atlasmoth/.cache/rhapsodos-model-archives
conda run -n dl python tools/model-archives.py verify \
  --directory /Users/atlasmoth/Downloads/Rhapsodos-HuggingFace
```

`build` hashes every source before use. It downloads pinned assets into a content-addressed cache, rejects oversized or mismatched responses and replaces final ZIPs only after verification. Repeated `--reuse-root /absolute/installed/pack/folder` arguments allow verified local assets to avoid downloading again. Cached assets are always rechecked. `verify` checks outer digests and sizes, exact inner inventories and per-file hashes; the official Phonon archive also has its internal manifest checked. Nothing is extracted into the source repository.

The output includes the four archives, `SHA256SUMS`, `release.json` with complete source provenance, a Hugging Face model card and license texts. These small supporting files should accompany the archives. They are not extra model downloads. The existing Phonon named-voice redistribution question remains a publication gate; [third-party.md](third-party.md) records it. Local packaging does not resolve that question or publish the files.

The prepared release is recorded in [archives.json](../catalogue/archives.json): four ZIPs totaling 442,563,639 bytes. The binaries remain outside Git. Model archives contain no PDFs, recordings, credentials or signing keys.

## Bind after upload

Once the user supplies the actual public Hugging Face repository and immutable commit after upload:

```sh
conda run -n dl python tools/model-archives.py bind \
  --directory /Users/atlasmoth/Downloads/Rhapsodos-HuggingFace \
  --repo OWNER/REPOSITORY \
  --revision FULL_40_CHARACTER_COMMIT_SHA \
  --output /absolute/release/archive-packs.json
```

The uppercase values above are command placeholders, never shipping URLs. `bind` verifies the local release again and emits a reviewed pack-list candidate with pinned HTTPS resolve URLs, archive hashes and new immutable edition IDs. Generic archive packs retain the installed file descriptors in `files` and declare one `archive` descriptor. `totalBytes` is the download size; expected installed payload size remains the sum of `files[].bytes`. The official iOS Phonon pack retains its existing one-ZIP shape. All four editions explicitly select their existing compiled adapter.

Review and sign that pack list through [model-catalog.py](../tools/model-catalog.py), increasing the catalogue revision. The signing key stays outside the repository and upload folder. After checking the hosted files, `model-catalog.py bundle --catalog /absolute/release/archive-packs.json` updates both the reviewable bundled manifest and its shared Kotlin representation. It requires the same reviewed payload hashes and explicit adapter IDs. Validate and rebuild both apps for discovery on a fresh install, or publish the signed catalogue through an already configured refresh address. Confirm all four pinned URLs and a complete fresh download before distributing that release. Until the actual hosted addresses are bound, current upstream catalogue downloads continue working.
