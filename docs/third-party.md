# Third-party software and speech assets

Rhapsodos uses native operating-system frameworks and the pinned dependencies in the Gradle catalog, Xcode project and Swift package lockfile. Font sources and redistribution licenses are bundled with both apps and recorded in [font-manifest.json](font-manifest.json). Speech files are downloaded explicitly; exact URLs, versions, sizes and hashes are in [model-catalog.json](model-catalog.json).

| Component | Use | License and source |
| --- | --- | --- |
| Google LiteRT and official samples | Android CPU model API and reference tensor contracts | Apache-2.0; [pinned license](https://raw.githubusercontent.com/google-ai-edge/LiteRT/f9c6371694c020b2fff1c7597dabcaf90674c434/LICENSE) |
| KittenTTS | Compact English narration and public synthetic sanity audio | Apache-2.0; [pinned license](https://raw.githubusercontent.com/KittenML/KittenTTS/ab5592c28b6f376ea4c3963e84ce6d6688241eca/LICENSE) |
| Phonon 31M English | Optional local narration on Android and iOS | Weights, tokenizer and included voices Apache-2.0; runtime MIT/Apache-2.0. [Pinned model](https://huggingface.co/gradium/phonon-31m-en/tree/52433c46975bfa13144c56204c9f4ee1c5b148aa), [native runtime provenance and dependency notices](../native/phonon/README.md). The 40M noncommercial model is excluded. |
| Moonshine English | Saved-note transcription and tokenizer | MIT for the English models; [upstream licensing statement](https://raw.githubusercontent.com/moonshine-ai/moonshine/234f60faa0eb388b01cdf7e60aca232af37aefda/LICENSE) |
| OpenPhonemizer | Pronunciation dictionary/checkpoint used by the compact voice frontend | BSD-3-Clause-Clear; [pinned license](https://raw.githubusercontent.com/NeuralVox/OpenPhonemizer/15ffa723139a5e9a34b069e34c17fdac3095dba3/LICENSE) |
| DeepPhonemizer | Grapheme-to-phoneme reference | MIT; [pinned license](https://raw.githubusercontent.com/axelspringer/DeepPhonemizer/5dce7e27556aef4426f5623baf6351d266a30a73/LICENSE) |
| OpenAI Whisper / WhisperKit | Offline CoreML English transcription | MIT; [pinned Whisper license](https://raw.githubusercontent.com/openai/whisper/86098128c0b4f24f0e2aa2994de830614b474227/LICENSE), [Argmax source](https://github.com/argmaxinc/argmax-oss-swift) |
| Kokoro CoreML export | Optional enhanced English narration on eligible iOS 18+ devices | Apache-2.0; [pinned model conversion, weights and voices](https://huggingface.co/aufklarer/Kokoro-82M-CoreML/tree/f8ff771e4cab0bb3368e8af3a090a7e847485401) |
| Misaki English frontend / BART G2P | Bundled local pronunciation for iOS narration | Apache-2.0; [pinned adapted source/license](https://github.com/Jud/kokoro-coreml/tree/6bfc02afb237a311caee927482e58c028edb6acb), [BART package](https://github.com/Jud/swift-bart-g2p/tree/0.4.0) |
| PDFBox Android | Embedded-text PDF extraction and outline interpretation on Android | Apache-2.0; [source](https://github.com/TomRoush/PdfBox-Android) |
| Tesseract4Android, Tesseract and official tessdata_fast English | Bundled local scanned-page OCR on Android | Apache-2.0, with original Leptonica/libjpeg/libpng notices; [wrapper](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0), [pinned model](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd), [artifact manifest](ocr-manifest.json) |

Fetched Android speech license texts are in [licenses/](licenses/), with source revisions in [license-sources.json](license-sources.json). iOS dependency texts and speech notices ship in [iosApp/Resources/Licenses](../iosApp/Resources/Licenses/); exact package revisions are in [Package.resolved](../iosApp/EReader.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved). The retained English frontend records its pinned attribution and host modifications in [NOTICE](../iosApp/Vendor/Kokoro/NOTICE). An asset's runtime compatibility, license and actual device qualification are separate questions.

Do not broaden the Moonshine entry to a legacy non-English model without reviewing its specific terms. Do not substitute a public webfont URL for redistribution permission. Do not replace pinned model URLs with mutable branch downloads or enable a library's automatic fallback download.

ML Kit was evaluated and removed: its documented Android diagnostic collection includes device/app identifiers and usage metadata. The chosen bundled OCR path requires no telemetry or hidden model transfer. [Official disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
