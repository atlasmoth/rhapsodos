# Rhapsodos visual system

Rhapsodos is a listening product. Its identity combines warm paper, an ember accent, deliberate typography and an original mark that suggests both a voice waveform and the ribs of an open book. The source remains comfortable to read; controls feel tactile and immediate.

The reference is Mistral's Voxtral visual language: warm tonal contrast, bold typography, crisp technical labels and rhythmic graphic shapes. The official site declares ALTMistral, Inter and Space Mono. ALTMistral is not bundled because no redistribution permission has been established. Rhapsodos uses openly licensed Space Grotesk for display, Inter for interface/body text, and Space Mono for compact timing/status labels. The immutable source and checksums are in [font-manifest.json](font-manifest.json); bundled OFL licenses travel with both apps. Do not use Mistral logos or its model illustrations.

| Token | Light | Dark |
| --- | --- | --- |
| Background | `#FFF8EE` | `#161412` |
| Surface | `#FFFDF8` | `#211D19` |
| Ink | `#241C15` | `#FFF5E8` |
| Muted ink | `#756657` | `#C6B5A3` |
| Primary action | `#9F3800` | `#FFB58B` |
| Primary container | `#FFE3CC` | `#693116` |
| Decorative ember | `#F47A36` | `#F47A36` |

Use the accessible primary token for text and controls. Decorative ember is not a general text color. Native semantics, focus, Dynamic Type and Material typography scaling remain authoritative. Display typography must wrap rather than truncate essential actions. System, light and dark appearance are explicit user choices that survive restart.

Background and Ink apply to the surrounding PDF canvas, interface and accessible source context. PDF pages and covers preserve original colours, typography, text and artwork in both themes. No page-pixel colour mapping or PDF appearance preference is part of the visual system. Narration highlights use a separate translucent primary-accent overlay; they must keep the underlying source legible and retain the canonical page geometry.

## Icon and launch

The original mark occupies a 256-square coordinate system: seven rounded vertical bars, width 14, x positions 56, 77, 98, 119, 140, 161, 182; y positions 108, 88, 65, 49, 65, 88, 108; heights 40, 80, 126, 158, 126, 80, 40. Amber-to-ember bars sit on a near-black field with a restrained radial glow. The geometry is identical across native icons, onboarding and launch. Android uses separate adaptive foreground/background layers with a monochrome themed-icon layer; the foreground keeps the mark inside the launcher mask safe area. [Android adaptive-icon specification](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

The static operating-system launch view leads into a brief native bar reveal and traveling wave, with a Metal/AGSL glow when supported. No animation delays usable content; the overlay finishes within 900 ms and does not replay on every document change. OS Reduce Motion uses a short opacity transition; unsupported hardware, low power, high thermal state and assistive technology receive a still, fully legible identity. Splash animation never acquires audio focus or vibrates automatically.

## Interaction

Favor a large tactile listening action, a persistent dock and a calm source card. Haptics distinguish selection, successful durable save and meaningful errors; they respect OS settings and hardware capability. Feedback and decorative motion are enabled app behavior without separate app switches. Recording has explicit text and shape cues, not just an orange pulse. Shaders are clipped to decorative surfaces and cease when hidden. No unbounded particle systems or per-frame source layout work.

Library navigation uses three rounded tonal icon-only buttons in one row below branding: Search, Import and Settings. Sheet titles, Done placement, content/action order, spacing, cards and feedback share the app's design tokens across native platforms. Settings owns voice choice, downloads and transcription configuration.

Phone controls stay reachable with large text. Tablets and wide windows use native navigation split/adaptive panes, with library or outline, source and notes visible together when space permits. Audio and draft state survive pane changes. See [ui-quality.md](ui-quality.md) for measurement and accessibility gates.

Reference: [official Mistral brand page](https://mistral.ai/brand/) and [official site stylesheet](https://mistral.ai/_astro/astro.BOPo2zPB.css), inspected 4 October 2026. The reference is inspiration; all Rhapsodos mark geometry is original.
