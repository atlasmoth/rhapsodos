# Product plan

Status: agreed product direction and first-release scope. Research baseline: 4 October 2026. Native listening, notes, source preparation and speech downloads are implemented; [development.md](development.md) records executed evidence and remaining release qualification. The scope below defines the product bar rather than certifying every device or document.

## Product promise

Make an ordinary PDF something people actively want to listen to. Narration should feel comfortable over a long session, navigation should follow how people listen, and recording a thought should take a few deliberate actions without losing the passage.

Reading serves listening: the app shows the active passage, lets a person check the original page and helps them revisit a note's context. A useful source view remains available, but the home and player should communicate a listening product rather than a document editor with a play button.

The first audience is everyday readers and people using listening for accessibility. English gets launch-level speech and extraction quality. The architecture carries language tags, locale-aware segmentation and transcript metadata for later multilingual expansion.

## Primary journeys

| Journey | Intended experience |
| --- | --- |
| First listen | Import a PDF, see its first useful content, choose an available voice and start without waiting for full-book processing |
| Continue | Open the app or use system media controls and resume at the last source position with the selected voice |
| Follow | Track the highlighted spoken passage on the original PDF; inspect nearby content and return to the current passage |
| Remember | Tap the active PDF highlight to pause and record at the confirmed passage, then save and resume independently of transcription |
| Revisit | Open a note, hear its original recording, edit its transcript and jump back to its source context |
| Prepare for offline | Download a compatible voice if desired and prepare selected chapters; see exactly which content is ready |
| Use a wide window | Keep outline, source and anchored notes in useful panes without restarting audio or losing a draft |

The library has no separate continue-listening hero. Each file shows its own source-based listening progress. Opening it restores its saved passage and position without resetting an already active session. Progress remains unknown while document preparation is incomplete; it does not imply a known full-book audio duration.

The player uses that same whole-file progress. Scrubbing commits a single source seek across the PDF; it does not reset at each passage. Scrubbing preserves whether narration was playing or paused, including after natural completion. Voice previews and replayed notes retain the saved book progress, and seeking to the end leaves playback paused.

### First listen

Use the system file picker. Prefer a dependable local copy for the initial implementation, with clear storage management. Show the document title, preparation state, available voice and a prominent listen action. Start with embedded-text PDFs and scanned PDFs that the native OCR pipeline can handle reliably.

Preparation is progressive. Prioritize the first/current passage and nearby content. A full document scan must not block the first useful listen when the start is ready. Show uncertainty or preparation failure in context, with access to the original page.

The first-run model choice should be simple: recommend one compatible voice, provide a short audible sample and explain size and offline use. System speech can support immediate use where available. A gallery of technical model names is not the first screen.

### Listening

The player emphasizes the current passage and clear controls: play/pause, skip or seek, speed, chapter/passage navigation, voice, output volume and record thought. Common actions remain reachable on phones; wide layouts keep a persistent playback dock.

The dock provides output-volume controls through the native media-volume APIs. They follow the active output route and hardware volume buttons, without restarting narration or changing microphone gain. Opening the control never changes volume. Routes with fixed output volume explain where the listener can adjust it.

Resume, scrubbing and chapter selection refer to the source, even if generated audio changes. If a requested section is not prepared, show that state and prepare it; do not pretend every PDF is instantly an offline audiobook.

The original PDF is the main reader surface, with no separate "Listen and follow" or reflow pane. Highlight only at the precision the speech path can support. Tapping the visible active highlight pauses narration and opens an audio note at the frozen source position. Blank-page taps, scrolling and pinch gestures do not trigger capture. Accessible source text and an equivalent record action accompany the PDF without adding another visible reading pane. Manual page browsing, pan and zoom pause narration and automatic following immediately. Play resumes the saved listening position and returns the PDF to narration. There is no separate follow-narration control. Explicit chapter, note and search destinations can move the source position while leaving playback paused.

Page browsing and zoom use gestures and accessible actions; do not add a visible PDF control bar. Keep passage counts and routine voice-status labels out of the playback dock. Show actual preparation and failure states when they require attention. Import prompts use a short, single-sentence subtitle.

The PDF display follows the app's light/dark background and contrasting ink for neutral and muted text. Recolour low-chroma pixels, preserve strongly coloured artwork, and offer **Original colours** in Appearance settings. This is a display transformation: it never rewrites the PDF, extraction input, source ranges or note anchors. Grayscale and muted-colour photographs and scanned illustrations also change because pixels cannot distinguish them from text; the original-colour option restores their appearance. Some coloured text can retain insufficient contrast in dark mode. Further automatic handling needs qualified text-aware rendering rather than treating artwork pixels as text.

Voice changes are explicit. Keep the current listening place, explain any required preparation and retain the chosen voice rather than silently switching after a generation failure.

### Voice note

1. The listener taps the active highlight in the PDF or invokes its accessible **Pause and record thought** action.
2. Playback stops at a confirmed position. The app freezes and saves the attachment before opening the microphone.
3. The recorder shows the attached passage, recording status and duration. The listener can stop, cancel or save.
4. Save finalizes durable audio. **Save and resume** returns to listening without waiting for ASR.
5. The note shows a truthful state such as saved/transcribing, waiting for resources or transcription failed. Its recording and attachment remain usable.
6. The listener can edit the transcript or retry ASR. Machine retries cannot overwrite their edits.

Here, converting a voice note to text means **transcription**. Language translation is optional later and preserves the original. Capture does not require network or ASR availability. Typed notes are a useful accessible alternative, including after microphone permission is denied.

An interrupted recording must have a recoverable draft or an honest failure state; never display “saved” before durable commit. Unexpected interruptions should not automatically restart narration. Reattaching a note to another passage is a deliberate user operation.

## First-release scope

| Include in the first usable release | Defer until the core is proven |
| --- | --- |
| PDF import, incremental text/OCR and dependable reading order for the supported corpus | Broad document editing, arbitrary office formats and advanced PDF authoring |
| Pleasant qualified voice, background audio, media controls, speed and resume | Voice cloning, generative dramatization and conversational playback |
| Source-following original PDF, accessible source context and honest alignment precision | Mandatory word-level timing for providers that cannot supply it |
| Durable anchored voice notes, local ASR or explicit deferred transcription, transcript editing | Voice-command assistant, chat and automatic note rewriting |
| Curated compatible speech downloads, offline preparation and storage controls | Unrestricted custom-model importing and a large model marketplace |
| English quality, language-aware data contracts | Launching many languages with unmeasured quality |
| Native phone and wide-window layouts, accessibility and recovery | Desktop/web apps or feature parity across every new platform API |
| Enforceable local-only mode | Mandatory accounts, mandatory cloud processing or a general LLM dependency |

Optional cloud narration may be valuable if it provides a material quality gain; it is a product choice after the local bakeoff. Cloud note ASR and cloud OCR are separate opt-ins. Sync and account features should not delay a dependable local app.

EPUB is a plausible later input because the shared document graph is format-independent. Do not expand file support before qualifying the PDF listening loop.

## Document fidelity

Do not silently summarize, paraphrase or omit difficult passages in ordinary narration. Separate source text from spoken normalization and expose policies for running headers, footnotes, captions and tables. Complex layouts require confidence signals and a repair/source path.

Headings and paragraph boundaries shape navigation and prosody. Page numbers are useful context, but listening should not pause mechanically at page breaks or pronounce every header. Correct pronunciation of names and abbreviations matters more than an impressive model size.

Measure dropped/repeated text and incorrect reading order separately from voice naturalness. Beautiful audio generated from the wrong passage is a product failure.

## Local-first and honest readiness

Offer three understandable processing choices:

- **Local:** use installed/system capabilities for content processing.
- **Local-only:** enforce no content egress, including sync, diagnostics attachments and automatic fallbacks.
- **Optional cloud capability:** an explicitly selected remote voice, transcription or document-preparation service with a clear data/cost explanation.

Downloading public voice assets can be a separate network permission in local-only mode. Describe cloud sync independently from cloud inference. Do not use a vague privacy badge as a substitute for actual routing policy.

Show model download, installation and content preparation separately. A downloaded voice does not prove a whole book can be narrated offline immediately. An offline preparation view should answer which chapters are ready, how much space they use and whether note transcription can run locally.

On a smaller device, favor the compact voice, bounded caches and delayed ASR over heavy parallel jobs. For a slow compatible voice, offer advance generation. If a model does not fit memory, do not recommend it merely because preparation can run overnight.

## Accessibility and comfort

Rich haptics, polished native motion and tasteful GPU effects should make listening controls enjoyable and immediate. These are required product qualities with resource-aware and accessible fallbacks; they must preserve legibility and reliable narration/capture. See [ui-quality.md](ui-quality.md) for the measurable standard.

Accessible listening is a core quality bar. Support screen readers, large text, high contrast, reduced motion, keyboard/pointer operation and non-color recording cues. The original PDF may need an accessible semantic companion; merely wrapping a canvas in native UI is insufficient.

Do not move screen-reader focus with every highlight or speak every partial transcript over narration. Let people operate controls predictably with assistive speech enabled. Provide explicit settings for narration speed and source presentation.

Use real accessibility participants during validation. Comfortable listening, simple recovery and useful source access matter more than decorative waveforms or an elaborate onboarding sequence.

## Success and acceptance

The first release succeeds when a person can import a representative PDF, start listening, lock the phone, follow the source when needed, record a thought, resume immediately and retrieve the correctly attached note after restarting offline.

Track useful operational signals without collecting document content: start/preparation latency, audio underruns, extraction failures, capture commit/recovery outcomes, ASR latency, offline readiness and model installation failures. Product research can assess sustained listening comfort and whether people prefer this experience over reading-only tools. Numerical release targets and device methodology are in [roadmap.md](roadmap.md).

This is still a pre-release build. Remove obsolete features and internal compatibility layers freely as the product becomes clearer; retain user data integrity and the agreed platform floor. Follow [AGENTS.md](../AGENTS.md) for atomic commits and verification policy.
