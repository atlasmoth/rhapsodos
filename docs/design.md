# Design direction and references

Status: selected references and tentative experience design. Research baseline: 4 October 2026. References inform original work; they are not our designs or evidence of accessible, production-tested behavior.

## Five references

| Reference | What to study | How to apply it here |
| --- | --- | --- |
| [VoxShelf — Odama](https://dribbble.com/shots/25220008-VoxShelf-Mobile-App-Audiobook) | Spoken passage, audio controls and a cohesive dark/plum listening identity | Put the passage and record-thought action at the center. Keep accent color restrained and verify contrast; do not let a decorative waveform dominate |
| [Abuk — tubik](https://tubikstudio.com/works/abuk) | A complete audiobook/ebook product case spanning mobile and tablet, reading, listening and settings | Study continuity between library, player, reader and notes, rather than borrowing a single isolated screen. Preserve useful native media behavior |
| [Ebook Reader — Dipa Inhouse](https://dribbble.com/shots/21035903-Ebook-Reader-Mobile-Design) | Comfortable reading typography, chapters and persistent playback controls | Combine a quiet PDF surface with accessible player controls and predictable chapter navigation |
| [E-Book Dashboard / GrabRead — MindInventory](https://dribbble.com/shots/20454786-E-Book-Dashboard) | Warm editorial wide-screen library, compact navigation and contextual columns | Use the wide-screen structure for library/current listening/notes/offline readiness rather than unrelated dashboard widgets |
| [File Reader + Chat Panel — Never Before Seen](https://dribbble.com/shots/26279355-File-Reader-Chat-Panel-Interface) | Clear document and supplementary-panel boundaries, independent pane controls | Adapt the supplementary panel to anchored notes and transcripts; keep independent scrolling and access to the current passage |

The Abuk case provides documented product breadth; not every linked image was available for direct pixel inspection during research. The other references are design presentations, not proof of performance, interaction completeness or accessibility. Original assets, layout decisions and testing are still required.

## Visual direction

The user requires world-class UI craft from the first implementation: rich intentional haptics, carefully chosen shaders and native motion, very fast interaction and high sustained performance. [UI quality](ui-quality.md) defines feedback semantics, Metal/AGSL ownership, adaptive degradation and measurable budgets.

Use a coherent, quiet visual system: warm neutral interface surfaces, comfortable interface typography, strong controls and one controlled accent for playback/current passage. Covers can contribute secondary color without compromising interface contrast. Offer system, light and dark app appearance. PDF page colours, typography, text and artwork retain their original appearance in every theme; app typography and palette do not restyle the source document.

Appearance changes update the visible reader, open sheets and system controls together, including when returning to System mode. Keep their existing presentation identity, scroll position, search text, note drafts and audio session; a theme change must not dismiss or rebuild the reading flow. iOS applies the preference to the reader's own window so presented content inherits it. Android derives system-bar appearance from the same resolved theme as Compose.

The main screen should communicate **listen, follow, remember**. A large cover or waveform can establish identity, but should not displace the passage and recording action. The source text is the useful visual accompaniment to audio.

The original PDF is the main reader and remains a faithful zoomable canvas. There is no separate "Listen and follow" or reflow pane. Use source geometry for a calm active highlight and note markers; never redraw the document into an invented layout and present it as the original. Double-tapping prepared source text pauses narration and opens audio-note capture attached to that passage. Single taps never record, and there is no write-note menu action. Keep blank space and browsing gestures free of recording actions.

Motion explains transitions and preparation. It must respect reduced-motion preferences and should not move content with every spoken word. Use a stable highlight rather than constant scrolling animation.

## Main surfaces

Book thumbnails show the original first PDF page, including in the playback dock. Native adapters render small covers asynchronously with bounded caches. Covers retain their original colours in both themes; unavailable or damaged files receive a neutral document placeholder. Cover generation never changes the source PDF or reader geometry.

Exactly three icon-only library actions sit immediately beneath the Rhapsodos logo and name in one horizontal row on both apps: **Search → Import → Settings**. Use rounded tonal buttons with generous touch targets and accessible names; do not add visible titles or a separate Voices button. Voice selection, downloads and transcription configuration are reached through Settings. The file list follows these actions directly, without an introductory slogan section. Compact readers return to the library for these actions without restarting narration; document-specific navigation, search and notes remain with the PDF.

| Action | Native icon meaning | Accessible label and destination |
| --- | --- | --- |
| Search | Magnifying glass | Search the library; dedicated whole-library search sheet |
| Import | Plus | Import PDF; system document picker |
| Settings | Horizontal adjustment sliders | Settings; voice selection, speech downloads, transcription and appearance |

The library ends with its files rather than a privacy/archive footer. Settings contains Reading voice, Transcribe voice notes, Playback speed, Appearance and Documents, with Export document archive above Restore a document archive in Documents. Export saves the selected PDF, saved recordings, transcripts and source-based listening position through the native file picker. It remains unavailable during preparation, capture or another document operation. Do not add a Privacy & about section or an About sheet. Download context remains with the speech gallery; model cards do not show outbound licence links. Retain licence metadata and bundled notices.

Place **Download voices and transcription** in its own full-width tonal card above the Reading voice card on both platforms. Match the neighboring cards’ heading typography, padding and rounded corners, with a trailing chevron and the entire card tappable. It opens **Voices and transcription**, a compact combined manager reusing the same cards and setup actions as the focused sheets.

Reading voice is one compact Settings entry showing the selected voice or a concise download state. Transcribe voice notes is a switch, off initially. Each entry leads to a focused sheet with the same title; enabling transcription offers its download if necessary. The sheet shows a concise name, language, size and one contextual primary action: Download & use for narration, Download & enable for transcription, or Enable for verified transcription. Verified narration uses named voice rows. Setup automatically downloads, checks and activates the requested choice. Never duplicate selection chips or a sole-voice picker across surfaces. Turning transcription off preserves recordings and downloads and cancels pending activation.

Show progress, Pause, Resume, Retry and short waiting/error text only in the relevant state. Selected/On must mean verified and usable. Model requirements, check history and removal live under an expandable Details control. Model checks are automatic; expose Retry after failure without a manual check button. Configured catalogue refresh is secondary; unconfigured hosting and routine privacy/qualification prose stay out of the consumer flow. A sheet dismissal does not cancel setup, and completion never starts paused narration. See [model-manager.md](model-manager.md) for persistence, selection and recovery rules.

English speaker choices share one Phonon download card. Below the ready pack, show full-width voice rows with a name, localized language/accent and trailing selected checkmark. Narrators appear first; Characters is collapsed initially. The focused Reading voice sheet shows this list immediately. The combined manager puts the same list behind an initially collapsed Choose a voice disclosure, keeping the transcription download visible. Settings shows the selected speaker's name. Selecting a row uses the guarded activation flow, preserves the source position and leaves playback paused. Do not create a model card or repeat the engine download for each speaker, and do not add sample playback.

The PDF header shows the document title followed by **Contents → Search → Notes** on both apps, using equivalent native icons and accessible names. Notes toggles the anchored-notes panel on wide readers or a sheet on narrower readers; opening or closing it does not pause or seek narration. Do not add a separate notes row below the header. Search opens its own native sheet on both apps; it is not a tab inside another tool sheet. In-file scope stays fixed to that document; only the library entry searches all files. Both sheets use the same query prompt, loading/error/retry behavior and page-labelled passage/note results. Keep the query header stable as loading, errors and results change; scroll the bounded content area. Selecting a result moves the source position while remaining paused.

The playback dock shares one arrangement on both platforms. Voice-note capture belongs to prepared PDF passages and their accessible actions; the dock has no microphone button. The first row contains the original cover and document title, then the current speed button, output-volume button and **Previous → Play/Pause → Next** controls. At compact widths, transport moves together into a centered second row; identity, speed and volume remain above it. The whole-file progress slider follows the controls, with a concise percentage immediately to its right. Unknown progress retains useful preparation feedback rather than inventing a percentage. Keep the speed action visible and operable without opening Settings, with its current value and a meaningful accessibility label. Keep the volume control native to the active output route. Error and preparation feedback remain visible; do not add routine voice-status or passage-count labels.

Native sheets follow one content system based on the Android arrangement: a leading title and trailing Done action, then stable controls and bounded scrolling content. Secondary header actions precede Done; durable save or destructive actions remain clearly labelled and follow the same order on both platforms. Align content insets, section gaps, interface typography, rounded tonal cards and preparation/error/retry feedback through common app tokens. Keep query controls and download/preparation progress stable while results and states change. SwiftUI and Compose retain their native sheet mechanics, keyboard behavior, accessibility and safe areas. Closing a sheet must not silently commit or discard a recording or transcript edit.

Touch feedback and decorative motion are enabled app behavior without app-level switches. OS Reduce Motion and system haptic settings, hardware capability, power and thermal constraints still govern native fallback behavior.

| Surface | Content and primary action |
| --- | --- |
| Library | Original covers, progress on each file, resume by opening a file, preparation/offline status and import action |
| PDF reader | Original page, active narration highlight, double-tap passage-to-record and persistent playback dock |
| Notes | Source-attached recordings, transcript state, edit/replay and jump-to-passage |
| Recorder | Frozen source excerpt, duration, explicit recording status, cancel and save/resume |
| Reading voice | Current voice, language, size and a single download/check/activation flow; details and removal are secondary |
| Transcribe voice notes | On/off, download and automatic verified activation; recording remains independent |
| Preparation/storage | Chapters ready offline, pending work and separate controls for caches, models and user data |

Avoid presenting the model browser as a technical research dashboard. Use capability names by default; model/runtime/license details and measured resource data belong in an expandable advanced view. Distinguish “downloaded”, “compatible”, “ready” and “prepared content”.

## Adaptive layouts

Adapt to available **window space**, font scale and posture, not a tablet label or portrait/landscape heuristic. Platform size classes are useful inputs, but content still needs to fit. The same session remains active as panes collapse or reappear.

| Window | Intended composition | Collapse behavior |
| --- | --- | --- |
| Compact | Focused destination and persistent player; source and notes available by navigation/sheet | Preserve source selection, scroll position and note draft |
| Medium | Main PDF plus one useful context pane | Switch outline/notes without making the source unusably narrow |
| Wide | Outline/library, PDF, anchored notes, persistent playback dock | Hide supplementary context first when text size or width demands it |
| Very wide | Adjustable bounded panes, optional richer source context | Bound text measure and avoid stretching controls or adding empty columns |

Wide listening shows the PDF in the center and can expose notes for the current document on the right. A library or outline belongs on the left. Do not force four simultaneous panes merely because a large display permits it. The library header provides a separate Hide library button. When hidden, the reader provides Show library and a narrow left-edge swipe reveals it. These controls do not change the three library actions or seek, pause, resume or reconstruct narration. They preserve the selected PDF, browsing position and notes state. Compact readers reveal the library without discarding the selected document.

The source and notes scroll independently. Manual source browsing pauses narration and auto-follow. Play resumes the frozen listening position and returns to its page. There is no follow toggle or separate return action. Seeking moves narration only through an explicit source destination or listening command. Opening a note should expose its source context without inadvertently starting playback.

### Android

Compose and Material3 Expressive define controls, type, shapes and motion. Material3 Adaptive handles available-window/posture decisions. Navigation3 is the navigation owner; use its adaptive list-detail/supporting-pane scenes rather than creating multiple independent back stacks for the same selection. A navigation suite can adapt primary navigation between compact and wider arrangements.

Map outline/source and source/notes relationships deliberately to scenes. Preserve selection and saved state when panes change. Test fold hinges, keyboard/pointer input, font scaling and resizable windows. Check current stable versus experimental APIs before adopting the full expressive surface. [Android list-detail guidance](https://developer.android.com/develop/adaptive-apps/guides/list-detail), [Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)

### iOS

iPhones use a focused `NavigationStack` in portrait and landscape. Show library and the left-edge gesture open a full-width library overlay while the PDF remains mounted; Return to document closes it. First launch shows branding and library actions without an extra large Library title or disabled hide button. iPads use `NavigationSplitView` for library and source detail, including native collapse in narrow windows. Use the iOS 17 inspector for supplementary anchored notes: it can appear as a trailing panel in suitable windows and a sheet in compact space. Notes are context, rather than an artificial next step in the navigation hierarchy. Preserve source selection and inspector state through collapse. [NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview), [SwiftUI inspector](https://developer.apple.com/videos/play/wwdc2023/10161/)

Test iPad multitasking and resizable windows, large Dynamic Type, keyboard focus and panel dismissal. Native presentation is desirable even when Android and iOS expose the same domain actions.

## Essential state preservation

Across resize, rotation, pane changes and screen navigation retain:

- Active document, source selection and actual playback position.
- Chosen narration edition/voice and preparation queue.
- Source scroll/zoom and whether auto-follow is suspended.
- Frozen capture anchor, recording state and draft text.
- Notes selection, edits, focus and panel visibility where appropriate.

Audio and model ownership sit above individual screens. A layout transition must not recreate the player, restart generation or lose the recorder.

## Accessibility and interaction quality

Target clear contrast, useful text scaling, generous controls and semantic labels. The active passage and recording state require more than color. Keep screen-reader focus stable; avoid live announcements for every spoken word or partial transcript.

Provide useful chapter/note navigation, accessible recorder duration/state, keyboard shortcuts that respect text entry, pointer targets and reduced-motion behavior. Expose semantic source text and record actions for visible prepared passages alongside the PDF canvas; rendering alone is not an accessibility solution. [WCAG guidance for non-web software](https://www.w3.org/TR/wcag2ict-22/)

Validate the complete journey with assistive technologies and participants, including interaction between narration and the screen reader. Do not infer accessibility from a polished mockup.

## Design acceptance before implementation polish

Prototype the listening and note loop in compact and wide windows, using realistic text, long titles, empty/error/preparation states and large type. Check that someone can identify what is playing, record a thought and find its attachment without explanation.

Then build an original design system using native controls and consistent tokens. Preserve the useful structure of the references while choosing contrast, typography and motion through actual usability evaluation. Rhapsodos uses the implemented identity and licensed assets in [brand.md](brand.md); refine motion and contrast through measured usability.
