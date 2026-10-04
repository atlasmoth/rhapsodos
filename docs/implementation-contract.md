# Native integration contract

The shared `ReaderCore` is the canonical synchronous store. Native adapters call it from their existing serialized repository and decode JSON with unknown-key tolerance. Native files, audio transport, downloads and inference remain platform owned. These signatures are the implementation contract for the current build.

## Library and source

- `beginDocument(id,title,filePath,pageCount)` creates a preparing document. `appendDocumentPages(documentId,pagesJson,finished)` accepts ordered contiguous new pages, preserving all earlier text and offsets. Finalization requires the declared page count. Both return the current document snapshot; native UI can listen to already prepared passages while preparation continues.

- `bookmarksJson(documentId)` returns an array of `{id,documentId,passageId,label,createdAt}`. Empty document ID returns all.
- `toggleBookmark(bookmarkId,documentId,passageId,label)` returns that document's bookmark array. One bookmark per passage; calling again removes it.
- `searchJson(documentId,query)` returns up to 100 `{kind,id,documentId,passageId,title,text,pageIndex}`. Empty document ID searches the library. Search is literal and local.
- `renameDocument(documentId,title)` updates the canonical title.
- `deleteDocumentJson(documentId)` and `deleteNoteJson(noteId)` return `{paths:[...]}`. Stop playback/capture for the affected document before invoking deletion. Files become pending garbage in the same transaction; native adapters remove only owned files and call `acknowledgeFileRemoval(path)` after successful removal. `pendingFileRemovalsJson()` recovers cleanup after a crash. Never delete a current recording.
- `exportDocumentJson(documentId)` returns a versioned archive manifest containing the document, source pages, notes, bookmarks and position. Native exporters package its referenced PDF and durable recordings; generated narration and models are excluded. iOS vault recordings must be exported as decrypted audio and re-encrypted on restore, never export a device Keychain secret.
- `restoreDocumentJson(archiveJson)` atomically validates and restores the manifest into a vacant document ID. Native importers copy/verify owned files first and replace manifest file locators before calling this method. A collision is rejected without replacing existing data. Do not use platform absolute paths in exported archives.

`SourcePage` gains `provenance` (`embedded` or `ocr`), `languageTag` (default `en`) and `regions`, an array of `{start,end,x,y,width,height}`. Region ranges are page UTF-16 offsets; coordinates are normalized top-left page coordinates. `Passage` includes `displayText`, `languageTag`, `mappingVersion`, `spokenMapping` with `{spokenStart,spokenEnd,sourceStart,sourceEnd}` page-relative ranges, and `provenance`. Embedded PDF selection and OCR regions are different geometries. `documentJson` includes `pages` and page-based `sections` with `{id,title,passageId,pageIndex}`. Do not fabricate heading detection.

## Downloadable speech

- `modelCatalogJson(platform)` returns curated immutable entries: `{id,name,capability,platform,runtime,version,languageTags,minimumOS,minimumMemoryMB,license,licenseUrl,description,files:[{path,url,sha256,bytes}],totalBytes}`. Native adapters enforce OS, storage and memory compatibility before recommending an entry. Qualification is separate from compatibility.
- `modelInstallsJson()` returns `{packId,generation,status,bytesDownloaded,totalBytes,installedPath,error,updatedAt}` records.
- `beginModelInstall(packId,generation)` starts/restarts an attempt and returns its record. A ready pack is retained until its replacement verifies. The generation is a fresh native UUID per attempt.
- `updateModelInstall(packId,generation,status,bytesDownloaded,error)` accepts `downloading`, `paused`, `verifying`, `failed`. Stale callbacks are ignored. Status must never claim readiness.
- `activateModel(packId,generation,installedPath,verifiedFilesJson)` accepts an owned relative path `Models/<packId>/<version>` and the complete array of `{path,sha256,bytes}` matching the pinned catalog. Native code hashes every file, fsyncs and moves the staging directory atomically, and performs a runtime smoke check before activation. Only then does the canonical record become `ready`.
- `removeModel(packId)` removes inventory; native code unloads the runtime and removes owned model files. This never removes recordings or notes.

HTTPS GET requests are restricted to catalog assets and their audited CDN redirects. Range resumption verifies byte counts and hashes; rejected ranges restart the individual staging file. No document text, recording, transcript or filename belongs in a request, diagnostic or URL. Downloads are explicit, cancelable and resumable. Runtime libraries must receive local paths with their automatic download behavior disabled. A downloaded pack only becomes selectable after successful validation.

Both apps use `com.osuji.ereaderapp`, version `0.01`, build `1`, and the display name **Rhapsodos**.
