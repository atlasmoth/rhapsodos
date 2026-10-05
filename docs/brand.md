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

The PDF canvas and neutral page paper use Background; neutral and muted text uses Ink. Both apps apply the same deterministic sRGB display mapping: chroma at or below 24 byte values is fully themed, chroma at or above 64 retains the original RGB, and intermediate chroma blends linearly over 40 steps. Rec. 709 luminance preserves antialiasing between paper and ink. This maps the public sample's muted green `#263B2C` to dark-theme ink `#CFC6BC`, giving 10.90:1 contrast against `#161412`; the earlier narrower blend gave only 2.32:1. **Original colours** restores the source page appearance while keeping the surrounding canvas themed. Grayscale and muted-colour images also change. Pixel recolouring cannot distinguish highly chromatic text from artwork, so arbitrary coloured-text contrast is not guaranteed; use original colours where retaining a light source page improves readability. Narration highlights remain outside this colour transformation: use the theme primary accent for themed paper and the light primary accent for contrast against original white paper.

## Icon and launch

The original mark occupies a 256-square coordinate system: seven rounded vertical bars, width 14, x positions 56, 77, 98, 119, 140, 161, 182; y positions 108, 88, 65, 49, 65, 88, 108; heights 40, 80, 126, 158, 126, 80, 40. Amber-to-ember bars sit on a near-black field with a restrained radial glow. The geometry is identical across native icons, onboarding and launch. Android uses separate adaptive foreground/background layers with a monochrome themed-icon layer; the foreground keeps the mark inside the launcher mask safe area. [Android adaptive-icon specification](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

The static operating-system launch view leads into a brief native bar reveal and traveling wave, with a Metal/AGSL glow when supported. No animation delays usable content; the overlay finishes within 900 ms and does not replay on every document change. Reduced motion uses a short opacity transition; disabled effects, low power, high thermal state and assistive technology receive a still, fully legible identity. Splash animation never acquires audio focus or vibrates automatically.

## Interaction

Favor a large tactile listening action, a persistent dock and a calm source card. Haptics distinguish selection, successful durable save and meaningful errors; they respect the user's setting. Recording has explicit text and shape cues, not just an orange pulse. Shaders are clipped to decorative surfaces and cease when hidden. No unbounded particle systems or per-frame source layout work.

Phone controls stay reachable with large text. Tablets and wide windows use native navigation split/adaptive panes, with library or outline, source and notes visible together when space permits. Audio and draft state survive pane changes. See [ui-quality.md](ui-quality.md) for measurement and accessibility gates.

Reference: [official Mistral brand page](https://mistral.ai/brand/) and [official site stylesheet](https://mistral.ai/_astro/astro.BOPo2zPB.css), inspected 4 October 2026. The reference is inspiration; all Rhapsodos mark geometry is original.
