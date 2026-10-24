# Interface and identity

Rhapsodos is a listening-first PDF reader. SwiftUI and Compose use the same action order, labels and visual tokens while retaining native presentation and accessibility.

## Identity

Use Space Grotesk for display text, Inter for interface/body text and Space Mono for compact timing/status labels. Bundled font licenses and source records are described in [third-party.md](third-party.md). The original seven-bar mark combines a voice waveform and an open book; retain its shared geometry in app icons and launch artwork.

| Token | Light | Dark |
| --- | --- | --- |
| Background | `#FFF8EE` | `#161412` |
| Surface | `#FFFDF8` | `#211D19` |
| Ink | `#241C15` | `#FFF5E8` |
| Muted ink | `#756657` | `#C6B5A3` |
| Primary action | `#9F3800` | `#FFB58B` |
| Primary container | `#FFE3CC` | `#693116` |
| Decorative ember | `#F47A36` | `#F47A36` |

Use primary for controls and text; decorative ember is not a general text colour. System, light and dark appearance persist across launches. Theme changes update the reader, presented sheets and system controls without resetting scroll, search, drafts or audio. PDF pages and covers always retain their original colours, typography and artwork.

## Library and reader

- Place exactly three icon-only tonal buttons beneath the logo/name: **Search → Import → Settings**. Give each an accessible name. The file list follows directly, without slogans or a privacy/footer section.
- Use the original first PDF page as each cover, with a neutral fallback. Each file shows its own progress and opens at its saved listening position.
- The reader is the original PDF with a separate, legible narration highlight. Do not add a reflow pane, PDF page/zoom/fit controls, passage counts or routine voice-status labels.
- The document header contains **Contents → Search → Notes**, without repeating the document title. Notes opens a side panel or sheet without changing playback. Search has its own sheet; library search spans files, while in-file search stays within that document. Selecting a result moves to its source position and remains paused.
- Double-tapping a prepared passage pauses narration and opens voice-note capture at a frozen source attachment. A different passage attaches at offset zero without seeking the book. Save/resume retains the listening cursor. Single taps, blank space and browsing gestures never record. Provide an equivalent accessible action on the PDF surface.
- Manual PDF browsing pauses audio and automatic following. Play returns to the frozen listening position and restores following. There is no follow toggle, bookmark/export-document menu or write-note menu action.

The playback dock places cover/title, speed, output volume and **Previous → Play/Pause → Next** above the whole-file slider. Tapping the cover/title opens **Document details**, showing the full selectable title, page count and file size for that document. The sheet scrolls for long titles and large text, uses a trailing Done action, and leaves playback and the listening position unchanged. At compact widths, transport moves together into a centred second row. Put the percentage immediately to the slider's right; show preparation/error feedback when needed. Keep speed directly accessible and volume tied to the native output route. The dock has no microphone button.

## Settings and sheets

Settings starts with a separate **Download voices and transcription** card with a trailing chevron, above **Reading voice** and the **Transcribe voice notes** switch. Also include Playback speed, Appearance and Documents, with **Export document archive** above **Restore a document archive**. Export contains the selected PDF, recordings, transcripts and listening position; disable it during conflicting document/capture work.

Speech sheets use one contextual action: **Download & use**, **Download & enable**, or **Enable** for verified transcription. Download, checking and guarded activation form one flow. Show relevant progress, Pause, Resume and Retry after failure; keep requirements, check history and removal in Details. No manual check/Refresh button, sample playback, outbound license links or routine technical prose. Closing a sheet does not cancel setup or start paused narration. See [model-manager.md](model-manager.md).

English voices share one Phonon download card. Ready packs expose named voice rows with language/accent and a selected checkmark; Narrators precede an initially collapsed Characters section. The focused Reading voice sheet exposes these rows; the combined manager places them behind **Choose a voice**. Do not repeat the model download per speaker or duplicate selectors in Settings.

Native sheets share a leading title, trailing Done action, stable controls, bounded scrolling content and consistent spacing/tonal cards. Secondary header actions precede Done. Recording and transcript edits require explicit durable save/discard decisions; closing a sheet must not silently commit or discard them. Keep privacy/about sections and haptics, motion and PDF-colour switches out of Settings.

## Adaptive panes

Adapt to available window space, text size and posture. Compact layouts show one destination; wider layouts can show library, PDF and anchored notes with independent scrolling. Hide supplementary panes before making the source unusably narrow. Resizing must preserve the document, listening cursor, PDF scroll/zoom, selected voice, capture anchor and note drafts; audio/model ownership stays above screens.

Expose one library control at a time: **Hide library** beside library branding when visible, or **Show library** in the reader header when hidden. A left-edge swipe also reveals it. These actions preserve the selected document and playback state. Keep the three library actions separate from this control.

iPhone uses a focused NavigationStack and full-width library overlay while keeping the PDF mounted. iPad uses NavigationSplitView, with native narrow-window collapse. Hide native navigation bars that would duplicate sidebar controls. Android uses its adaptive list/detail layout and the same single Show/Hide control across folded and expanded states. Notes use a trailing pane when space permits and a sheet otherwise.

## Feedback and accessibility

Haptics and decorative motion are enabled behavior without app-level switches. Respect OS motion/haptic settings, assistive technology, hardware capability, low power and thermal pressure. iOS uses native/Core Haptics feedback and a bounded Metal glow; Android uses native haptics and Compose drawing. Keep effects off PDF pixels, stop hidden effects and avoid competing with narration or capture. Launch motion must not delay usable content, acquire audio focus or vibrate automatically.

Use distinct feedback for accepted actions, errors and successfully committed saves; never signal a saved note before durable storage. Avoid continuous haptics or animations on every spoken word. Provide static, legible fallbacks.

Support Dynamic Type/font scaling, generous touch targets, semantic labels, visible focus and keyboard/pointer navigation. Expose prepared source text and record actions alongside the PDF canvas. Highlight and recording status need cues beyond colour; keep screen-reader focus stable and avoid word-by-word announcements. Check the complete listening/note journey with assistive technologies and physical-device profiling before claiming accessibility or sustained performance qualification.
