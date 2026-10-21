# Uploadable model catalogue

Upload [catalogue.json](catalogue.json) unchanged to the public GitHub repository that will host catalogue updates. The file contains the signed envelope expected by both apps. Its payload lists the four current speech packs and their 43 pinned assets; the model weights remain at their existing download URLs.

- Revision: **1**
- Published: **21 October 2026, 03:33:58 WAT**
- Expires: **19 January 2027, 03:33:58 WAT**
- SHA-256: `16bb55f313b66bfbe4b1adcfff855b3419e3373d7ee8a92adea8f801dc809bd0`

[catalog-public-x963.txt](catalog-public-x963.txt) is the matching public verification key for app configuration. The private signing key is stored outside this repository at `~/.config/rhapsodos/catalog-signing/catalog-private.pem`, with owner-only permissions. Keep a secure backup for future catalogue updates; never upload that private key.

After upload, configure the raw GitHub HTTPS URL and this public key with the existing [release tooling](../docs/model-manager.md#release-setup), then rebuild both apps. Their current URL and public key configuration remain empty.

For future releases, edit the source pack list in [docs/model-catalog.json](../docs/model-catalog.json), increment the revision and sign with the same private key. Renew before expiry. Editing the decoded payload without signing it again invalidates the release.

The signature was verified with OpenSSL. Decoded content matches the source manifest exactly apart from explicit adapter defaults; pack totals, envelope bounds, dates and key permissions were checked. Hosting and app connectivity are verified after the public URL is available.
