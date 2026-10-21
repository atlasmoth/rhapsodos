# Delivery roadmap and qualification

Status: core listening, notes, preparation and downloadable speech are implemented; the acceptance gates below continue to guide qualification, 4 October 2026. Numerical values below are engineering targets; none are reported as measured app results. See [development.md](development.md) for executed evidence and current boundaries. Follow [AGENTS.md](../AGENTS.md), including atomic commits and the prohibition on committing unit-test source.

## Sequence

Deliver the complete listening loop before expanding the model catalog or introducing a conversational assistant. Stages are ordered by uncertainty and product dependence, not a promised calendar.

| Stage | Deliverable | Exit evidence |
| --- | --- | --- |
| 0. Evidence and contracts | Representative document corpus, voice/ASR bakeoff, source/anchor contracts, compact and wide journey designs | Explicit supported cohorts and failure cases; one coherent cross-platform contract; initial model shortlists with license/runtime evidence |
| 1. Source and local state | Native import/render/extract/OCR feeding the shared graph, deterministic normalization and canonical persistence | Reading order/source ranges verified against gold references; resume/anchor behavior survives restart and PDF zoom/page browsing |
| 2. Listening and remembering | Native persistent audio, media controls, following, recoverable recording, save/resume and asynchronous ASR | End-to-end journey on physical devices, including interruption, denied permission, low storage, termination and delayed ASR |
| 3. Qualified offline experience | Curated packs, resumable verified installation, bounded resource scheduling and content preparation | Sustained supported-device playback, genuine airplane-mode journeys and installation/update recovery; truthful offline readiness |
| 4. Launch quality | Accessibility participants, English quality, adaptive polish, voice quality and output volume and storage clarity | No known release-blocking source loss, note loss, privacy fallback or supported-device playback failure; evidence recorded |
| 5. Measured expansion | Additional languages, larger packs, optional cloud quality tier, sync and later assistance | Each capability has a concrete user benefit and its own quality/privacy/cost gates |

Begin cloud evaluation early enough to compare voice quality and costs, but do not make a backend a prerequisite for the local listening/capture slice. Optional features can ship after the first release if their complexity threatens that core.

## First vertical slice

Use a real long PDF with usable embedded text and another that needs OCR. Import, prepare the current passage, generate/play a segment, follow its source, pause and record, commit the note, resume, transcribe later and recover after restart. Exercise both native apps against the same canonical contracts.

This slice should resolve the shared SQLite library, Swift bridge, Android API floor, source-range/geometry convention, native transport ownership and recording recovery format. It is a qualification implementation when authorized, not justification to build every proposed abstraction first.

Create coherent atomic commits for the source contract, platform adapter, listening behavior and capture behavior. Include documentation updates alongside the change that establishes the decision. Do not produce one large “initial everything” implementation commit.

## Evaluation corpus

Build a proposed launch corpus of roughly **200–300 English PDFs**, using public-domain, appropriately licensed or consented material. Include everyday books and long-form nonfiction alongside documents with technical layout challenges. Keep a manageable representative subset for each routine check.

Cover embedded text, scans, multi-column layouts, footnotes, running headers, captions, tables, equations, rotated/cropped pages, unusual fonts/encoding, very large files and mixed page quality. Include Unicode edge cases even before multilingual launch.

For the gold subset, annotate reading order, expected spoken content, deliberate policy exclusions and source highlight/anchor positions. Measure omission, duplication and ordering independently. Record extraction confidence and whether the app identifies difficult cases rather than quietly speaking incorrect text.

Voice evaluation uses the same normalized passages across providers. Include names, abbreviations, dates, numbers, quotations, dialogue and long sections. Use listening participants and blinded comparisons where practical; judge comfort and intelligibility over long sessions rather than one attractive sample.

ASR evaluation uses consented notes with varied accents, background noise, Bluetooth microphones, quiet speech, interruptions and terminology. Judge edit burden, preserved meaning and failure/retry behavior as well as word error rate. Do not upload private corpus material to cloud providers without the corresponding consent.

## Device matrix

| Cohort | Why it matters |
| --- | --- |
| Budget Android devices at the chosen supported API floor | Limited memory/storage and weaker sustained CPU performance |
| Midrange Android devices across relevant SoCs | Likely large share of daily use; accelerator/operator differences |
| Flagship Android and tablets/foldables | Enhanced packs, wide windows, posture and media routes |
| Older physically available iOS 18-supported iPhones | Baseline OS coverage and constrained memory/thermal behavior |
| Newer iPhones | Enhanced packs and availability-gated newer APIs |
| iPads and resizable windows | Split/inspector behavior, keyboard, large type and multitasking |

Select exact device models before calling a cohort supported. Record OS build, runtime/model artifact versions, cold/warm state, playback speed, temperature, memory pressure and workload. An emulator or a macOS model benchmark does not qualify a phone.

The app can support a device while a particular enhanced pack does not. Make that distinction explicit in the catalog. Basic note recording still works when ASR is deferred or unavailable.

## Proposed quality gates

The UI must also pass [ui-quality.md](ui-quality.md): intentional haptics, qualified Metal/AGSL effects, immediate local feedback, frame-budget profiling and resource-aware fallback. Versions start at `0.01`/build `1` for both apps, with ID `com.osuji.ereaderapp`. Atomic commits track implementation and qualification evidence.

| Area | Target and method |
| --- | --- |
| Cached playback start | Proposed p95 below 0.5 seconds on qualified cohorts, measured from user action to audible output |
| Warm local generation start | Aim around 2 seconds for the qualified interactive path; define model, passage length and readiness state before comparison |
| Continuous playback | Multi-hour, initially 2–4 hour, sessions without known repeatable crashes, underruns or progressive alignment drift under supported conditions |
| 2× synthesis | Sustained real-time factor below 0.5 with headroom; target approximately 0.3–0.35 where streaming at that speed is promised |
| Source integrity | Review gold corpus omissions/repetitions/order and source mappings; no unresolved systematic defect in the declared supported corpus |
| Capture durability | No lost committed recording, moved anchor, duplicate completion or overwritten user transcript in defined fault scenarios |
| Offline operation | Prepared passages, resume, seek and raw note capture survive airplane mode and relaunch; deferred jobs remain explicit |
| Privacy | Controlled network observation confirms local-only content does not leave the device, including retry/failure paths |
| Model installation | Interrupted download, wrong hash, insufficient space, failed compilation/update and process termination leave a safe usable state |
| Wide layout | Audio, source position, recording and edits survive repeated resize/collapse/rotation with large text |
| Accessibility | Core journey completed with TalkBack/VoiceOver and relevant keyboard/switch/voice-control use, including narration coexistence |

These gates need actual measurements and workload definitions. Some models may qualify only for advance preparation, rather than live 2× generation. Report that as a supported mode, not as a failed promise hidden behind a fast flagship result.

Measure memory, energy and thermal behavior beside playback quality. Compare to system speech and a similar cached-audio workload on the same device. Do not promise a universal battery percentage from isolated tests. Bound combined PDF and inference resources and verify recovery after pressure.

## Required failure scenarios

- Pause/capture during generation, rapid repeated commands, denied microphone permission and canceled notes.
- A phone call, Siri/assistant, competing audio, headphone removal, Bluetooth route changes and media-service restart.
- Termination during recording, finalization, database commit, ASR and model activation; relaunch reconciles journals and files.
- Low storage, corrupt/missing cached segment, incomplete model file and unavailable selected voice.
- Delayed ASR followed by user transcript edits; retries neither duplicate notes nor replace the edit silently.
- Voice, normalization or graph changes; original note/source context remains available and ambiguity is surfaced.
- Network loss after explicitly selecting cloud; strict local-only failure never enables remote processing.
- Incoming synced progress while locally playing, conflicting transcript edits and deletions, if sync is implemented.

## Verification without committed unit tests

Use compile/static checks and appropriate integration/end-to-end/manual/device evaluation for implemented work. Temporary unit checks may live outside the repository and must not be committed or left as repository source. Do not add a large test scaffold before there is behavior to verify.

Representative evaluation assets, benchmark specifications and reproducible integration tooling are distinct from unit-test source and can be considered when useful. Use licensed material and exclude private recordings. Store large artifacts outside Git when appropriate; commit a manifest and evaluation method rather than a pile of binary outputs.

Record results with the exact commit, device, OS, model/runtime, scenario and metrics. Distinguish measured facts, targets and anecdotal observations. Repeat checks after relevant changes or failures, not simply to fill a checklist.

## Future agent collaboration

| Workstream | Primary ownership | Coordinate before changing |
| --- | --- | --- |
| Shared domain/data | Graph, offsets, anchors, narration editions, repositories and jobs | Both platform adapters and schema owner |
| Android | Compose/adaptive navigation, document/audio/capture/LiteRT and OS lifecycle | Shared contracts, pack/runtime and privacy policy |
| iOS | Swift facade, SwiftUI, PDFKit/Vision, audio/capture/CoreML and OS lifecycle | Shared contracts, availability and crypto/background policy |
| Speech qualification | Model artifacts/frontends, licensing, alignment and device evidence | Native runtime owners and catalog manifest |
| Experience/accessibility | Listening and note journeys, adaptive compositions and semantics | Native UI owners; never independently redesign the underlying source model |
| Optional backend/sync | Provider adapters, account/quotas, retention, outbox/conflicts and key design | Shared repositories, privacy and native background transfers |

Assign disjoint files/components for simultaneous edits and define the interfaces first. Use small reviewable handoffs. The integration owner checks cross-platform agreement, validates the affected journey and ensures atomic commits contain only the intended change. Avoid multiple agents staging the same shared checkout.

Every completed implementation handoff should include the concrete behavior, files/interfaces changed, evidence actually collected, known device/model limitations and updated decisions. Do not pass architectural speculation to the next agent as a tested guarantee.

## Remaining qualification decisions

1. Which compact voice wins long-session preference and meets the baseline device budget on each platform?
2. Which compact ASR packs and languages are supported locally at launch?
3. Which PDF extraction/rendering limitations require alternate engines or optional remote preparation?
4. Does the implemented SQLDelight store and Kotlin/Native JSON facade meet sustained physical-device concurrency, recovery and performance requirements?
5. Do bounded Android PCM and the iOS authenticated chunk vault preserve recordings across physical microphone, route, lock-screen and low-storage interruptions? Portable archive export/restore and synthetic recovery already have executed evidence.
6. Which capabilities qualify across the initial Android API 28 floor, and do the current dependency pins satisfy audience reach without fragile API assumptions?
7. Does optional cloud narration provide enough quality gain to justify its price and privacy tradeoff?

Resolve these with a bounded experiment and record the decision in the owning document and an atomic commit. During this build, delete abandoned paths rather than maintaining compatibility layers for speculative alternatives.
