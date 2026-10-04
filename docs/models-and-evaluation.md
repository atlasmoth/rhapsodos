# Models and evaluation

Research snapshot: **4 October 2026**. The implementation inventory below is separate from the broader candidate research. [Development](development.md) records executed checks. Provider catalogs/prices and physical-device readiness require continuing qualification.

## Current embedded catalog

| Platform | Capability | Pinned implementation | Download | Eligibility guard |
| --- | --- | --- | --- | --- |
| Android | Local English narration, eight voices | Kitten nano 0.8, LiteRT 2.1.5, local dictionary/G2P frontend | 62,887,019 bytes | API 28+, 3 GB device memory |
| Android | Saved-note English transcription | Moonshine tiny 5-second INT8 LiteRT, tokenizer and public runtime sanity clip | 54,140,870 bytes | API 28+, 1 GB device memory |
| iOS | Saved-note English transcription | Whisper tiny.en CoreML, WhisperKit 0.18.0 and exclusively local tokenizer | 79,066,059 bytes | iOS 17+, 2 GB device memory |
| iOS | Enhanced English voice preview, three voices | Pinned end-to-end FP32 Kokoro CoreML export and bundled local Misaki/BART pronunciation frontend | 325,316,220 bytes | iOS 18+, 3 GB device memory; physical qualification pending |

Exact URLs, bytes and SHA-256 values are in [model-catalog.json](model-catalog.json), with licenses in [third-party.md](third-party.md). These guards prevent known incompatible choices; they are not device-fleet qualification. The shipped catalog is embedded in the app rather than downloaded from an unsigned remote index. System narration and raw note capture remain independent of packs. Future remote catalog updates require their own signing contract.

Android packs also require a supported native ABI: `arm64-v8a`, `armeabi-v7a` or `x86_64`. An x86-only or unidentified ABI keeps the built-in reading/capture path available while withholding these packs. Runtime activation waits for foreground/idle resources on both platforms, even when asset transfer has completed.

Real Android ARM64 emulator execution revealed roughly 693–720 MiB loaded speech PSS despite the compact download. The voice uses bounded synthesis chunks and releases idle ASR; the initial memory guard is conservative. Physical memory peaks, battery behavior and listening comfort remain unmeasured. See the dated execution record before promoting a pack.

The initial Jud two-stage Kokoro export failed native simulator intelligibility checks and has been replaced. The replacement's inspected compiled graph is FP32 with fixed 128-token input and five-second waveform capacity, despite stale FP16 wording in its model card. Its output reports the unclamped predicted duration. The adapter rejects overflow and splits source chunks before accepting audio, without truncating tokens or clamping away speech. Native iOS 18.4 and 27 checks produced speech for all three voices and varied 310-character passages at two rates retained their final sentence under local ASR. This establishes a usable preview path, not perfect transcription or physical listening quality. See [development.md](development.md) for complete adapter, cache, lifecycle and memory evidence. [Pinned export and license](https://huggingface.co/aufklarer/Kokoro-82M-CoreML/tree/f8ff771e4cab0bb3368e8af3a090a7e847485401)

## Product constraints

Listening is the primary experience. The document view helps the listener track their place. Continuous narration needs faithful text, pleasant sustained delivery, accurate source alignment and dependable background playback.

ASR serves **recorded voice notes**, not continuous voice commands or conversational chat in v1. Pause narration, freeze its source anchor, persist the recording and attach the note, then transcribe asynchronously. Resume listening independently of transcription. A failure leaves an attached playable recording with a pending or failed transcript.

English comes first. Language, script, pronunciation and model selection remain explicit so multilingual packs can be added later. A generic LLM is not required for narration, playback, source tracking, recording or transcription.

The implemented policy and a future service policy are distinct:

- **Local only:** document processing, narration and note transcription stay on the device. Unavailable capability produces a fallback, pending job or useful explanation; it never triggers an upload.
- **Future local first with optional remote services:** the user would separately choose whether document text may be sent for narration/document processing and whether microphone recordings may be sent for transcription. A network failure would return to a permitted local path or leave work pending. These services and permissions are not implemented in the current app.

Downloaded cloud-generated audio can provide offline playback, but it is not on-device synthesis. Downloading a model is also different from having a chapter's audio prepared. The interface must accurately describe each state.

## Curated downloadable packs

Offer a gallery of **qualified task and voice packs**, not an arbitrary model-file importer. Every listed voice must identify its narrator, language, synthesis engine, download requirement, offline status and supported features. A voice pack may share an engine with other voices; avoid downloading that engine repeatedly.

Catalog metadata should include:

| Area | Required information |
| --- | --- |
| Identity | Pack ID/version, upstream model revision, conversion revision, voice ID, task, languages, tokenizer/normalizer version and pronunciation resources. |
| Integrity | Exact artifact sizes, hashes, signed manifest, provenance, download URLs, resumable download support and atomic activation after verification. |
| Compatibility | Minimum OS and runtime versions, model-specific requirements, qualified device/SoC classes, permitted CPU/GPU/accelerator paths and known exclusions. |
| Resources | Download size, installed/compiled size, **measured** peak resident memory, cold/warm startup, generation speed, battery and thermal behavior. Mark unavailable measurements as unknown. |
| Features | Sentence/word alignment capability, streaming versus whole-chunk generation, background behavior, pronunciation controls and rate support. |
| Licensing | Runtime, weights, voice, tokenizer and phonemizer licenses separately; attribution and redistribution requirements. |
| Release state | Research candidate, device-qualified preview or production-qualified; tested app/OS/model versions and results. |

Model files, compiled caches and audio caches have different storage accounting. Removal must be safe around active playback, recording and transcription jobs. Keep the original document and saved notes independent of model availability.

## Local narration candidates

| Candidate | Verified integration evidence | Resource/language evidence | Decision and qualification |
| --- | --- | --- | --- |
| **Native system speech** | Apple `AVSpeechSynthesizer`; Android `TextToSpeech`. | Installed voices and offline capability vary by platform, engine and voice. | Required dependable fallback. Check actual installed availability and test airplane-mode playback. Apple exposes speech-range callbacks; Android range callbacks require engine timing support. [Apple API](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer), [Android timing contract](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener), [Android voice properties](https://developer.android.com/reference/android/speech/tts/Voice). |
| **KittenTTS nano 0.8 / LiteRT** | Official Google Android sample with CPU/XNNPACK and sentence prefetch. | 15M parameters, approximately 32 MB FP16 synthesis graphs, eight English voices. These graph sizes exclude the rest of the app/resources. | First compact Android narration evaluation. Sample reports Pixel 8a CPU first audio around 370 ms and RTF around 0.22–0.29; these are sample observations, not app/device-fleet guarantees. Model Apache 2.0; sample uses permissive G2P resources. [Official sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/text_to_speech_streaming/README.md). |
| **Kokoro 82M / sherpa-onnx** | Native Android/iOS examples through ONNX Runtime; this is not LiteRT or CoreML. | Base weights are Apache 2.0; language/voice availability depends on the selected package. | Cross-platform quality comparator. Qualify sustained CPU behavior and alignment separately. Audit phonemizer/data licenses; a permissive model license does not cover every frontend. [Weights](https://huggingface.co/hexgrad/Kokoro-82M), [runtime](https://github.com/k2-fsa/sherpa-onnx), [model packages](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html). |
| **PocketTTS / FluidAudio CoreML** | Actual Swift/CoreML integration; the FluidAudio package targets iOS 17. | Current documentation lists an English pack around 549 MB mixed INT8 or 766 MB FP16 **on disk**, not peak RAM. | iOS candidate requiring model-specific iOS 17 and older-iPhone qualification. Package compatibility alone does not establish model support or real-time performance. Current performance observations are on M-series Macs; only the fused flow decoder reaches ANE in the documented route. No custom phoneme/IPA lexicon interface. Weights CC BY 4.0, SDK Apache 2.0; voices require their own review. [Detailed integration](https://raw.githubusercontent.com/FluidInference/FluidAudio/main/Documentation/TTS/PocketTTS.md), [package target](https://raw.githubusercontent.com/FluidInference/FluidAudio/main/Package.swift), [upstream](https://github.com/kyutai-labs/pocket-tts). |
| **Kokoro / FluidAudio CoreML** | Actual staged CoreML pipeline, with detailed normalized-text/phoneme/duration outputs. | Hardware placement and OS behavior are model-specific. | **Gated research candidate.** Current maintainer documentation reports iOS 26.6/27 long-session failures across tested routes. No blanket safe-iOS or older-iPhone claim is justified; require resolved failures and physical-device soak evidence before offering it as a normal listening pack. [Current limitations](https://github.com/FluidInference/FluidAudio/blob/main/Documentation/TTS/KokoroAne.md). |
| **Qwen3-TTS / Argmax TTSKit** | CoreML integration and incremental playback, but TTSKit requires **iOS 18+**. | 0.6B download around 1 GB; current 1.7B support is macOS-only. | Optional high-end research tier, excluded from the iOS 17 baseline. Package-wide lower deployment targets must not be mistaken for TTS model support. [SDK](https://github.com/argmaxinc/argmax-oss-swift), [releases](https://github.com/argmaxinc/argmax-oss-swift/releases). |

Do not force all speech through one inference runtime. LiteRT is the preferred Android integration where a qualified pack supports it; CoreML is preferred on iOS where the actual model is qualified. A bounded ONNX adapter can be worthwhile when it provides better reliability or listening quality.

Current Kokoro LiteRT conversion artifacts are preview evidence, not a ready default: their published Pixel 8a CPU end-to-end result is slower than real time, and accelerator coverage is incomplete. [Conversion card](https://huggingface.co/litert-community/Kokoro-82M).

Piper is not the default closed-app recommendation: the maintained engine is GPL 3.0 and voices have separate licenses; the archived MIT project is not the current license reference. [Maintained project](https://github.com/OHF-Voice/piper1-gpl).

## Local recorded-note ASR candidates

Use a small downloadable tier first. Larger models are optional upgrades, never prerequisites for recording and retaining a note. Benchmark file-based accuracy on the intended recordings rather than prioritizing live command endpoint latency.

| Candidate | Verified path | Evaluation decision |
| --- | --- | --- |
| **Native on-device recognition** | iOS speech requests can require on-device recognition when supported. Android has an on-device recognizer factory and runtime availability checks. | Useful capability-dependent fallback. Default recognizers may use remote services; private mode must explicitly require and verify the local path. Unsupported locale/service/device leaves the note pending rather than uploading it. [Apple request](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition), [Android API](https://developer.android.com/reference/android/speech/SpeechRecognizer). |
| **Moonshine Voice** | Native iOS/Android packages using ONNX `.ort` models. | Evaluate compact English packs first, including Tiny 34M and Small 123M; Medium 245M is an optional upgrade. Current streaming models are MIT. Runtime/package support still needs physical-device qualification; not all legacy language models share this license. Streaming is available but not required for the note workflow. [Models](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/), [integration](https://moonshine-voice.readthedocs.io/en/latest/quickstart/). |
| **WhisperKit** | Swift/CoreML integration applicable to the iOS 17 app target. | Evaluate small English-capable variants first. The compressed large-v3-turbo artifact around 626 MB belongs in an opt-in high-capability tier. Actual model deployment/performance must be tested, not inferred from the SDK target. [SDK](https://github.com/argmaxinc/argmax-oss-swift). |
| **whisper.cpp** | MIT native C/C++ with iOS/Android examples and several acceleration options. | Portable comparator or fallback where integration is worthwhile. Published memory estimates are not measured phone peak RAM; choose a small/quantized model through device evaluation. [Repository](https://github.com/ggml-org/whisper.cpp). |
| **Parakeet TDT 0.6B v3** | CoreML path through FluidAudio; official Android LiteRT sample. | Optional larger accuracy tier: 600M parameters, 25 European languages, CC BY 4.0 weights. LiteRT sample support includes CPU/GPU and specific accelerators, not every Android NPU. Its overlapping-window demo is not native model streaming; this is less problematic for stored notes but still requires robust file transcription. [Model](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [LiteRT sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/speech_recognition/README.md), [FluidAudio](https://github.com/FluidInference/FluidAudio). |

Preferred first comparisons: **Moonshine Small versus a smaller WhisperKit model on iOS**, and **Moonshine Small versus a small portable Whisper or qualified LiteRT model on Android**. Measure whether the larger Parakeet tier earns its additional download, memory and battery cost.

ASR word timestamps refer to the recorded note, not the location in the book. The book anchor comes from the narration player. Retain the original recording, permit transcript corrections and distinguish corrected text from machine output. A bounded long-note policy can segment transcription without discarding recording audio or source association.

## Optional remote services

These are quality/accuracy choices behind explicit policy, not mandatory dependencies. Rates below are published values captured on 4 October 2026; verify billing units, endpoint eligibility, account tier, region, taxes and any changed promotion before budgeting.

| Service | Role | Cost and integration caveat |
| --- | --- | --- |
| **Eleven v4 / v4 Turbo** | Premium narration audition. | v4 regular **$0.08/1k characters**; Turbo **$0.04/1k**. Promotion until **12 October 2026** lists **$0.022 / $0.011** respectively; use regular rates for the long-term plan. API has character-alignment paths; verify chosen model/endpoint behavior. [Pricing](https://elevenlabs.io/pricing/api), [alignment](https://elevenlabs.io/docs/api-reference/text-to-speech/stream-with-timestamps). |
| **Cartesia Sonic 3.6** | Premium narration audition with word timestamps and model snapshots. | Approximately one credit per character, subject to preprocessing. Posted monthly included packs: $5/100k, $49/1.25M, $299/8M credits. **Overage** rates: Pro $65/M, Startup $45/M, Scale $38/M credits. Included-plan averages are not marginal overage rates. [Current model](https://docs.cartesia.ai/build-with-cartesia/tts-models/latest), [word timestamps](https://docs.cartesia.ai/examples/tts-sse-with-timestamps), [metering](https://docs.cartesia.ai/pricing), [plans](https://www.cartesia.ai/pricing). |
| **Google current Gemini TTS / Chirp 3 HD** | Quality/cost narration comparator. | Current Cloud Gemini 3.8 Flash/Lite promotional table through 31 December lists $0.50/M input text tokens plus $9/$6 per M output audio tokens; January rates double. Chirp 3 HD is $30/M characters before applicable allowance. Do not convert character and audio-token billing into equal per-minute claims; support differs by model/API. [Pricing](https://cloud.google.com/text-to-speech/pricing), [Chirp API](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd). |
| **Eleven Scribe v2** | Asynchronous recorded-note transcription. | $0.22/audio hour; keyterm prompting adds $0.05/hour. This is batch pricing, not the Realtime rate. Evaluate accented English and document vocabulary. [Pricing](https://elevenlabs.io/pricing/api). |
| **Deepgram Nova 3** | Prerecorded-note ASR alternative. | Posted PAYG prerecorded rates are **$0.0043/audio minute monolingual** and **$0.0052/min multilingual**. These are not streaming promotional rates; assess the appropriate language model on the same note corpus. [Pricing](https://deepgram.com/pricing). |
| **OpenAI transcription** | Optional recorded-note transcription comparator. | Pin an explicitly selected transcription model and verify its current official endpoint/pricing before budgeting. Keep transcription separate from narration or speech-to-speech chat. [Audio guide](https://developers.openai.com/api/docs/guides/audio/), [pricing](https://developers.openai.com/api/docs/pricing/). |
| **Azure Speech** | Narration/pronunciation-control and transcription comparator. | Obtain a quote for the chosen region and exact Neural/HD voice or ASR SKU. The reviewed public pricing page renders region-dependent values as `$-`; no universal numeric rate is assumed here. Speech SDK word-boundary events support source tracking. [Pricing](https://azure.microsoft.com/en-us/pricing/details/speech/), [TTS](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/text-to-speech), [word boundaries](https://learn.microsoft.com/en-us/javascript/api/microsoft-cognitiveservices-speech-sdk/speechsynthesiswordboundaryeventargs?view=azure-node-latest). |

Choose a premium voice through extended blind listening, not the provider's fastest first-audio figure. Verify that permitted download/cache behavior and voice terms suit offline chapter preparation.

Paid API access does not itself establish zero retention. Eleven and Cartesia describe enterprise/API eligibility and exclusions for their zero-retention arrangements. [Eleven policy](https://elevenlabs.io/docs/eleven-api/resources/zero-retention-mode), [Cartesia policy](https://docs.cartesia.ai/enterprise/zero-data-retention).

### Illustrative narration cost envelope

For planning only, assume **150 words/minute × 6 characters/word including spaces × 60 minutes = 54,000 characters per hour of newly generated narration**. This is an illustrative text-density assumption, not a measured book average or equal billing across providers. Using the regular rates above gives:

| Narration service | Derived charge for 54,000 characters |
| --- | --- |
| Eleven v4 | **$4.32** at $0.08/1k characters. |
| Eleven v4 Turbo | **$2.16** at $0.04/1k characters. |
| Cartesia Sonic, overage only | Approximately **$2.05–$3.51**, using $38–$65/M credits and the approximate one-credit/character mapping. **Subscription costs remain separate**; included credits affect when overages start. |

These figures are derived from the posted [Eleven](https://elevenlabs.io/pricing/api) and [Cartesia](https://www.cartesia.ai/pricing) rates, exclude promotions and are not per-hour playback fees. Replaying valid cached audio avoids new synthesis, while voice changes, text/normalizer revisions, regeneration and unused prefetch can add billable generation. Budget cache hit rate, subscription minimums, backend processing, storage, download/egress and optional OCR/ASR separately. At 2× playback the same generated chapter is consumed in half the time; generating continuously for a listening hour would approximately double this assumed text volume.

## Document and OCR model policy

Use embedded text where reliable and OCR where needed. The implementation uses **Apple Vision** and **bundled Tesseract English**. ML Kit was evaluated and removed because of documented diagnostic telemetry. OCR text alone does not establish table semantics or universally correct order; preserve geometry, provenance and original-page review. Additional scripts require separate artifact and device evaluation. [Apple Vision](https://developer.apple.com/documentation/vision/vnrecognizetextrequest), [Tesseract data](https://tesseract-ocr.github.io/tessdoc/Data-Files.html), [ML Kit disclosure](https://developers.google.com/ml-kit/android-data-disclosure)

Complex layouts may justify optional **Docling** processing in a controlled server environment or a separately consented **Mistral OCR** service. Neither is a mandatory phone download or an excuse to replace faithful source text with generated prose. Route by document evidence and processing policy; compare reading order, omitted/added content, geometry and tables, not only OCR character accuracy. [Docling](https://github.com/docling-project/docling), [Mistral document processing](https://docs.mistral.ai/capabilities/document_ai/).

Future local LLMs or vision-language models may help with user-requested explanations, difficult document recovery or pronunciation suggestions. Keep these separately labelled and optional. They must not silently paraphrase the author's narration or become a required multi-gigabyte install.

| Later intelligence candidate | Architecture boundary |
| --- | --- |
| [Gemma 4 E2B/E4B through LiteRT-LM](https://developers.google.com/edge/litert-lm/models/gemma-4) | Optional Android intelligence packs, qualified separately from speech. LiteRT-LM is a separate integration from the speech runtime; large downloads and working-memory budgets exclude it from the mandatory baseline. |
| [Apple on-device Foundation Models](https://developer.apple.com/documentation/technotes/tn3193-managing-the-on-device-foundation-model-s-context-window) | Optional newer-system capability on eligible devices. It is not an iOS 17 baseline dependency; check OS, device, locale and model availability before offering a feature. |

Remote general-purpose models can serve explicitly requested explanations or note organization later. They receive selected content only under the corresponding processing policy. Keep original notes and faithful narration independent of any generated interpretation.

## Proposed empirical release gates

These are proposed acceptance gates, **not achieved measurements or upstream benchmark claims**. Record results by device, OS, app build, exact artifacts, placement and audio settings. Use manual corpus checks, developer builds, physical-device profiling and sustained sessions; no committed unit-test source is part of this documentation request.

| Gate | Required evidence before a pack is promoted |
| --- | --- |
| **Fidelity and sustained quality** | Listen to representative ten-minute and hour-long passages. Inspect added/dropped/repeated words, sentence endings, dates, currencies, abbreviations, proper names, citations, footnotes, tables, equations and chunk transitions. Compare listening fatigue and voice consistency through blinded samples. |
| **Throughput at listening speed** | Measure end-to-end RTF = synthesis wall time / generated audio duration, including normalization, orchestration and waveform output. Continuous generation at 2× listening requires **RTF below 0.5 with measured headroom**, not merely below 1.0. Test cold/warm starts, seeks and competing document work. |
| **Preparation alternative** | A slower engine may prepare cached chapters before listening if the job fits actual memory/storage and stays within thermal/battery limits. Preparation does not make an engine suitable for instant seeks or unlimited concurrent work. |
| **Memory and storage** | Measure resident-memory peaks, model loading, compiled caches, first voice use and simultaneous rendering/ASR. Test lower-memory supported devices; avoid mandatory large packs and swap-based assumptions. |
| **Endurance and background behavior** | Complete at least two-hour listening soaks on every supported device/OS class, including screen locked, headset changes, calls, interruptions, network loss, thermal pressure and low battery. Record crashes, starvation, thermal throttling and battery consumption per hour. |
| **Alignment and resumption** | Check highlight drift, taps/seeks, altered playback speed and pause/resume using actual played audio. Resume after cancellation must ignore stale generated chunks. Test that a paused note anchors the last heard source word, or explicitly records sentence/paragraph precision when word timing is unavailable. |
| **Recording fidelity and survival** | Check clipping, quiet speech, headset microphones, route changes, interrupted recording, app/process interruption and storage failure. Preserve the original audible recording independently of transcription. Never make ASR success a prerequisite for saving or replaying a note. |
| **ASR usefulness** | Evaluate short recorded notes across English accents, names and document vocabulary, with background noise and natural hesitations. Measure transcript usefulness, omissions and correction effort; record processing latency, RAM and battery. Do not compare unrelated vendor WER benchmarks as if they shared a dataset. |
| **Private/offline behavior** | Test airplane mode, missing/corrupt packs, interrupted downloads, insufficient storage and revoked microphone permission. Confirm no silent network fallback, and that recordings stay attached while transcription is pending. |
| **Integrity and upgrade recovery** | Verify interrupted-download recovery, signature/hash failure handling, version pinning, rollback, safe model removal and reactivation. A catalog alias change must not silently break cached audio/alignment provenance. |

Promote only packs that pass their advertised capability. A voice can be qualified for prepared chapter playback but excluded from live synthesis, or qualified for a newer device tier while remaining unavailable on older phones. Publish that distinction in the catalog.

## Evaluation sequence

1. Assemble an English listening corpus covering ordinary books/articles, scanned pages, multicolumn PDFs, difficult pronunciation and representative recorded notes.
2. Establish native speech/OCR baselines and small local ASR packs. Qualify source anchors, recording survival and playback separately from model quality.
3. Compare Kitten and Kokoro on Android; Pocket and Kokoro ONNX on iOS. Measure real devices, including the oldest supported iOS 17 class, before making compatibility promises.
4. Audition premium cloud voices under the optional policy to establish a quality reference and cost envelope. Keep long-term budgets separate from promotions.
5. Add larger ASR, advanced voices and complex-document escalation only when measured improvements justify resources and integration risk. Expand languages through the same pack qualification process.
