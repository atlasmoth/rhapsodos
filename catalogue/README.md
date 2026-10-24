# Catalogue release files

These files record the existing publisher's release and the inputs used to reproduce it. They do not configure a clone's remote catalogue or signing authority.

| File | Purpose |
| --- | --- |
| [source-packs.json](source-packs.json) | Pinned upstream inputs for packaging the four ZIPs |
| [archives.json](archives.json) | Archive digests, sizes and source provenance |
| [archive-packs.json](archive-packs.json) | Reviewable hosted pack list |
| [catalogue.json](catalogue.json) | Signed catalogue envelope for that pack list |
| [catalog-public-x963.txt](catalog-public-x963.txt) | Public verification metadata for this existing release; never an implicit build key |

The four packs cover Phonon narration and Whisper Base English transcription on Android and iOS. Phonon's English voices share one download per platform. [The bundled catalogue](../docs/model-catalog.json) provides discovery before a configured remote catalogue is available.

## Publish your catalogue

1. [Package and verify the archives](../docs/model-archives.md), upload them to public HTTPS URLs and bind a new immutable edition.
2. Follow [release setup](../docs/model-manager.md#release-setup) to generate your own external keypair, sign and verify the pack list, then upload the signed JSON.
3. Configure both `RHAPSODOS_CATALOG_URL` and `RHAPSODOS_CATALOG_PUBLIC_KEY` through ignored `.env` or the build environment, and rebuild both apps. There is no default build key.
4. For updates, reuse your private key, increase the revision and replace the signed JSON at the same catalogue address. Published ZIPs remain immutable.

Private keys must stay outside Git, app bundles and upload folders. Public verification keys are intentionally distributed. The tools do not upload files, and `configure` does not modify these tracked release artifacts.

Catalogues are permanent and need no scheduled renewal. Configured apps refresh automatically without blocking startup, retain a cached copy offline and require explicit selection before replacing a working model. See [third-party notices](../docs/third-party.md) before redistributing model and voice assets.
