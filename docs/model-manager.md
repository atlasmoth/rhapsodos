# Model manager

Settings → **Download voices and transcription** opens the shared download flow. **Download & use** installs, checks and selects Phonon for narration; **Download & enable** does the same for Whisper Base English transcription. Checks are automatic. Failed operations offer Retry; paused downloads offer Resume. Technical details and removal stay in Details.

Phonon's 38 English voices share one model download per platform. Changing an installed voice downloads nothing and preserves the listening position. Transcription is optional: recording, saving and replaying a note never require a speech model. Neither capability falls back to system speech or remote processing.

## Catalogue updates

With a configured HTTPS catalogue and public key, both apps refresh once on launch without blocking startup. The cached catalogue, or bundled defaults on a fresh installation, is immediately usable. Signature, compatibility, network or rollback failures leave the saved catalogue and installed packs available. There is no Refresh button.

Catalogues are signed with ECDSA P-256/SHA-256 and have no expiry or renewal requirement. Changed contents require a higher revision; exact repeats are accepted, while altered contents at the same revision and older revisions are rejected. A fresh installation has no remembered remote revision, so signatures establish the publisher's identity rather than proving a response is its newest release.

Updates advertise compatible editions without downloading or selecting them automatically. Existing installed or unfinished editions retain their descriptors. Changing URLs, bytes or edition details requires a new immutable pack ID. A new inference engine or incompatible model family still requires an app release.

## Release setup

Each clone publishing a catalogue generates its own signing keypair. Prepare the four ZIPs and an external `archive-packs.json` using [the archive workflow](model-archives.md). Set up the [catalogue tools](setup.md#catalogue-tools) first; all paths and hosts below are placeholders.

Keep private keys outside the repository and upload directory. Generate, sign and verify your first catalogue:

```sh
conda run -n dl python tools/model-catalog.py keygen \
  --output-directory /absolute/private/catalog-keys
conda run -n dl python tools/model-catalog.py sign \
  --catalog /absolute/release/archive-packs.json \
  --private-key /absolute/private/catalog-keys/catalog-private.pem \
  --revision 1 --output /absolute/release/catalogue.json
conda run -n dl python tools/model-catalog.py verify \
  --envelope /absolute/release/catalogue.json \
  --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
```

The release directory and reviewed pack list must already exist. Upload the ZIPs to their declared URLs and the signed JSON to a stable public HTTPS address. The tools do not upload files. Configure the matching endpoint and public key:

```sh
conda run -n dl python tools/model-catalog.py configure \
  --url https://models.example.com/catalogue.json \
  --public-key /absolute/private/catalog-keys/catalog-public-x963.txt
```

`configure` validates the HTTPS address and P-256 key, then atomically writes both settings to the ignored root `.env`, preserving unrelated settings:

| Build setting | Value |
| --- | --- |
| `RHAPSODOS_CATALOG_URL` | Public HTTPS address of the signed catalogue |
| `RHAPSODOS_CATALOG_PUBLIC_KEY` | Base64 uncompressed P-256 X9.63 public-key bytes |

Environment variables override `.env` independently, including explicitly empty values. CI can provide both directly. An empty URL disables remote refresh; a nonempty URL requires a valid key. **There is no default build key.** The tracked [release public key](../catalogue/catalog-public-x963.txt) only verifies the existing publisher's release. Neither build setting is secret; the private signing key must never enter Git or an app bundle.

Rebuild both apps after changing either setting; Android and normal Xcode builds consume the same generated configuration. A fork installed alongside the original app needs a separate app identity and app-data container, so signing authorities and revisions remain independent. Preserve existing user libraries.

## Publish an update

Keep a recoverable private-key backup and reuse that publisher's key. Package changed assets under new immutable edition IDs and URLs, sign the reviewed pack list with a higher revision, verify it, then replace the signed JSON at the existing catalogue address. Never edit the signed envelope by hand or let it replace its own trust key.

Compatible model updates at that address need no app release. To change the defaults shipped inside a future build, run `conda run -n dl python tools/model-catalog.py bundle --catalog /absolute/release/archive-packs.json`, then rebuild both apps. Bundling is optional for remote updates.
