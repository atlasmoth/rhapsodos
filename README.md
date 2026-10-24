# Rhapsodos

This project turns a PDF into an enjoyable listening experience. The original PDF follows and highlights the passage being spoken. Double-tapping a prepared passage pauses narration and opens an audio note attached there; the listener can save and resume while transcription waits for available resources.

The initial audience is everyday readers and people who benefit from accessible listening. Launch quality is English first, with multilingual document and speech contracts from the beginning.

## Current state

Implementation was authorized on **4 October 2026**. Native listening, source preparation, anchored notes, curated model downloads and local speech are implemented around a canonical KMP store. Git tracks work in atomic commits. Native integration and physical-device qualification determine release readiness.

Both apps use **`com.osuji.ereaderapp`**, version **`0.01`**, build/version code **`1`**. The display name is **Rhapsodos**.

Android debug/release APKs and its app bundle build successfully; iOS simulator Debug and unsigned device Release builds also pass. Native checks exercise local speech, source preparation, anchored notes, downloads, archive export/restore and failure recovery. See [Development](docs/development.md) for exact evidence, build locations and remaining qualification.

The documentation records the implementation, product decisions and remaining qualification. Check current primary documentation before changing dependency versions, model artifacts, licenses or prices.

## Documentation

| Document | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Instructions for future contributors and agents, including atomic commits, comments, validation and removal policy |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Shared KMP core, native ownership, document anchors, playback, notes, storage, privacy and optional services |
| [Product](docs/product.md) | Audience, listening and note journeys, scope and product guarantees |
| [Design](docs/design.md) | Five design references, visual direction and adaptive phone/tablet/window behavior |
| [UI quality](docs/ui-quality.md) | Rich haptics, Metal/AGSL effects, responsiveness budgets and sustained performance qualification |
| [Brand](docs/brand.md) | Unified identity, licensed fonts, light/dark palette and native launch animation |
| [Integration contract](docs/implementation-contract.md) | Actual shared commands, canonical JSON contracts and native download/asset responsibilities |
| [Android](docs/android.md) | Compose, Material3 Expressive, Navigation3, Material3 Adaptive, Media3 and native speech architecture |
| [iOS](docs/ios.md) | SwiftUI, NavigationSplitView, CoreML and native media architecture with an iOS 18 floor |
| [Models and evaluation](docs/models-and-evaluation.md) | Local and remote candidates, model gallery, costs, licensing and device qualification |
| [Model manager](docs/model-manager.md) | Explicit selections, catalogue signing/refresh, supported adapter profiles and release setup |
| [Model archives](docs/model-archives.md) | Four reproducible uploadable ZIPs, integrity checks and provider-independent HTTPS release binding |
| [Roadmap](docs/roadmap.md) | Delivery stages, measurable acceptance criteria and collaboration boundaries |
| [Development](docs/development.md) | Implemented workflows, build commands, measured evidence and release qualification |
| [Build artifacts](docs/build-artifacts.json) | Produced metadata, hashes, source revisions and exact native check associations |

Start with [AGENTS.md](AGENTS.md), [Product](docs/product.md) and [ARCHITECTURE.md](ARCHITECTURE.md), then read the relevant platform or feature document.

## Decisions already made

- Listening is the core experience. Reading primarily supports visual tracking and access to the original source.
- Voice-note ASR transcribes a saved recording attached to a frozen source position. Saving and resuming do not wait for transcription.
- Narration requires a downloaded and verified Phonon voice; system speech, Kitten and Kokoro are removed. Whisper transcribes saved notes after download on both platforms. Local-only content processing and curated speech packs are fundamental. Cloud processing remains a researched future capability behind separate consent.
- Kotlin Multiplatform shares domain, data and policy. Android and iOS retain native interfaces, audio, document rendering and inference adapters.
- Android uses Compose, Material3 Expressive, Navigation3, Ktor, Koin and Material3 Adaptive, with bounded native speech adapters. LiteRT remains a preferred route for future qualified model artifacts. iOS uses Swift, SwiftUI and CoreML, supporting iOS 18 and newer.
- Wide windows, tablets and foldables receive deliberate layouts that preserve the listening session.
- Rich haptics, native motion and GPU effects accompany a snappy interface with measurable performance budgets and accessible resource-aware fallbacks.
- Keep code comments necessary and sparse. Do not commit unit-test files. During this pre-release build, remove obsolete features without compatibility shims.
- Track work in coherent, validated atomic commits.

## Choices still to qualify

Physical speech quality, older-device behavior, complex document layouts, accessibility and sustained performance receive separate qualification. The implementation uses SQLDelight, a Kotlin/Native framework facade and an Android API 28 floor; exact build pins live in the version catalog. iOS now requires 18 or newer; downloaded voices retain device checks and physical qualification gates. No optional cloud backend is required or implemented.

The release gate is the complete loop on physical devices: import, prepare, listen, follow, pause, record, save, resume and recover, with comfortable sustained speech and accessibility validation.
