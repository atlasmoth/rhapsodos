# Uploadable model catalogue

Upload [catalogue.json](catalogue.json) unchanged to the public GitHub repository that will host catalogue updates. The file contains the signed envelope expected by both apps. Its payload lists the four current speech packs and their 29 pinned assets; the model weights remain at their existing download URLs.

- Revision: **5**
- Published: **21 October 2026, 06:35:20 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `d3e79928d41e9333f70e184456248c8949c68a18361058ea56e6e5933e1d45ec`

[catalog-public-x963.txt](catalog-public-x963.txt) is the matching public verification key for app configuration. The private signing key is stored outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup for future catalogue updates; never upload that private key.

After upload, configure the raw GitHub HTTPS URL and this public key with the existing [release tooling](../docs/model-manager.md#release-setup), then rebuild both apps with permanent-catalogue support. Their current URL and public key configuration remain empty.

Phonon is the only narration family. Whisper Base English provides downloaded voice-note transcription on both platforms, using native CPU Q8 on Android and CoreML on iOS. Moonshine and Whisper Tiny transcription profiles are retired alongside Kitten and Kokoro narration; older cached catalogues cannot restore them. Existing notes and recordings remain intact. Retired transcription selections become off; the replacement requires an explicit Download & enable.

For future releases, edit the source pack list in [docs/model-catalog.json](../docs/model-catalog.json), increment the revision and sign with the same private key. No periodic renewal is needed. Editing the decoded payload without signing it again invalidates the release.

The signature was verified with OpenSSL. Decoded content matches the source manifest exactly apart from explicit adapter defaults; pack totals, envelope bounds, dates and key permissions were checked. Hosting and app connectivity are verified after the public URL is available.
