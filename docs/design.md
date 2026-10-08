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

Use a coherent, quiet visual system: warm neutral reading surfaces, comfortable typography, strong controls and one controlled accent for playback/current passage. Covers can contribute secondary color without compromising contrast. Offer light, dark and accessible reading themes.

The main screen should communicate **listen, follow, remember**. A large cover or waveform can establish identity, but should not displace the passage and recording action. The source text is the useful visual accompaniment to audio.

The original PDF is the main reader and remains a faithful zoomable canvas. There is no separate "Listen and follow" or reflow pane. Use source geometry for a calm active highlight and note markers; never redraw the document into an invented layout and present it as the original. Tapping the active highlight pauses narration and opens audio-note capture at the frozen source position. Keep blank space and browsing gestures free of recording actions.

Motion explains transitions and preparation. It must respect reduced-motion preferences and should not move content with every spoken word. Use a stable highlight rather than constant scrolling animation.

## Main surfaces

Book thumbnails show the original first PDF page, including in the playback dock. Native adapters render small covers asynchronously with bounded caches. Covers retain their original colours in both themes; unavailable or damaged files receive a neutral document placeholder. Cover generation never changes the source PDF or reader geometry.

Global search, offline voices, settings and import sit immediately beneath the Rhapsodos logo and name in the library pane. The actions wrap when width or text size requires it. Compact readers return to the library for these actions without restarting narration; document-specific navigation, search, bookmarks and notes remain with the PDF.

The PDF header shows the document title and document tools.

| Surface | Content and primary action |
| --- | --- |
| Library | Original covers, progress on each file, resume by opening a file, preparation/offline status and import action |
| PDF reader | Original page, active narration highlight, tap-to-record, bookmarks, follow control and persistent playback dock |
| Notes | Source-attached recordings, transcript state, edit/replay and jump-to-passage |
| Recorder | Frozen source excerpt, duration, explicit recording status, cancel and save/resume |
| Offline voices | Preview, language/accent, recommendation, size, download and delete |
| Voice-note transcription | Language, device suitability, installation and privacy status |
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

Wide listening shows the PDF in the center and can expose notes for the current document on the right. A library or outline belongs on the left. Do not force four simultaneous panes merely because a large display permits it. Offer a focused listening mode and explicit panel visibility.

The source and notes scroll independently. Manual source scrolling suspends auto-follow and shows a return-to-current-passage action. Seeking moves narration only through an explicit listening command. Opening a note should expose its source context without inadvertently starting playback.

### Android

Compose and Material3 Expressive define controls, type, shapes and motion. Material3 Adaptive handles available-window/posture decisions. Navigation3 is the navigation owner; use its adaptive list-detail/supporting-pane scenes rather than creating multiple independent back stacks for the same selection. A navigation suite can adapt primary navigation between compact and wider arrangements.

Map outline/source and source/notes relationships deliberately to scenes. Preserve selection and saved state when panes change. Test fold hinges, keyboard/pointer input, font scaling and resizable windows. Check current stable versus experimental APIs before adopting the full expressive surface. [Android list-detail guidance](https://developer.android.com/develop/adaptive-apps/guides/list-detail), [Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)

### iOS

Use `NavigationSplitView` for navigation and source detail. Use the iOS 17 inspector for supplementary anchored notes: it can appear as a trailing panel in suitable windows and a sheet in compact space. Notes are context, rather than an artificial next step in the navigation hierarchy. Preserve source selection and inspector state through collapse. [NavigationSplitView](https://developer.apple.com/documentation/swiftui/navigationsplitview), [SwiftUI inspector](https://developer.apple.com/videos/play/wwdc2023/10161/)

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

Provide useful chapter/note navigation, accessible recorder duration/state, keyboard shortcuts that respect text entry, pointer targets and reduced-motion behavior. Expose semantic source text and the active-highlight record action alongside the PDF canvas; rendering alone is not an accessibility solution. [WCAG guidance for non-web software](https://www.w3.org/TR/wcag2ict-22/)

Validate the complete journey with assistive technologies and participants, including interaction between narration and the screen reader. Do not infer accessibility from a polished mockup.

## Design acceptance before implementation polish

Prototype the listening and note loop in compact and wide windows, using realistic text, long titles, empty/error/preparation states and large type. Check that someone can identify what is playing, record a thought and find its attachment without explanation.

Then build an original design system using native controls and consistent tokens. Preserve the useful structure of the references while choosing contrast, typography and motion through actual usability evaluation. Brand naming and final visual assets remain open.
