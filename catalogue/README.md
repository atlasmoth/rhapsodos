# Uploadable model catalogue

Upload [catalogue.json](catalogue.json) unchanged to the public GitHub repository that will host catalogue updates. The file contains the signed envelope expected by both apps. Its payload lists the four current speech packs and their 31 pinned assets; the model weights remain at their existing download URLs.

- Revision: **4**
- Published: **21 October 2026, 04:46:39 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `731a30013f1b7bc599b27d11aab285c3bb5fa710f4fe6ef279217126b562168e`

[catalog-public-x963.txt](catalog-public-x963.txt) is the matching public verification key for app configuration. The private signing key is stored outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup for future catalogue updates; never upload that private key.

After upload, configure the raw GitHub HTTPS URL and this public key with the existing [release tooling](../docs/model-manager.md#release-setup), then rebuild both apps with permanent-catalogue support. Their current URL and public key configuration remain empty.

Phonon is the only narration family. Moonshine on Android and Whisper on iOS provide downloaded voice-note transcription. Kitten and Kokoro narration adapters are retired; older cached catalogues cannot restore them.

For future releases, edit the source pack list in [docs/model-catalog.json](../docs/model-catalog.json), increment the revision and sign with the same private key. No periodic renewal is needed. Editing the decoded payload without signing it again invalidates the release.

The signature was verified with OpenSSL. Decoded content matches the source manifest exactly apart from explicit adapter defaults; pack totals, envelope bounds, dates and key permissions were checked. Hosting and app connectivity are verified after the public URL is available.
