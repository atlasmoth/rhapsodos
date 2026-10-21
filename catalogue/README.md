# Uploadable model catalogue

Upload [catalogue.json](catalogue.json) unchanged to the public GitHub repository that will host catalogue updates. The file contains the signed envelope expected by both apps. Its payload lists the four current speech packs and their 66 pinned assets; the model weights remain at their existing download URLs.

- Revision: **6**
- Published: **21 October 2026, 07:17:48 WAT**
- Expiry: **None — no renewal required**
- SHA-256: `0133aa4f1dc19ce00210377f1c66e4879a45821719070ab298f6978cda3365b0`

[catalog-public-x963.txt](catalog-public-x963.txt) is the matching public verification key for app configuration. The private signing key is stored outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup for future catalogue updates; never upload that private key.

After upload, configure the raw GitHub HTTPS URL and this public key with the existing [release tooling](../docs/model-manager.md#release-setup), then rebuild both apps with permanent-catalogue support. Their current URL and public key configuration remain empty.

Phonon is the only narration family. Whisper Base English provides downloaded voice-note transcription on both platforms, using native CPU Q8 on Android and CoreML on iOS. Moonshine and Whisper Tiny transcription profiles are retired alongside Kitten and Kokoro narration; older cached catalogues cannot restore them. Existing notes and recordings remain intact. Retired transcription selections become off; the replacement requires an explicit Download & enable.

Both Phonon entries expose the same 38 voice choices: Default plus 37 named English profiles from the pinned upstream voice catalogue. Android adds 9,477,032 bytes of profiles to its unchanged four core assets, bringing its pack to 74,526,338 bytes. iOS already contains those voices in its unchanged 137,096,081-byte CoreML archive. Default is retained as its own voice; it is not a duplicate of any named profile.

The updated Android app recognizes this one audited bundled expansion and preserves its installed default voice during an explicit update. Its prior signed payload remains unchanged in storage. Signed catalogue updates cannot otherwise replace an existing edition's assets or invent voice identities. Apps built before this expansion must be rebuilt before using revision 6.

For future releases, edit the source pack list in [docs/model-catalog.json](../docs/model-catalog.json), increment the revision and sign with the same private key. No periodic renewal is needed. Editing the decoded payload without signing it again invalidates the release.

The signature was verified with OpenSSL. Decoded content matches the source manifest exactly apart from explicit adapter defaults; pack totals, envelope bounds, dates and key permissions were checked. Hosting and app connectivity are verified after the public URL is available.
