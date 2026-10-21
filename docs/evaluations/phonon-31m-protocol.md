# Phonon 31M comparison protocol

Date: 21 October 2026. Compare Phonon 31M with the actual Kitten LiteRT and Kokoro CoreML implementations before deciding whether to add a downloadable voice. Python runs use the conda `dl` environment; experimental harnesses and model files stay outside the repository.

## Inputs and measurements

Use the same [ten public, authored passages](phonon-31m-corpus.json), with no user documents or recordings. Cover narration, dialogue, numbers, abbreviations, names, literal punctuation, technical prose and a long passage. Preserve all input text and final words. Use each engine's normal English voice at its default synthesis rate, recording exact voice/model/runtime revisions. Matching voice identities across unrelated models is impossible and must not be implied.

Measure cold loading, warm whole-passage preparation, first audio where exposed, generated duration, real-time factor and process memory. Record device, OS, runtime, compute placement and chunking. Compare performance only within the same environment; Mac CPU, Mac CoreML and Android-emulator results do not establish relative physical-phone performance. Separate compilation and competing inference from timed runs.

Transcribe generated audio locally with one pinned independent ASR model. Report normalized word edits, actual transcripts, known final-word retention, silence and clipping. ASR disagreement is a fidelity screening signal, not ground-truth pronunciation error or a naturalness score. Inspect difficult cases separately. Preserve playable samples for blinded human comparison; do not claim subjective listening preference without a listener.

Probe cancellation and reuse, long/unpunctuated text, repeated generation and memory growth. Never accept silent truncation as a successful result. Generated-audio duration does not constitute equivalent elapsed playback, battery or background endurance testing.

## Integration decision

Add Phonon only when measured results establish a useful improvement without material fidelity or lifecycle regressions. Download size alone, upstream benchmarks and first-audio latency alone are insufficient. A useful result can be limited to a clearly stated optional device tier; it must not silently replace the listener's current selection.

Native integration must preserve the iOS 17 baseline and existing Android ABIs, enforce supported runtime eligibility, accept only verified local model assets, serialize synthesis/cancellation with note capture, and preserve passage/source anchors. Reuse the canonical pack inventory and atomic download activation. Model weights are downloads; executable runtime code ships with the app. Native end-to-end checks must cover download, verification, explicit selection, narration, interruption, removal and recovery before the new card is advertised as runnable.

If the comparison does not establish superiority, document that result and retain the current voices. Physical-device performance, long listening comfort and accessibility remain separate qualification gates and must be stated honestly.
