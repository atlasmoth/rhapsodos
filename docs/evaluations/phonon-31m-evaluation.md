# Phonon 31M evaluation — 21 October 2026

**Evaluation conclusion: the measured memory, preparation-speed and text-fidelity improvements justified integrating Phonon.** The subsequent 21 October product decision makes Phonon the sole downloadable narration family and removes system speech, Kitten and Kokoro. This is a curation decision; the comparison does not establish universal perceptual dominance, a preferred voice personality or physical-phone release readiness. The active policy is in [model-manager.md](../model-manager.md).

The [protocol](phonon-31m-protocol.md), [authored corpus](phonon-31m-corpus.json) and [structured results with transcripts](phonon-31m-results.json) record the evidence. All Python ran in conda `dl`; no user content was processed. Experimental code, model files and WAVs are outside the repository at `/private/tmp/rhapsodos-phonon-evaluation-m926hyez`.

## Measured performance

Lower real-time factor (RTF) means less generation time per second of audio. Android results use the same API 37 ARM64 emulator and two CPU threads. Apple results use the same M4 Pro Mac, macOS 27.0.1 and 48 GB RAM, with isolated sequential processes. Cross-platform measurements must not be combined into a phone ranking.

| Environment and voice | Generation RTF | Peak process memory | Coverage |
| --- | ---: | ---: | --- |
| Android Kitten LiteRT, Bella | 0.1279 | 858.5 MiB PSS | 10/10 passages |
| Android Phonon Q8, default voice | **0.0703** | **337.1 MiB PSS** | 10/10 passages |
| Mac Kokoro CoreML CPU, Heart | 0.0825 | 746.3 MiB RSS | 9/10 passages; names rejected |
| Mac Phonon CoreML CPU, default voice | **0.0315** | **277.0 MiB RSS** | 10/10 passages |
| Mac Phonon CoreML ANE, default voice | **0.0279** | **143.1 MiB RSS** | 10/10 passages |

Apple RTF compares the same nine successful texts in the second round; each engine attempted twenty generations across two rounds. Android Phonon prepared the complete corpus in 11.30 seconds versus Kitten's 30.61 seconds. Their natural speaking rates differ: Phonon produced 160.8 seconds of speech and Kitten 239.3 seconds. Faster speaking alone is not a quality improvement. Android load times were 0.211 seconds and 1.016 seconds respectively. Phonon's warm first PCM arrived in 20–29 ms, but the current app transport prepares a complete passage before playback; callback latency must not be advertised as app startup latency.

The separate Mac Rust/Python CPU route reached 0.0286 warm RTF with one thread. Its process RSS rose to about 835 MiB during 100 normal utterances, then plateaued; unloading retained much of the allocator's memory. This differs materially from the CoreML and Android results and demonstrates why download size is not a RAM estimate. The run generated 26.76 minutes of audio, not 26.76 minutes of actual playback.

## Fidelity screening

All generated audio was transcribed locally with pinned Whisper small.en. Reference normalization preserves the words inside parentheses. The counts below are ASR-based word edits, not independently established TTS pronunciation errors; time formatting and proper-name recognition contribute to them.

| Voice | Full available corpus | Matched nine passages |
| --- | ---: | ---: |
| Android Kitten | 18 / 426 words | 14 / 390 |
| Android Phonon | **3 / 426** | **2 / 390** |
| Kokoro CoreML | 4 / 390; names unavailable | 4 / 390 |
| Phonon CoreML CPU | 4 / 426 | **3 / 390** |
| Phonon CoreML ANE | 5 / 426 | **3 / 390** |

Kitten's screening disagreements concentrated in abbreviations, currency, dates and names. Phonon handled the names passage that the current Kokoro frontend rejected. All engines that produced the explicit fidelity passage retained its final word, “lighthouse.” Additional Phonon unpunctuated inputs retained their ending sentinels: CPU 243 words/66.8 seconds, CoreML 130 words/39.68 seconds. These cases do not prove universal absence of omissions. Naturalness, fatigue and accent preference still need blinded listeners and extended listening.

## Native requirements before integration

The runtime is [Phonon v0.4.0](https://github.com/gradium-ai/xn-ptts/releases/tag/v0.4.0), commit `f07d7952db0c6127d5e4bc3f41c82b3304b6b5aa`. Android Q8 assets are pinned to [model revision 52433c46975b](https://huggingface.co/gradium/phonon-31m-en/tree/52433c46975bfa13144c56204c9f4ee1c5b148aa), totaling 65,049,306 bytes for four required files. The CoreML release archive is 137,096,081 bytes, SHA-256 `10f3a99fd39256db44f826267ef21d78cca959b16c0554926344c09fde27b436`, with approximately 170.3 MB of manifest assets before compilation. Its recorded export revisions differ from the later model-card revision; required source model blobs were checked for equality. The 31M model is Apache 2.0. A later named-voice audit found no separate license file or metadata in the pinned voices repository; [third-party.md](../third-party.md) records the remaining attribution check. The 40M noncommercial models are outside this change.

Stock CPU and CoreML generation loops can exhaust their frame budgets without reporting missing end-of-speech. Rebuild the runtime with an explicit failure for this case; never cache truncated audio as success. Cancellation also returns partial audio successfully upstream. The app must join generation and reject cancelled output before publishing a cache entry. Cancellation/reuse probes succeeded, and the native iOS 27 simulator generated all ten passages, but those probes do not replace the complete integrated listening/note journey.

Android must retain existing 32-bit support while restricting this pack to supported 64-bit processes. The evaluated generic ARM64 build uses API 28 and 16 KiB alignment, without mandatory dot-product/FP16 CPU instructions. The user subsequently approved raising the app minimum to iOS 18; native objects and app deployment settings now share that floor. The evaluation did not qualify physical iPhones. Verified bounded archive preparation, installed-pack preservation and explicit voice selection are mandatory.
