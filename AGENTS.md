# Contributor and agent instructions

These instructions apply throughout this workspace. Current instructions from the human user take precedence. Read this file before changing code or documentation.

## Product and scope

Build a listening-first PDF app for everyday reading and accessibility. The reader follows narration and gives access to the original page. Voice notes are recorded at a frozen source position and transcribed asynchronously. English is the first launch language; preserve multilingual contracts.

The main reader surface is the original PDF with the active narration passage highlighted. Do not restore a separate "Listen and follow" or reflow pane. Double-tapping a prepared passage pauses narration and freezes that passage's source attachment before audio-note capture. Single taps do not record. For a passage outside the current narration, attach at passage offset zero without seeking the book; save/resume retains the frozen listening cursor. Blank-page taps and browsing gestures must not record or seek. Manual PDF browsing pauses narration and automatic following; Play resumes the frozen listening position and restores following. Do not expose a follow-narration control. Preserve accessible text and an equivalent record action within the PDF surface.

Keep the PDF surface free of visible page/zoom/fit controls and the player free of passage-count and routine voice-status labels. Retain useful preparation and failure feedback. Display original PDF colours, typography, text and artwork in every app theme. App appearance affects the surrounding interface, not page content; do not add a PDF recolouring preference. Preserve narration highlights, source PDFs, OCR inputs, anchors, gestures and accessible browsing.

Architecture plans are not, by themselves, authorization to scaffold or implement the apps. Follow the current human task's scope. When implementation is authorized, work through a complete usable slice rather than accumulating speculative infrastructure.

Read [README.md](README.md), [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/product.md](docs/product.md), plus the platform and feature documents relevant to the task. Update the documents when actual decisions supersede proposals. Identify assumptions and unresolved device qualification honestly.

## Architectural boundaries

- Use KMP for shared domain contracts, document transformations, anchors, repositories, jobs and privacy policy. Keep SwiftUI and Compose as the native UI layers.
- Android uses the agreed Compose/Material3 Expressive/Navigation3/Material3 Adaptive/Ktor/Koin/LiteRT stack. iOS uses Swift, SwiftUI and CoreML with an iOS 18 baseline.
- Native platform code owns audio transport, media sessions, capture, rendering, model execution and OS lifecycle. Native playback time is authoritative.
- Preserve source, display and spoken text separately with explicit versioned mappings. An audio timestamp or page number alone is not a durable note anchor.
- Persist tagged `cropTopLeftV1` geometry in the unrotated PDF crop box. Apply rotation in native rendering and hit testing. Repair untagged geometry only after fresh preparation proves the canonical text: exact whole-page equality, or embedded whole-page outer-whitespace trimming with an explicit UTF-16 prefix offset. OCR requires exact equality. Never use substring/fuzzy matching or move user anchors to accommodate a drawing error.
- Pause and freeze the source position before capture. Save durable audio before acknowledging a saved note. ASR failure must not prevent saving or move the attachment.
- Enforce local-only policy in every content-processing and sync path. Never silently send a document or voice note to a remote fallback.
- Core listening and note capture must not require a large language model, a network connection, an account or a flagship device. Optional packs need real device qualification.
- Use one canonical persistence schema and clear ownership. Avoid duplicate native stores that disagree about documents, notes or progress.
- Treat user recordings and edits as durable user data; generated narration and model files are separately managed assets or caches.

## Code comments

**Do not add superfluous comments. Comment only when it is very necessary.** Prefer clear naming, small functions and straightforward control flow.

Comments are appropriate for a non-obvious invariant, a necessary platform workaround, a surprising constraint or rationale that cannot be expressed clearly in code. Explain why that behavior must exist. Do not narrate obvious statements, add decorative section banners, repeat signatures in docstrings, retain commented-out implementations or leave speculative TODO padding. Remove comments made stale by a change.

This rule does not limit useful architecture documentation or documentation required for an external contract.

## Verification and unit tests

**Do not commit unit tests to this repository. Do not add or leave unit-test source files in the repository.** This is an explicit project preference, not permission to skip verification.

Use the checks appropriate to the change: compilation, lint/static analysis, integration or end-to-end checks, representative document evaluation, manual interaction and physical-device qualification. Temporary unit checks or experimental harnesses may run outside the repository, such as in a task-specific temporary directory, and must not enter a commit. Do not create tests that merely mirror implementation details.

For audio, notes, extraction, privacy and downloads, verify the relevant failure and recovery behavior as well as the happy path. Do not claim a model or app is qualified based on an emulator, a library's deployment target or one isolated vendor benchmark. Report what actually ran and any remaining limitation.

Documentation-only work requires file/link/consistency review; it does not require an app test suite. Broaden verification when changes, failures or unresolved risks justify it, not by repeatedly rerunning already sufficient checks.

## Removal and compatibility during the build

**The product is pre-release. There is no obligation to preserve obsolete feature behavior or old internal APIs. Remove old features freely when the authorized change replaces or eliminates them.**

Delete their unused code, settings, assets, dependencies and documentation. Do not retain deprecated wrappers, compatibility aliases, migration ladders, legacy feature flags or parallel old implementations without a current product requirement. Development schemas and disposable fixtures can be reset rather than supported indefinitely.

This policy does not authorize deleting unrelated user work, real recordings or user edits. Preserve durability within the current product, existing-archive restoration, source-anchor reconciliation and atomic model replacement. Supporting iOS 18 is an agreed platform requirement, not obsolete feature compatibility. Revisit the policy when real releases create a user-data migration obligation.

## Atomic commits

**Track all work in atomic commits.** Each commit represents one coherent change with its necessary code, configuration and documentation, and should be independently understandable and reviewable. Keep the repository buildable at commit boundaries whenever a build exists.

- Inspect the working tree before editing and before staging. Separate unrelated tasks and never sweep unrelated user changes into a commit.
- Complete relevant validation before committing. Include the validation outcome and any known remaining gate in the task handoff.
- Use concise commit messages describing the concrete change. Avoid giant catch-all commits, speculative WIP commits and unrelated formatting churn.
- Stage deliberate paths or hunks. Do not blindly stage the entire working tree.
- Coordinate commit ownership when agents share a checkout. The integration owner can create atomic commits after reviewing delegated changes; every agent should not independently stage the same files.
- Do not rewrite existing history, amend someone else's commit, force push or discard work without authorization. Creating local commits does not authorize pushing, publishing or merging.

If a workspace is not a Git repository, state that limitation and establish version control when authorized as part of implementation. Do not claim work has been committed until it has. The user has now authorized implementation and the required local atomic-commit workflow; pushing or publishing still requires authorization.

## Collaboration workflow

1. Inspect the workspace and relevant instructions; determine the actual task and its acceptance criteria.
2. Read the relevant architecture. Confirm concrete interfaces before splitting interdependent work.
3. Delegate independent tasks with explicit file/component ownership, inputs, expected output and validation. Prefer disjoint edits; do not let several agents independently redesign the same contract.
4. Communicate discoveries that affect another component promptly, especially anchor formats, storage ownership, runtime availability and audio lifecycle.
5. Integrate and review the actual result. Resolve conflicting assumptions in one authoritative contract and update dependent documentation.
6. Validate the complete affected journey, create atomic commits where repository workflow is established, and report the outcome concisely.

Use an existing suitable checkout or worktree when available. Do not create speculative worktrees in a non-Git workspace. Never overwrite or revert unrelated changes. Search with `rg`/`rg --files` first and batch independent reads where useful.

Proceed autonomously within the user's authorized scope. Do not repeatedly ask permission for ordinary reversible edits or routine implementation choices. Ask only when missing information materially blocks the result or an action needs authorization. Do not message outside this task, create user-facing chats, publish, deploy or merge unless authorized.

Keep updates meaningful: explain findings, uncertainties and the next resolving step. Final handoffs should say what changed, how it was verified and what materially remains. Use clickable file links. Do not present plans or unperformed checks as finished implementation.

## Dependencies, privacy and product polish

Verify changing APIs, licenses, model artifacts and prices against current primary sources. Pin versions only after qualification. Treat upstream samples as reference material, not proof of production quality or permission to copy their entire dependency stack.

Keep secrets out of source and logs. Do not log document text, filenames, passage quotes, recordings or transcripts in ordinary diagnostics. Provider credentials belong on an optional backend or in a deliberate user-owned credential design, never embedded in an app.

Present consumer choices as voices, transcription and offline readiness. Runtime names, tensors and benchmark internals belong in optional advanced details. Build usable wide-window layouts, accessibility semantics and failure states alongside the core interaction.

## UI and app identity requirements

The UI must be exceptional: rich intentional haptics, polished native motion, tasteful GPU shaders, immediate input response and measured sustained performance. Follow [docs/ui-quality.md](docs/ui-quality.md). Use Core Haptics and Metal-backed SwiftUI effects on iOS; native haptics and supported AGSL/Compose effects on Android. Feedback and decorative motion are enabled app behavior, without app-level switches. Respect OS Reduce Motion, system-disabled haptics, hardware capability, low power, thermal pressure and older devices. Decorative effects must not compromise narration, recording, legibility or accessibility.

Keep the native apps consistent in action placement and content. Library actions below branding are exactly three icon-only buttons in one row: Search → Import → Settings, with accessible names and no visible titles. Settings provides voice selection, speech downloads and transcription configuration. Native sheets share a title-leading, Done-trailing header, content/action order, spacing, cards and feedback while preserving platform-native presentation and durable save rules. The playback dock puts document identity, speed, volume and transport above the whole-file slider, with its percentage immediately to the slider's right. Compact layouts center transport in a second row. See [docs/design.md](docs/design.md) for the shared arrangement and accessibility labels.

Both apps use the application/bundle ID **`com.osuji.ereaderapp`**. The current app version is **`0.01`**, with initial build/version code **`1`**. Keep build metadata and documentation consistent and verify the actual produced app metadata. The display name is **Rhapsodos**. Follow [docs/brand.md](docs/brand.md) for the unified typography, light/dark palette, original identity and native launch motion.
