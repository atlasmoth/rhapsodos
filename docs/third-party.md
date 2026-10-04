# Third-party software and speech assets

Rhapsodos uses native operating-system frameworks and the pinned dependencies in the Gradle catalog, Xcode project and Swift package lockfile. Font sources and redistribution licenses are bundled with both apps and recorded in [font-manifest.json](font-manifest.json). Speech files are downloaded explicitly; exact URLs, versions, sizes and hashes are in [model-catalog.json](model-catalog.json).

| Component | Use | License and source |
| --- | --- | --- |
| Google LiteRT and official samples | Android CPU model API and reference tensor contracts | Apache-2.0; [pinned license](https://raw.githubusercontent.com/google-ai-edge/LiteRT/f9c6371694c020b2fff1c7597dabcaf90674c434/LICENSE) |
| KittenTTS | Compact English narration and public synthetic sanity audio | Apache-2.0; [pinned license](https://raw.githubusercontent.com/KittenML/KittenTTS/ab5592c28b6f376ea4c3963e84ce6d6688241eca/LICENSE) |
| Moonshine English | Saved-note transcription and tokenizer | MIT for the English models; [upstream licensing statement](https://raw.githubusercontent.com/moonshine-ai/moonshine/234f60faa0eb388b01cdf7e60aca232af37aefda/LICENSE) |
| OpenPhonemizer | Pronunciation dictionary/checkpoint used by the compact voice frontend | BSD-3-Clause-Clear; [pinned license](https://raw.githubusercontent.com/NeuralVox/OpenPhonemizer/15ffa723139a5e9a34b069e34c17fdac3095dba3/LICENSE) |
| DeepPhonemizer | Grapheme-to-phoneme reference | MIT; [pinned license](https://raw.githubusercontent.com/axelspringer/DeepPhonemizer/5dce7e27556aef4426f5623baf6351d266a30a73/LICENSE) |
| OpenAI Whisper / WhisperKit | Offline CoreML English transcription | MIT; [Whisper license](https://github.com/openai/whisper/blob/main/LICENSE), [Argmax source](https://github.com/argmaxinc/argmax-oss-swift) |
| Kokoro CoreML | Optional enhanced English narration on eligible iOS 18+ devices | Apache-2.0; [pinned host source/license](https://github.com/Jud/kokoro-coreml/tree/6bfc02afb237a311caee927482e58c028edb6acb) |
| PDFBox Android | Embedded-text PDF extraction and outline interpretation on Android | Apache-2.0; [source](https://github.com/TomRoush/PdfBox-Android) |
| ML Kit bundled Latin recognizer | Local scanned-page OCR on Android | [Google ML Kit terms](https://developers.google.com/ml-kit/terms) |

Full fetched speech license texts are in [licenses/](licenses/), with exact source revisions in [license-sources.json](license-sources.json). Native code ships applicable notices with its resources. Vendored iOS host modifications and their pinned source attribution are recorded beside that source. An asset's runtime compatibility, license and actual device qualification are separate questions.

Do not broaden the Moonshine entry to a legacy non-English model without reviewing its specific terms. Do not substitute a public webfont URL for redistribution permission. Do not replace pinned model URLs with mutable branch downloads or enable a library's automatic fallback download.
