# Third-party software and speech assets

Rhapsodos uses native operating-system frameworks and the pinned dependencies in the Gradle catalog, Xcode project and Swift package lockfile. Font sources and redistribution licenses are bundled with both apps and recorded in [font-manifest.json](font-manifest.json). Speech files are downloaded explicitly; exact URLs, versions, sizes and hashes are in [model-catalog.json](model-catalog.json).

| Component | Use | License and source |
| --- | --- | --- |
| Phonon 31M English | Downloaded narration on Android and iOS | Model Apache-2.0; runtime MIT/Apache-2.0. [Pinned model](https://huggingface.co/gradium/phonon-31m-en/tree/52433c46975bfa13144c56204c9f4ee1c5b148aa), [37 named English speaker profiles](https://huggingface.co/gradium/phonon-voices/tree/da6c9ec0d47be8df5c87c730a3aa97187f3229c3), [native runtime provenance and dependency notices](../native/phonon/README.md). The official 31M release includes the English profiles, but their separate repository has no license file or license metadata at this revision; confirm voice-asset redistribution terms before distribution. The 40M noncommercial model is excluded. |
| OpenAI Whisper / WhisperKit / whisper.cpp | Downloaded English note transcription through native CoreML on iOS and Q8 CPU inference on Android | MIT; [pinned Whisper license](https://raw.githubusercontent.com/openai/whisper/86098128c0b4f24f0e2aa2994de830614b474227/LICENSE), [Argmax source](https://github.com/argmaxinc/argmax-oss-swift), [pinned Android runtime and dependency notices](../native/whisper/README.md) |
| PDFBox Android | Embedded-text PDF extraction and outline interpretation on Android | Apache-2.0; [source](https://github.com/TomRoush/PdfBox-Android) |
| Tesseract4Android, Tesseract and official tessdata_fast English | Bundled local scanned-page OCR on Android | Apache-2.0, with original Leptonica/libjpeg/libpng notices; [wrapper](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0), [pinned model](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd), [artifact manifest](ocr-manifest.json) |

Font redistribution texts are in [licenses/](licenses/). The active native speech runtimes retain their own pinned sources and notices under [native/](../native/). iOS dependency texts and speech notices ship in [iosApp/Resources/Licenses](../iosApp/Resources/Licenses/); exact package revisions are in [Package.resolved](../iosApp/EReader.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved). An asset's runtime compatibility, license and actual device qualification are separate questions.

Do not substitute a public webfont URL for redistribution permission. Do not replace pinned model URLs with mutable branch downloads or enable a library's automatic fallback download.

ML Kit was evaluated and removed: its documented Android diagnostic collection includes device/app identifiers and usage metadata. The chosen bundled OCR path requires no telemetry or hidden model transfer. [Official disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
