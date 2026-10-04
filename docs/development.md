# Development and current implementation

Implementation started on **4 October 2026**. This is an early `0.01` build, not a completed or device-qualified v1. Both apps use `com.osuji.ereaderapp` and build/version code `1`.

## Workspace

| Path | Responsibility |
| --- | --- |
| `shared/` | KMP canonical SQLite schema, faithful source pages, segmentation, frozen note anchors, progress and transcript records |
| `androidApp/` | Native Compose UI, PDF adapter, Media3 transport, system TTS and recording |
| `iosApp/` | Native SwiftUI UI, PDFKit, system speech, protected recording and on-device-only system ASR |
| `tools/` | Build/policy verification and inspection of actual app artifact metadata |
| `docs/` | Product, architecture, quality requirements and qualification status |

SQLDelight is the initial canonical-store implementation. Both native apps use the same shared schema and `ReaderCore` commands. The Swift facade uses JSON snapshots across the established Kotlin/Native framework bridge. Native code owns file access, audio and UI lifetimes; store operations run off the UI thread.

The initial system-speech path exposes passage precision. It does not claim sample-aligned word timing. A saved note freezes its source attachment, and a machine transcript remains separate from user-edited text.

iOS stores PDF and recording locators relative to its owned data root so an app update can relocate the container safely. Resolve only the known `Documents` and `Recordings` locations. Local data is excluded from automatic backup; the recording key is non-synchronizing and bound to this device. Intentional export, key recovery and restore remain release gates. Android also excludes its data from automatic backup and device transfer in this slice.

This first source revision retains each platform's raw page text. Passage ranges use UTF-16 code units, with page-local ranges and document ranges calculated as pages separated by `\n\f\n`. Normalized spoken whitespace is stored separately. These simple passages are not a qualified layout-aware reading-order graph or the complete versioned mapping system described in the architecture.

## Expanded shared store

The canonical store now supports append-only page preparation, OCR provenance and normalized source regions, compact versioned source-to-spoken mappings, local literal search, anchored bookmarks, genuine PDF outline destinations, spoken pronunciation corrections, rename/delete with a durable file-cleanup queue, and validated document archive export/restore. Existing source positions remain stable while later pages are appended or pronunciation changes. Schema upgrades add these records without removing existing recordings or edits. Native archive adapters own media copying and protection.

The curated catalog and generation-scoped install inventory distinguish downloading, paused, verifying, failed and ready states. Activation requires the complete pinned digest/size manifest. Catalog URLs contain only public assets; the shared content policy rejects document and recording uploads. Download managers and runtimes are native integrations and need their own verification beyond store checks. See [implementation-contract.md](implementation-contract.md) and [model-catalog.json](model-catalog.json).

Shared Android compilation and the iOS simulator framework link passed. A temporary real SQLDelight harness outside the repository passed 27 rejection cases, Unicode anchors, injected rollback, immutable incremental pages, source separator offsets, literal search, bookmark toggle, anchored archive round-trip, collision rejection, pronunciation/source fidelity, outline destination validation, stale model generations and invalidation, repair attempts, digest-manifest requirements and durable deletion cleanup. Those checks do not qualify native download resumption, microphone capture or physical-device inference.

## Building

Use JDK 17, the installed Android SDK and Xcode. The Gradle wrapper is pinned; dependency versions live in `gradle/libs.versions.toml`. Configure the SDK through a local, untracked `local.properties` or the supported environment configuration. Do not commit signing material or machine-specific paths.

```sh
./gradlew :androidApp:assembleDebug :androidApp:lintDebug
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
tools/verify.sh policy
tools/verify.sh android
tools/verify.sh ios
```

The iOS project is generated from `iosApp/project.yml` with XcodeGen. The normal Xcode prebuild integrates `SharedCore` through `embedAndSignAppleFrameworkForXcode`. An explicit prebuilt-framework option supports independent Swift validation against an already compiled framework; it must point to an actual matching platform/configuration artifact.

`tools/check-workspace.py` checks the source policy and can inspect a built APK or `.app` bundle for the required ID, version, build and OS floors. It also rejects an Android APK containing the `INTERNET` permission in this initial local-only slice. That permission check does not certify the behavior of a separate installed system speech engine. The tool does not certify performance, all privacy egress or audio quality. `tools/verify.sh` orchestrates real builds and artifact inspection. Builds that need cache writes, SDK components or simulator services require appropriate local tool permissions.

## Verification recorded on 4 October 2026

| Check | Executed result | Practical limit |
| --- | --- | --- |
| Maintained build workflow | `tools/verify.sh all` passed Android assembly/lint, shared iOS simulator framework linking, normal Xcode prebuild, Swift/Metal app compilation and actual artifact inspection | Debug builds; no distribution/signing or release performance qualification |
| Shared platform builds | Android main compilation and both iOS ARM64 device/simulator framework links passed | Framework compilation does not qualify physical-device behavior |
| Shared integration | Temporary harness used actual compiled shared classes and SQLDelight JDBC SQLite: 20 guard/rejection cases, frozen anchors, idempotent save, interrupted draft states, edited-transcript preservation, injected transactional rollback and six Unicode-safe passages passed | In-memory shared-store evaluation; native microphone/filesystem recovery was not exercised |
| Android emulator | Pixel 10 Pro Fold, API 37: real Files import, installed English narration in airplane mode, passage following, mic-denied typed note, exact canonical quote/range/offset and force-stop/relaunch persistence passed | Embedded-text fixture; no real microphone capture or device resource qualification |
| Android layouts/settings | Narrow, wide three-pane and 200% text layouts inspected; touch-feedback preference persisted after process termination | Visual checks do not certify TalkBack, physical haptics or all window/posture combinations |
| iOS simulators | iPhone 18 Pro/iOS 27.0 and iPad Pro 13-inch (M5)/iPadOS 26.3.1: real Files import produced 17 passages from three pages; narration, original-page highlighting, mic-denied frozen typed notes, save/resume and relaunch restoration passed | No iOS 17 runtime, real microphone capture, ASR accuracy or physical haptic qualification |
| iOS storage/layout | Canonical relative PDF locator and note quote/UTF-16 ranges checked; PDF/progress/notes survived relaunch; tablet library/reader/notes panes inspected | Key recovery, backup/export and interrupted recording need separate evaluation |
| Produced artifacts | Both actual app artifacts report `com.osuji.ereaderapp`, version `0.01`, build `1`; Android minimum/target API 28/37, iOS minimum 17.0; APK excludes `INTERNET` and `ACCESS_NETWORK_STATE` | Metadata and permissions are specific checks, not a complete privacy or OS-cohort certification |
| Repository policy | No unit-test source detected; Python/shell syntax, Markdown links/fences and staged whitespace checks passed | Temporary harnesses and synthetic fixtures stayed outside the repository |

Android lint finishes with **zero errors and four warnings**: the exported Media3 service, which filters untrusted controllers, and three KTX style suggestions. Kotlin reports the expected beta warning for `expect`/`actual` classes. These checks were not suppressed globally. No physical-device qualification was performed.

## First-slice behavior and remaining gates

Native work implements PDF import, a source-following listening surface, system narration, persistent progress, frozen anchored audio/text notes and transcript editing. Actual build and smoke results must be recorded separately from these intended behaviors.

Important remaining v1 work includes:

- Progressive document preparation and qualified reading order/OCR for the wider PDF corpus. The initial adapters do not prove reliable narration of scans or complex layouts, or complete listening support on every device at the deployment floor.
- Qualified compact custom voices and saved-file ASR packs, model catalog/download/update management and sustained resource scheduling.
- Android saved recordings currently retain a truthful pending-model state; no remote transcription fallback is implied. iOS system ASR only runs where on-device recognition is available and authorized.
- Parser isolation, full source geometry/alignment qualification and edition-aware audio preparation/cache management.
- Explicit encryption, backup/export/key recovery across platforms. Protected storage or a recording vault does not by itself establish an end-to-end encrypted sync/restore product.
- Interrupted capture, process termination and retry evaluation on actual devices, beyond code review and compilation.
- Accessibility participant feedback, older-device coverage, wide-screen interaction and sustained haptic/GPU/audio/memory/energy profiling.
- Distribution/signing and store metadata validation when publication is authorized.

Follow [ui-quality.md](ui-quality.md) and [roadmap.md](roadmap.md) for release gates. Do not describe an emulator launch or successful compile as a world-class v1. Track completed work in validated atomic commits; temporary checks remain outside the repository and no unit-test source is committed.
