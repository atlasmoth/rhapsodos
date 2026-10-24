# Uploadable model catalogue

The existing [catalogue.json](catalogue.json) is a signed revision-6 envelope for four speech packs and their 66 upstream assets. It can be hosted unchanged, but it does not point to the newly prepared four-archive release.

For the four ZIPs, follow [the archive release workflow](../docs/model-archives.md). [archives.json](archives.json) records their sizes and SHA-256 digests. Once the files have actual HTTPS addresses on your chosen host, bind those addresses with a fresh edition suffix, review the resulting pack list and sign a new catalogue revision. R2, Hugging Face, S3 and custom servers use the same verification contract. Use versioned or content-addressed paths and retain each release. Do not edit URLs inside the existing signed envelope. The private signing key must never be uploaded.

- Revision: **6**
- Published: **21 October 2026, 07:17:48 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `0133aa4f1dc19ce00210377f1c66e4879a45821719070ab298f6978cda3365b0`

[catalog-public-x963.txt](catalog-public-x963.txt) is the matching public verification key for app configuration. The private signing key is stored outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup for future catalogue updates; never upload that private key.

After upload, configure the catalogue's HTTPS URL and this public key with the existing [release tooling](../docs/model-manager.md#release-setup), then rebuild both apps. Their current URL and public key configuration remain empty. Once configured, signed updates can change model hosting through new editions without rebuilding either app.

Phonon is the only narration family. Whisper Base English provides downloaded voice-note transcription on both platforms, using native CPU Q8 on Android and CoreML on iOS. Moonshine and Whisper Tiny transcription profiles are retired alongside Kitten and Kokoro narration; older cached catalogues cannot restore them. Existing notes and recordings remain intact. Retired transcription selections become off; the replacement requires an explicit Download & enable.

Both Phonon entries expose the same 38 voice choices: Default plus 37 named English profiles from the pinned upstream voice catalogue. Android adds 9,477,032 bytes of profiles to its unchanged four core assets, bringing its pack to 74,526,338 bytes. iOS already contains those voices in its unchanged 137,096,081-byte CoreML archive. Default is retained as its own voice; it is not a duplicate of any named profile.

The updated Android app recognizes this one audited bundled expansion and preserves its installed default voice during an explicit update. Its prior signed payload remains unchanged in storage. Signed catalogue updates cannot otherwise replace an existing edition's assets or invent voice identities. Apps built before this expansion must be rebuilt before using revision 6.

For future releases, edit the source pack list in [docs/model-catalog.json](../docs/model-catalog.json), increment the revision and sign with the same private key. No periodic renewal is needed. Editing the decoded payload without signing it again invalidates the release.

The signature was verified with OpenSSL. Decoded content matches the source manifest exactly apart from explicit adapter defaults; pack totals, envelope bounds, dates and key permissions were checked. Hosting and app connectivity are verified after the public URL is available.
