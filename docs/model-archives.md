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

Upload the same four ZIPs to any HTTPS host, including Cloudflare R2, Hugging Face, S3 or your own server. The signed catalogue and exact SHA-256 digests establish trust independently of the storage provider. Use durable versioned or content-addressed paths so older editions remain downloadable. No provider SDK, embedded credential or domain allowlist is needed. The server must permit unauthenticated HTTPS GET requests; byte-range support makes interrupted downloads efficient.

Once the actual hosted addresses are available, bind a common directory:

```sh
conda run -n dl python tools/model-archives.py bind \
  --directory /Users/atlasmoth/Downloads/Rhapsodos-HuggingFace \
  --base-url https://models.example.com/releases/2026-10-24 \
  --edition archive1 \
  --output /absolute/release/archive-packs.json
```

The example domain is a placeholder, never a shipping address. `--base-url` appends each archive filename to the supplied directory and preserves any query. For different directories or signed query parameters, replace `--base-url` with `--urls /absolute/release/urls.json`. That file must be a JSON object mapping exactly the four archive filenames in the table above to their full HTTPS URLs. Expiring URLs require a new catalogue edition before they expire; permanent public download addresses are simpler.

`--edition` is required and contains up to 32 lowercase letters, digits or hyphens, starting with a letter or digit. Use a fresh suffix whenever URLs or other edition data change. `bind` verifies the local release again and emits a reviewed pack-list candidate with the supplied HTTPS URLs, archive hashes and immutable edition IDs. Generic archive packs retain the installed file descriptors in `files` and declare one `archive` descriptor. `totalBytes` is the download size; expected installed payload size remains the sum of `files[].bytes`. The official iOS Phonon pack retains its existing one-ZIP shape. All four editions explicitly select their existing compiled adapter.

All source, model, archive and catalogue addresses require HTTPS. Release tooling validates every redirect before requesting its target; native apps apply the shared URL policy with the [platform transfer rules](model-manager.md). Different HTTPS hosts and ports, IPv6, encoded paths and queries are supported. Credentials, fragments, whitespace, control characters, malformed escapes and invalid hosts or ports are rejected. International domain names use their ASCII/punycode form. URL query parameters are part of the public catalogue; do not put private credentials in them. The packager verifies downloaded bytes even when redirects are used.

Review and sign that pack list through [model-catalog.py](../tools/model-catalog.py), increasing the catalogue revision. The signing key stays outside the repository and upload folder. After checking the hosted files, `model-catalog.py bundle --catalog /absolute/release/archive-packs.json` updates both the reviewable bundled manifest and its shared Kotlin representation. It requires the same reviewed payload hashes and explicit adapter IDs, and rejects changes that reuse an existing edition ID. Validate and rebuild both apps for discovery on a fresh install, or publish the signed catalogue through an already configured refresh address. A configured app can change storage hosts through a signed catalogue update without rebuilding. Confirm all four hosted URLs and a complete fresh download before distributing that release. Until actual hosted addresses are bound, current upstream catalogue downloads continue working.
