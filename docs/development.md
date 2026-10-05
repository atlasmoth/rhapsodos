# Development and verification

Rhapsodos has native Android and iOS apps with a shared Kotlin Multiplatform core. Both use `com.osuji.ereaderapp`, version `0.01`, build/version code `1`. Implementation and verification started on **4 October 2026**. Physical-device release qualification remains open.

## Responsibilities

| Path | Responsibility |
| --- | --- |
| `shared/` | Canonical SQLDelight schema, source pages/mappings, frozen anchors, progress, notes, search, bookmarks, pronunciation and model inventory |
| `androidApp/` | Compose/Material3 Expressive/Navigation3 adaptive UI, native PDF/OCR, Media3, capture, Ktor transfers and LiteRT speech |
| `iosApp/` | SwiftUI split navigation, PDFKit/Vision, native audio and protected capture, background URLSession transfers and CoreML speech |
| `tools/` | Build/policy checks, artifact metadata and pinned asset inspection |
| `docs/` | Product decisions, contracts, provenance and qualification evidence |

The shared store owns documents, source positions and notes. Native code owns files, PCM, inference, OS services and views. Harmless presentation preferences and generated caches are separate from durable user data.

Source ranges use UTF-16 code units, with page texts separated by `\n\f\n`. Spoken normalization and pronunciation corrections preserve source/display text and note anchors through compact versioned mappings. Audio attachments currently have **passage precision with a native transport offset**. Word/sample alignment requires further work and is not advertised.

## Implemented paths

| Journey | Implementation |
| --- | --- |
| Import | App-owned PDF, bounded progressive extraction, scan OCR, original-page access and preparation retry |
| Listen | Original PDF with active-passage highlights, system/installed or downloaded local voices, native playback controls, rate, progress, following and manual browsing |
| Remember | Tap the active PDF highlight to pause and freeze the source before capture; durable audio, typed notes, transcript editing and deferred local transcription |
| Navigate | Literal passage/note search, bookmarks, PDF outline destinations with page fallback, pronunciation corrections |
| Manage | Rename/delete, owned-file cleanup and portable document/audio/note archive export/restore |
| Offline speech | Curated platform catalog, explicit downloads, pause/resume, pinned verification, runtime checks, atomic activation, repair and removal |
| Presentation | Rhapsodos icon, licensed unified typography/palette, system/light/dark preference, native launch motion, haptic preferences and wide layouts |

See [implementation-contract.md](implementation-contract.md) for the actual shared commands. These entries describe code paths; the verification record determines which scenarios have been exercised.

## Speech, privacy and durability

The current app implements local-only content processing. App-managed HTTP transfers fetch explicitly requested public model assets. There is no cloud provider, account service, analytics client or sync backend. Installed system speech engines are separate OS services whose offline eligibility must be checked.

[model-catalog.json](model-catalog.json) pins every public asset. Android offers Kitten English narration and Moonshine English saved-note transcription. iOS offers Whisper tiny English transcription on the iOS 17 baseline and optional Kokoro CoreML voices on iOS 18+. Memory/OS guards constrain eligibility; physical qualification is pending.

Native managers verify all artifacts, check runtime execution, install atomically and update the shared generation-scoped inventory. Failed ASR retains the recording and its attachment. The iOS Whisper adapter overrides an upstream tokenizer fallback to require local assets even after a parsing failure.

Android now uses bundled Tesseract and a pinned English OCR model. ML Kit was removed after its documented diagnostic telemetry conflicted with the network boundary. See [third-party.md](third-party.md) for provenance and redistribution notices.

iOS recordings use authenticated encrypted chunks and a non-synchronizing, device-bound Keychain key. Android recordings use app-private OS-protected storage. Automatic backup/transfer is disabled. Explicit `.rhapsodos` export contains the original PDF, notes and portable audio in a plaintext ZIP archive. Restore verifies safe paths, bounds, digests and canonical anchors; iOS seals restored audio with the local vault key. This provides deliberate local export/restore, without a cloud key-recovery promise.

## Building

Use JDK 17, Android SDK and Xcode. Pins live in `gradle/libs.versions.toml` and the Swift package lockfile. Configure the SDK through an untracked `local.properties` or environment setting. Keep signing material and machine paths out of source.

```sh
./gradlew :androidApp:assembleDebug :androidApp:lintDebug
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:linkDebugFrameworkIosArm64
tools/verify.sh policy
tools/verify.sh android
tools/verify.sh ios
```

XcodeGen generates the project from `iosApp/project.yml`. Normal Xcode builds integrate `SharedCore` through `embedAndSignAppleFrameworkForXcode`. `EREADER_PREBUILT_SHARED=1` supports independent Swift validation against the matching framework in `shared/build/bin/`; rebuild shared source changes before using it.

`tools/check-workspace.py` checks actual app metadata, source policy, catalog consistency, font hashes and the bundled OCR checksum. Android intentionally includes `INTERNET` for public pack downloads. This inspection does not certify every network path or an installed speech engine. The iOS verification command uses ad-hoc simulator signing and disables explicit Swift modules for the qualified Xcode/KMP integration. Simulator entitlements use Xcode's simulated signing mechanism; unsigned compilation does not exercise Keychain capture protection.

The documented `tools/verify.sh ios` command also passed through the normal Xcode Gradle build phase without `EREADER_PREBUILT_SHARED`. That checks the ordinary project integration in addition to the independent Swift builds.

## Executed evidence on 4 October 2026

| Check | Recorded result | Limit |
| --- | --- | --- |
| Shared builds | Android compilation and both ARM64 iOS framework links passed after the current catalog and pronunciation changes | Compilation only |
| Shared SQLite integration | Temporary harness against compiled classes/SQLDelight passed 29 rejection guards, frozen/idempotent save, edit-preserving ASR, injected rollback, Unicode anchors, progressive source stability, mappings, search, bookmarks, outlines, pronunciation, archive round-trip, collisions, install generations, invalidation/repair and cleanup. Saved/transcribed recordings cannot be relabeled interrupted drafts | Native media/network checked separately |
| Pronunciation boundaries | Combining marks and connector punctuation preserve whole terms and unchanged source | English-first literal correction policy |
| Initial Android journey | Pixel 10 Pro Fold/API 37: Files import, airplane-mode system narration, following, denied-mic typed note and force-stop restoration passed | Earlier native slice; no physical microphone/device |
| Initial iOS journey | iPhone 18 Pro/iOS 27 and iPad Pro 13-inch M5/iPadOS 26.3.1: Files import, narration, source highlighting, frozen typed note and relaunch restoration passed | Earlier native slice; no iOS 17 runtime or real microphone |
| Android LiteRT | All eight voices produced finite nonzero PCM. 1,200 characters produced 120.23 s audio in 15.302 s cold and 14.282 s warm; repeated runs remained bounded | Emulator CPU measurements, not listener ratings or true streamed output |
| Android memory | Long requests loaded roughly 712–720 MiB PSS; closed engines about 110 MiB in the recorded run | Conservative 3 GB narration-pack floor, not qualification of a physical 3 GB phone |
| Android ASR | Public 4.55 s sanity clip transcribed in 246 ms warm; repeated 13.65 s clip retained all three sentences in 591 ms after overlap correction | Synthetic English; proper-name, currency, accent and noise accuracy remain limited |
| Android model/note integration | 15 native assertions passed: actual download pause retained 16,375 bytes, resume/verification activated the pack, corruption invalidated it and retry repaired it. Saved-note ASR preserved the recording hash, frozen anchor and manual edit | Earlier `0.01` APK; its hash was not captured before replacement |
| Android portable archives | 19 native assertions passed: iOS archive restored/exported with exact canonical JSON and raw PDF/audio bytes; unsafe path, wrong hash, truncated audio, undeclared entry and duplicate entry rejected without library mutation or staging residue | The archive-tested APK hash is recorded separately from the earlier manager checks |
| English narration symbols | Android actual frontend/inference checks cover nine common operators, currency, negative numbers and word hyphens; unsupported operators fail explicitly | Literal readings only; no mathematical interpretation or complete pronunciation corpus |
| Android isolated PDF | 22 actual checks passed: narrow-column embedded/scanned order, UTF-16 geometry, rotated/cropped text, bounded rendering, distinct isolated UID, worker death/recovery and idle/memory/background release | Broader complex-layout corpus and oldest supported hardware remain to qualify |
| Android capture limits/recovery | 15 synthetic native checks passed for the 30-minute budget, sample alignment, storage reserve, interrupted finalization, retry, empty draft rejection and oversized draft preservation | No microphone opened; physical capture remains a release gate |
| Android capture races | 14 assertions across six scenarios passed: save/cancel reservation, writer join, empty-running discard, failed deletion with retained retry, stale service start and canceled quiet-wait start | No microphone object was created or opened; exact APK association is recorded below |
| Android playback/cache | Seven native checks preserved the original 1,200 ms reading position, 700 ms note replay and 900 ms frozen source anchor through previews/voice selection. Actual installed offline system TTS regenerated a damaged cache | Enhanced-voice cache run was correctly refused on the under-3 GB emulator |
| Android ASR/removal lifecycle | Six native checks passed for memory deferral/wake, cancellation, retry, manual edits and suspended removal/download reservation | Synthetic saved speech and emulator lifecycle |
| Android removal failure | Twelve native checks passed: denied file deletion retained bytes, inventory, error and retry; successful retry removed every owned version/staging file before clearing inventory. Documents, notes, progress and eleven PDF/recording files stayed unchanged | Exact prior APK association below; no microphone |
| Android strict durability | Twenty-two native checks passed: a directory-sync failure after WAV rename retained the final recording and unsaved draft; retry saved once at the same 1,700 ms anchor offset. Actual PDF import/extraction and archive restore retained text, geometry, notes, audio and progress | Final debug APK; synthetic one-second recording and permission fault, no microphone |
| iOS protected audio | Actual Keychain/vault checks round-tripped 4,550 ms; partial header/body and whole-block truncation rejected. Interrupted draft recovered a 4,437 ms authenticated prefix | Synthetic public audio; physical microphone/lock-screen behavior pending |
| iOS recovered-note journey | A truncated draft produced a durable strict-format recovered asset, preserved the original, committed the 4,437 ms note at its frozen 1,750 ms offset and passed strict decryption, Whisper, archive export/restore and owned-file cleanup with the transcript edit intact | Synthetic interruption; physical capture remains separate |
| iOS Whisper | Native public transfer activated the pinned local pack; real 4.55 s speech decoded in approximately 0.89 s | The word “Kitten” was misrecognized; ASR output remains editable |
| iOS extraction/OCR | Embedded narrow-column PDF and Vision two-column/rotated scans retained the expected reading order and source geometry | Broad tables, footnotes and layout corpus pending |
| iOS model recovery | Eight native delegate-fault assertions passed for bad resume-data purge/fresh retry, size overflow/cancellation, temporary move failure/cleanup and paused checksum work/state preservation. A complete orphaned installation was verified and runtime-checked; damaged generated audio regenerated | Network/background interruption on physical devices remains to qualify |
| iOS enhanced voice | Replacement FP32 adapter produced all three voices on iOS 18.4 and 27. A varied 310-character passage retained its ending at two rates, producing 21.775–27.45 s in approximately 9.5–13.9 s. Fixed-buffer duration overflow rechunked without token/audio truncation; truncated WAV payloads regenerated. Literal brackets retained their contents; unsupported mixed-script words failed explicitly | Simulator CPU execution; ASR errors and repetitive text remain imperfect. Physical sustained listening remains a release gate |
| iOS long voice/lookahead | A 1,200-character passage produced 82.2 s audio in approximately 30.8 s on both runtimes. Actual CoreML next-passage preparation during playback gave measured cached handoffs of approximately 55.3 ms; capture canceled/joined lookahead | Simulator workload, not a fleet latency guarantee or fully streamed synthesis |
| iOS voice memory | Final runs peaked at approximately 221 MiB physical footprint and fell to 56–60 MiB after unloading | Host simulator measurement, not physical phone peak RAM; conservative 3 GB eligibility floor remains |
| iOS audition | Voice samples preserved the paused book position and a saved note paused at approximately 700 ms, which resumed beyond 1,000 ms, with no sample-clock writes to canonical reading progress | Native synthetic playback; physical routes remain separate |
| iOS final pack lifecycle | All nine pinned assets passed native verification/activation. Stale ready files were rejected; injected removal failure retained failed/unqualified inventory, and retry cleaned all owned versions/staging | Native iOS 18.4/27; real background network/lock interruptions remain physical gates |
| iOS typography | Registered variable Space Grotesk uses explicit weight 400; a 24 pt title scales to 48 pt at accessibility XXXL | Native font measurement; full screen-reader/large-text interaction remains separately inspected |
| Brand contrast | Ink/background, muted/surface, primary/background, primary/container and native filled-action label pairs exceed 4.5:1 in both palettes | Static token calculations; contrast over materials, disabled states and complete accessibility journeys remain separate |
| iOS transport/archive | Generated playback pause froze at 765 ms, seek reached 1,000 ms and playback completed. Android archive restored/re-exported with exact canonical JSON | Native CLI integration, not physical route or listener qualification |
| Build/policy | Android debug/release APKs, AAB and lint passed; simulator Debug and unsigned ARM64 iOS Release compiled. Produced metadata matches Rhapsodos, `com.osuji.ereaderapp`, `0.01` and build `1`; iOS plist/Mach-O minimum is 17.0. All seven ARM64 Android native libraries meet 16 KiB packaging/load alignment | Lint has zero errors and eleven existing warnings; distribution signing and physical qualification remain |

Temporary checks and synthetic fixtures remain outside the repository. No unit-test source is added or committed. Android PSS and iOS physical-footprint measurements are different metrics and are not directly comparable.

## Executed evidence on 5 October 2026

The reader now shows only the original PDF. Active-passage highlights provide the pause-and-record action, with source semantics retained inside the PDF surface. The playback dock and supporting notes/tools remain available. Expected document, passage and revision identity is checked before synchronously pausing and reserving the native position; asynchronous preparation and permission handling retain that frozen attachment.

| Check | Recorded result | Limit |
| --- | --- | --- |
| Shared geometry migration and repair | Seventy-two checks against compiled ReaderCore and actual SQLDelight/SQLite passed: schema upgrade preserves old rows; validated geometry-only replacement preserves source, attachments, edits, bookmarks, pronunciation and progress; malformed geometry is rejected; a real SQL-trigger failure rolls back; portable export omits ambiguous rectangles without changing stored data | Temporary JVM/native SQLite harness; native PDF coordinate adapters checked separately |
| Android PDF geometry | Sixteen checks against the actual drawing/hit-placement helper passed for narrow/wide fit, letterboxes, blank space, disjoint regions, zoom/pan, viewport clipping and rotated display | Native helper execution; real Compose pointer dispatch remains separate |
| Android canonical geometry and repair | Sixty-two additional native checks passed against the final APK: actual isolated PdfRenderer raster ink overlaps transformed line bounds at 0/90/180/270 degrees with offset crop boxes; canonical embedded boxes agree across rotations; rotated OCR is upright; literal embedded outer-trim repair succeeds while OCR trim/text mismatch refuses; geometry repair retains source, anchors, progress and file bytes | Synthetic PDFs and existing public validation fixture; complex-layout/font corpus and physical hardware remain separate |
| Android tap state | Six checks passed for short single-pointer eligibility and latched rejection of holds, extra fingers, consumed events, movement beyond slop and invalid timestamps | Compiled gesture wiring and native state helper; physical gestures remain unqualified |
| Android capture attachment | Eighteen native Media3 checks passed: wrong/stale targets, preview/replay and unprepared media refuse capture; accepted input pauses at 1,700 ms, repeated input refuses a second reservation, permission handoff retains the same offset, and a boundary switch freezes only the actual new passage | Synthetic silent audio, isolated temporary canonical database, no microphone; permission dialog/recreation UI not driven |
| Android build/policy | Strict offline Debug, unsigned Release APK, AAB and lint passed. All seven ARM64 libraries retain 16 KiB alignment in both APKs; produced identity remains Rhapsodos / `com.osuji.ereaderapp` / `0.01` / `1` | Zero lint errors and eleven existing warnings; signing and physical-device gates remain |
| iOS PDF geometry and interaction | Actual PDFKit checks passed for crop/zoom and all four rotations, individual painted lines, gaps/margins, inactive pages, stable passage/word layers, stale touches, overlapping links/widgets, native selection, gesture precedence and accessible excerpt/actions | Production component methods executed on iOS 18.4; no injected finger or VoiceOver session |
| iOS portable geometry and repair | Expanded native checks passed for tagged-region gating, actual rendered ink overlap across all rotations/crop offsets, explicit ToUnicode supplementary UTF-16 endpoints, embedded whole outer-trim source mapping, exact OCR mismatch rejection, geometry-only data preservation and safe ambiguous-region export/restore followed by native repair | Actual PDFKit/shared store in a temporary native harness; current consumer interaction is recorded separately |
| iOS capture attachment | Native integration verified synchronous pause/reservation at the native clock, rejection of a rapid second tap or stale source, one canonical frozen draft, late-source-change cancellation, preview/replay exclusion and restored eligibility after discard | Only hardware `startCapture` was replaced with a permission-denied probe in an external source copy; no microphone used or OS permission dialog exercised |
| iOS builds | Current simulator Debug and unsigned ARM64 Release passed against newly rebuilt matching SharedCore frameworks; final Debug installed and launched on the preserved phone and iPad simulators | Produced metadata retains Rhapsodos / `com.osuji.ereaderapp` / `0.01` / `1` / iOS 17; iOS 17 runtime and physical devices remain gates |
| Cross-platform source review | Independent review verified removal of the separate passage pane, expected-source guards, synchronous pause/reservation, accessibility alternatives and preservation of PDF browsing gestures | Source review supplements native execution; it does not qualify screen-reader usability |

The final Android artifact passed **102 focused checks**: the 40 drawing/tap/capture checks were rerun alongside 62 rotation/repair checks. No microphone was opened; source text, notes, progress, PDFs and recordings were preserved. One retained public OCR fixture received geometry-only repair during an earlier permissive whole-trim run whose APK digest was not captured; final checks verify its already tagged drawing and the current stricter OCR mismatch refusal, rather than attributing that earlier repair to the final binary. The final iOS native harness passed **111 assertions**, including the original 82 PDF/capture checks and the geometry/repair additions. The shared geometry harness passed **72 checks**. Report hashes and artifact associations are recorded below. The 4 October checks remain historical evidence for their recorded source/artifacts.

## Build artifacts and evidence association

The complete metadata, SHA-256 hashes and exact check associations are in [build-artifacts.json](build-artifacts.json). Build outputs stay outside Git.

| Output | Location | Signing |
| --- | --- | --- |
| Android debug APK | [androidApp-debug.apk](../androidApp/build/outputs/apk/debug/androidApp-debug.apk) | Development key; installed on the development emulator |
| Android release APK | [androidApp-release-unsigned.apk](../androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk) | Unsigned; owner signing required |
| Android app bundle | [androidApp-release.aab](../androidApp/build/outputs/bundle/release/androidApp-release.aab) | Owner distribution signing required |
| iOS simulator app | [Rhapsodos.app](../iosApp/build/Debug-iphonesimulator/Rhapsodos.app) | Ad-hoc simulator signing; installed on phone/tablet simulators |
| iOS ARM64 release app | [Rhapsodos.app](../iosApp/build/Release-iphoneos/Rhapsodos.app) | Unsigned; owner team/provisioning required |

Native checks ran on several successive development artifacts. The record preserves their exact hashes rather than assigning historical checks to the latest binary. The current Android artifact received the 102-check PDF-highlight/capture/geometry run. The 4 October strict-durability/archive, preview, removal-failure and capture-race runs retain their earlier associations. The iOS speech adapter was exercised by external native harnesses on iOS 18.4 and 27 on 4 October. Its current PDF/capture components received the 111-check external native run on iOS 18.4; consumer Debug/Release builds are recorded separately.

Native UI inspection previously exercised import, listening, following, typed notes and restart on phone/tablet surfaces. Earlier inspection also confirmed the Rhapsodos icon, light/dark appearance and settings. On 5 October, CUA inspected the final Android APK on the unfolded API 37 emulator: the main reader contains only the PDF, rotated text and its highlight align, narration follows pages, blank space does not open a note, and an active-highlight tap reaches the OS microphone prompt. Denying permission shows the typed-note fallback attached to the expected page and passage; the empty verification note was canceled. No microphone access was granted or recording opened. The fold transition reached a locked emulator surface, so the current compact reader is not visually qualified. The final iPad consumer was visually inspected in a light wide layout: original PDF, aligned active text highlight, library sidebar and playback dock are visible, with no separate reading pane. Earlier iOS preview attempts timed out; final CUA input attempts returned `AXError.cannotComplete`, so current consumer tap interaction is not claimed. Static palette checks and native font measurements do not replace assistive-technology or physical-device qualification.

## Release qualification

Complete real compilation, lint, model execution and relevant failure/recovery checks before atomic commits. Record UI inspections separately; a locked host can block native UI automation while build/model checks continue.

Physical gates include microphone/route interruptions, locked-screen endurance, oldest supported devices, haptics, thermal/battery behavior, accessibility participants and sustained listening quality. Distribution needs the owner's signing configuration and separate publication authorization. [ui-quality.md](ui-quality.md) and [roadmap.md](roadmap.md) define the release bar.
