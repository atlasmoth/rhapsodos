# UI craft, haptics and performance

Status: **implementation requirement**, added 4 October 2026. The apps must feel exceptional in everyday use. Rich feedback, GPU effects and native motion are part of the product, with measurable budgets and graceful adaptation to older hardware. These targets are not measured results.

## Interaction standard

Use immediate local acknowledgement for every input. Play/pause, source seeking, recording and pane changes must respond without waiting for inference, database writes or network work. Follow acknowledgement with truthful preparation/commit state; never display successful saving before durable commit.

Build an original, coherent design system around the references in [design.md](design.md). The active passage, playback controls and record-thought action remain visually dominant. Native typography, spring motion, materials, responsive controls and careful transitions should make the interface feel deliberate at phone and tablet sizes.

The required quality applies from the first slice, including empty, preparing, error, recording and recovery states. A screen full of placeholder controls is not a completed feature.

## Rich haptic language

Treat haptics as designed interaction feedback with reusable semantic events. Use distinct, restrained patterns for selection, committed actions and boundaries, plus richer patterns where they improve control feel.

| Event | Intended feedback |
| --- | --- |
| Play/pause | Crisp lightweight acknowledgement at accepted command |
| Begin recording | Distinct clear start feedback before the microphone begins; avoid recording the motor's sound |
| Saved recording | Short confident success pattern after durable commit |
| Cancel/error | Distinguishable feedback, paired with visible/accessibility state |
| Scrub/chapter navigation | Throttled selection ticks at meaningful boundaries, stronger chapter boundaries |
| Download ready | Optional completion feedback while the user is actively interacting |
| Model preparation, speech progress | Visual state only; no continuous haptic loop or vibration on every spoken word |

Support an explicit haptics preference, hardware capability checks and system settings. Do not bypass disabled system feedback. Degrade to clear visual/semantic feedback when hardware lacks haptics. Rate-limit rapid actions and avoid overlapping patterns.

iOS uses Core Haptics for qualified custom patterns and native feedback generators/SwiftUI sensory feedback for standard events. Prepare resources ahead of interaction, handle engine interruption/reset, and avoid creating an engine per tap. Keep recording transitions silent at the appropriate point. [Core Haptics](https://developer.apple.com/documentation/corehaptics)

Android uses native view/Compose haptic feedback for standard semantics, with capability-qualified vibration effects only where custom patterns provide a benefit. Respect API availability and user settings. Do not assume every device exposes an amplitude-controllable actuator. [Android haptics](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)

## Shaders and GPU effects

Use effects that reinforce listening: an ambient player surface, subtle depth, restrained passage transition or a responsive control treatment. Keep the document text legible and the original PDF faithful. Effects must not hide state or interfere with hit testing/accessibility.

iOS uses Metal-backed SwiftUI shader effects available from iOS 17, with a focused Metal renderer only if it solves a measured need. Prefer small bounded effect regions and reusable compiled pipelines. Android uses AGSL/RuntimeShader where supported, with native Compose rendering and a simple fallback on older APIs. Metal is the iOS implementation, not an Android dependency. [SwiftUI Shader](https://developer.apple.com/documentation/swiftui/shader), [Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)

Avoid full-screen expensive blur chains, unbounded layers, synchronous shader compilation on interaction, repeated allocations per frame and continuously ticking offscreen effects. Animate only while the relevant surface is visible and the effect communicates something useful. Never let a decorative shader compete with audio generation or note capture.

Reduced motion uses a static or simplified treatment. Low Power Mode, elevated thermal state, backgrounding and constrained devices reduce resolution, frequency or disable optional effects. The fallback must remain intentionally designed.

## Responsiveness budgets

| Measure | Proposed target and qualification |
| --- | --- |
| Visible input acknowledgement | p95 under 50 ms for common local actions, measured on supported physical cohorts |
| Rendering | Meet the device's active refresh budget: 16.67 ms at 60 Hz; 8.33 ms at 120 Hz where supported and chosen |
| Navigation/resize | Smooth transitions without resetting playback, draft state or model ownership |
| Scrolling | Stable PDF zoom, pan and page browsing without synchronous rendering, OCR, DB access or inference on the UI thread |
| Cached playback start | p95 under 0.5 s under the defined qualified conditions |
| Startup | Measure cold and warm startup; establish cohort-specific targets rather than claiming one universal figure |
| Memory/energy | Bound joint page/inference/effect caches; compare sustained sessions to system speech/cached-audio baselines |

Measure frame deadlines and long-tail stalls, not only average FPS. A 120 Hz device does not prove a 120 Hz app. Thermal/resource adaptation is part of the experience, not a way to disguise unsupported models.

Native UI observes compact immutable snapshots. Avoid broad recomposition/invalidation for every audio sample, unnecessary cross-language events and decoding the entire document on every playback tick. Use lazy lists, stable item identity, bounded raster caches and off-main-thread extraction/storage/inference. Keep native progress events at the precision/frequency the visible surface needs.

## Evidence and release gate

Use Instruments/Core Animation/Metal profiling on iOS and Perfetto/Android rendering tools on Android in realistic release configurations. Capture cold start, scrolling, adaptive resize, play/seek, recording transitions and sustained playback with inference active. Keep measurements tied to commit/device/OS/model/effect settings.

Require physical-device evidence for supported cohorts, accessibility checks and battery/thermal sessions before describing the UI as qualified. Simulator compilation is useful build evidence and does not establish haptic quality, device energy behavior or sustained performance.

No unit-test source is committed to the repository. Appropriate integration tooling, temporary checks outside the repo and device evaluation still apply. Implement and track each coherent UI change in an atomic commit with its relevant documentation.
