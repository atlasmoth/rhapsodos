# Listening-first PDF app

This project turns a PDF into an enjoyable listening experience. The document view follows the passage being spoken; a listener can pause, record a thought, save it at that exact passage, and resume while the note is transcribed.

The initial audience is everyday readers and people who benefit from accessible listening. Launch quality is English first, with multilingual document and speech contracts from the beginning.

## Current state

This is a planning and architecture baseline, researched on **4 October 2026**. No application scaffold or production code has been implemented. The workspace was not a Git repository when these documents were created. Architecture proposals and performance targets are not evidence of measured device behavior.

The documentation records decisions from the product discussion and identifies choices that still need implementation experiments. Check current primary documentation before selecting dependency versions, model artifacts, licenses or prices.

## Documentation

| Document | Purpose |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Instructions for future contributors and agents, including atomic commits, comments, validation and removal policy |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Shared KMP core, native ownership, document anchors, playback, notes, storage, privacy and optional services |
| [Product](docs/product.md) | Audience, listening and note journeys, scope and product guarantees |
| [Design](docs/design.md) | Five design references, visual direction and adaptive phone/tablet/window behavior |
| [Android](docs/android.md) | Compose, Material3 Expressive, Navigation3, Material3 Adaptive, Media3 and LiteRT architecture |
| [iOS](docs/ios.md) | SwiftUI, NavigationSplitView, CoreML and native media architecture with an iOS 17 floor |
| [Models and evaluation](docs/models-and-evaluation.md) | Local and remote candidates, model gallery, costs, licensing and device qualification |
| [Roadmap](docs/roadmap.md) | Delivery stages, measurable acceptance criteria and collaboration boundaries |

Start with [AGENTS.md](AGENTS.md), [Product](docs/product.md) and [ARCHITECTURE.md](ARCHITECTURE.md), then read the relevant platform or feature document.

## Decisions already made

- Listening is the core experience. Reading primarily supports visual tracking and access to the original source.
- Voice-note ASR transcribes a saved recording attached to a frozen source position. Saving and resuming do not wait for transcription.
- Local-first operation, an enforceable local-only mode and curated downloadable speech packs are fundamental. Cloud processing is an explicit optional choice.
- Kotlin Multiplatform shares domain, data and policy. Android and iOS retain native interfaces, audio, document rendering and inference adapters.
- Android uses Compose, Material3 Expressive, Navigation3, Ktor, Koin, Material3 Adaptive and LiteRT. iOS uses Swift, SwiftUI and CoreML, supporting iOS 17 and newer.
- Wide windows, tablets and foldables receive deliberate layouts that preserve the listening session.
- Keep code comments necessary and sparse. Do not commit unit-test files. During this pre-release build, remove obsolete features without compatibility shims.
- Track work in coherent, validated atomic commits once the workspace is a Git repository.

## Choices still to qualify

Initial custom voice and ASR packs, Android minimum API level, exact dependency versions, the shared SQLite library, the Swift interop route, recording recovery format, local encryption/key recovery and optional cloud providers remain qualification decisions. The proposed structure deliberately allows those choices without replacing the source-anchor or note contracts.

The next implementation priority is the complete listening loop on physical devices: import, prepare, listen, follow, pause, record, save, resume and recover. A large model catalog or conversational assistant should not precede that experience.
