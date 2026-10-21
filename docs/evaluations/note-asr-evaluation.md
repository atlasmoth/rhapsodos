# Recorded-note ASR evaluation — 21 October 2026

## Decision supported by this experiment

**Do not replace Whisper with the default Moonshine streaming pipeline on the evidence collected.** Whisper Small.en has the best transcription quality in this sample; Whisper Base.en is the stronger compact-default candidate pending native runtime checks. Base makes fewer word errors than both Moonshine streaming sizes, particularly on the quiet second-source recordings and controlled noise. Small.en improves quality further but its larger artifact and runtime footprint need a real mobile tradeoff, not an automatic extra model option.

This is a bounded engineering comparison, not a published benchmark or physical-phone qualification. Native WhisperKit/CoreML and whisper.cpp quantized results must determine the shipping choice. These host results compare transcript quality on identical audio; they do **not** establish mobile speed superiority for any model. Do not rank raw Whisper silence outputs against Moonshine's built-in VAD as if the pipelines use the same filtering.

The [committed metrics and provenance](note-asr-metrics.json) retain aggregate counts, artifact hashes and corpus identifiers without raw recordings or transcripts.

## Corpus and scoring

The preregistered corpus has 50 distinct human utterances: 40 LibriSpeech test-clean clips from 40 speakers (20 short 2–6 s and 20 longer 12–25 s), plus the first 10 FLEURS en_us validation rows. The selection seed is `20261021`. There are 12 controlled-noise variants (six LibriSpeech originals at both 10 dB and 20 dB white Gaussian SNR), four constructed 44–61 s notes (three longer clips each, separated by 0.4 s silence), four digital-silence probes, and four white-noise-only probes at −40 dBFS RMS. Blank probes last 1, 5, 15 and 30 s. Total: 74 cases, 66 with speech, 813.015 s of speech-case audio.

The 50 unique utterances contain **1,199 reference words**. The 66-case stress total contains **2,065 words / 11,310 normalized characters**. Reused source utterances mean these 66 cases are correlated: their aggregate is not 66 independent samples. Both the unique-utterance result and stress subsets are reported. All audio is mono 16 kHz PCM16, with original gain preserved; synthetic noisy mixtures are scaled only when necessary to avoid clipping. No user recordings were used or uploaded.

Both references and predictions use the same pinned Transformers `EnglishTextNormalizer`: case folding, punctuation handling, English contraction and number normalization. WER is `(substitutions + deletions + insertions) / reference words` after summing edit counts across a cohort; CER uses the same normalized strings, including spaces. Neither metric is the mean of per-clip percentages. Empty results and runtime failures remain scored; blank-reference cases have separate false-positive counts. All valid runs finished with **zero runtime failures**.

FLEURS is a deliberately small second-source panel, not an accent-balanced or spontaneous-note study. Seven of its ten recordings have RMS below −49 dBFS. `fleurs-0` (source ID 1548, 6.54 s, 15 words, approximately −49.9 dBFS RMS) and `fleurs-3` (4.14 s, 10 words, approximately −63.4 dBFS RMS) are completely omitted by both default Moonshine streaming sizes. The original float audio sample counts were checked against each dataset row; these are complete recordings, not truncated preview assets.

## Quality: planned streaming paths and Whisper reference models

Whisper entries below are ungated FP32 references, not the final CoreML/GGML packages. Moonshine entries use the actual quantized native runtime with bounded streaming and its default speech detector.

| Model/path | S / D / I, 66 speech cases | Stress WER | CER (errors / chars) | Unique 50 WER (errors / 1,199) | Empty speech outputs |
|---|---:|---:|---:|---:|---:|
| Whisper Tiny.en FP32 | 91 / 10 / 17 | 5.71% | 2.29% (259/11,310) | 6.09% (73/1,199) | 0 |
| Whisper Base.en FP32 | 54 / 9 / 10 | 3.54% | 1.62% (183/11,310) | 3.75% (45/1,199) | 0 |
| Whisper Small.en FP32 | 36 / 9 / 7 | 2.52% | 1.06% (120/11,310) | 2.42% (29/1,199) | 0 |
| Moonshine Tiny 34M streaming | 107 / 43 / 37 | 9.06% | 5.14% (581/11,310) | 10.51% (126/1,199) | 2 |
| Moonshine Small 123M streaming | 62 / 40 / 17 | 5.76% | 3.46% (391/11,310) | 6.84% (82/1,199) | 2 |

Subset cells are **edit count / reference words (WER)**. Each model sees the same files.

| Model/path | LibriSpeech short (20) | LibriSpeech long (20) | FLEURS (10) | Noise 10 dB (6) | Noise 20 dB (6) | Long notes (4) |
|---|---:|---:|---:|---:|---:|---:|
| Whisper Tiny.en FP32 | 16/191 (8.38%) | 27/832 (3.25%) | 30/176 (17.05%) | 16/170 (9.41%) | 4/170 (2.35%) | 25/526 (4.75%) |
| Whisper Base.en FP32 | 3/191 (1.57%) | 21/832 (2.52%) | 21/176 (11.93%) | 5/170 (2.94%) | 1/170 (0.59%) | 22/526 (4.18%) |
| Whisper Small.en FP32 | 2/191 (1.05%) | 13/832 (1.56%) | 14/176 (7.95%) | 5/170 (2.94%) | 2/170 (1.18%) | 16/526 (3.04%) |
| Moonshine Tiny 34M streaming | 5/191 (2.62%) | 53/832 (6.37%) | 68/176 (38.64%) | 27/170 (15.88%) | 4/170 (2.35%) | 30/526 (5.70%) |
| Moonshine Small 123M streaming | 3/191 (1.57%) | 24/832 (2.88%) | 55/176 (31.25%) | 13/170 (7.65%) | 3/170 (1.76%) | 21/526 (3.99%) |

Moonshine Small is competitive on clean speech and constructed long notes. Its default segmentation restores long-note accuracy compared with the unbounded diagnostic path, but its quiet-speech omissions materially hurt a note-taking app. Whisper Small and Base produce nonempty transcripts for every speech clip. This sample supports further native evaluation of Whisper, not a claim that Moonshine is intrinsically incapable of better results with a different speech detector.

## Silence and noise: pipeline diagnostics

| Model/path | Digital silence: raw nonempty clips / 4; normalized words | Noise-only: raw nonempty clips / 4; normalized words |
|---|---:|---:|
| Whisper Tiny.en FP32 | 4/4; 2 | 4/4; 0 |
| Whisper Base.en FP32 | 4/4; 4 | 4/4; 25 |
| Whisper Small.en FP32 | 4/4; 4 | 4/4; 4 |
| Moonshine Tiny 34M streaming | 0/4; 0 | 0/4; 0 |
| Moonshine Small 123M streaming | 0/4; 0 | 0/4; 0 |

Blank-output safety counts raw nonempty text, separately from normalized word counts. The scoring normalizer removes parenthesized annotations and punctuation; that is appropriate for WER but must not hide descriptions or punctuation-only output in no-speech checks. In particular, host Whisper Tiny emits raw punctuation on six blank probes and a word on two: eight nonempty raw outputs, two normalized-word false positives. Speech WER/CER is unchanged. Moonshine streaming's default VAD rejects all eight blank probes. The Whisper reference deliberately supplies the waveform without a separate VAD and does not set the standard no-speech/log-probability thresholds; its false positives are **ungated baseline behavior**, not an approved product pipeline. A shipping Whisper adapter needs actual no-speech handling checked against silence *and* these quiet clips, so suppressing hallucinations does not discard legitimate notes. Recording durability must remain independent of ASR success.

## Host performance: descriptive, not cross-engine mobile rankings

Fresh Python process for each model, CPU batch 1, requested four Torch/BLAS threads; Torch interop 1. Moonshine's internal native thread count was not separately verified. Runs were serial while native agents paused heavy compilation/inference. Peak RSS includes the Python wrapper/runtime and cumulative native allocation; it is not just weights. Creation time includes Python model/runtime initialization and local artifact access; OS caches were **not** flushed, so this is not an uncached-storage cold-start benchmark. First inference is the same 3.2 s clip. Warm RTF is median per-clip elapsed/audio time excluding that first clip, across the remaining speech cases. Streaming files are fed as fast as computation permits, with updates every 0.5 s of audio, not wall-clock microphone input.

| Model/path | Downloaded host artifacts (decimal MB) | Creation s | First inference s | Warm median RTF | Peak process RSS MiB |
|---|---:|---:|---:|---:|---:|
| Whisper Tiny.en FP32 | 155.00 | 1.549 | 0.114 | 0.023 | 860 |
| Whisper Base.en FP32 | 294.34 | 2.124 | 0.672 | 0.032 | 1004 |
| Whisper Small.en FP32 | 970.93 | 1.554 | 1.016 | 0.071 | 1958 |
| Moonshine Tiny 34M streaming | 45.23 | 0.056 | 0.127 | 0.035 | 506 |
| Moonshine Small 123M streaming | 142.30 | 0.404 | 0.284 | 0.075 | 929 |

Host: Apple M4 Pro, 48 GiB RAM, macOS 27.0.1 ARM64. Runtime versions: `moonshine-voice 0.1.5`, `torch 2.13.0`, `transformers 5.14.1`, `numpy 2.5.1`, `soundfile 0.14.0`, `jiwer 4.0.0`, `scipy 1.18.0`. Python came from the user-requested conda `dl` environment; existing Torch/Transformers versions were not changed. HF FP32 artifact sizes above are **not** proposed app-download sizes; the native agents qualify separate CoreML / GGML artifacts.

## Rejected diagnostic configuration

The first Moonshine diagnostic follows upstream's isolated-utterance evaluation method: `vad_threshold=0`, `vad_max_segment_duration=100000`, then `transcribe_without_streaming`. That setting avoids VAD omissions on short, presegmented speech but is unsuitable for arbitrarily long saved notes. It is retained as diagnostic evidence, excluded from the shipping-path comparison.

| Diagnostic path | All-speech edits / 2,065 (WER) | Long-note edits / 526 (WER) | Peak RSS MiB |
|---|---:|---:|---:|
| moonshine-tiny, VAD disabled / unbounded segment | 257/2,065 (12.45%) | 149/526 (28.33%) | 2362 |
| moonshine-small, VAD disabled / unbounded segment | 204/2,065 (9.88%) | 145/526 (27.57%) | 3818 |

For example, Small's diagnostic long-note errors contain 134 deletions out of 526 words. Bounded default streaming reduces this to two deletions, at much lower measured peak RSS. This is why isolated vendor WER or a short-clip batch demo cannot qualify the mobile saved-note path. No post-hoc VAD or loudness tuning was used in the primary comparison, and no legacy Moonshine27M baseline was needed to resolve the current candidates.

## Exact candidate pins and invocation

Moonshine uses the **actual quantized `.ort` assets**, not float Transformers weights, from `https://download.moonshine.ai/model/{tiny-streaming-en,small-streaming-en}/quantized_26_08_21`. Architecture enums are `TINY_STREAMING=2` and `SMALL_STREAMING=4`. Runtime is 0.1.5. Each package has eight files; exact SHA-256 values and sizes are retained in the [metrics and provenance](note-asr-metrics.json). This avoids the older pre-July 2026 Tiny quantization issue described by upstream.

The measured bounded path sets `return_audio_data=false` and `decode_incomplete_lines=false`, keeps default VAD, calls `create_stream(update_interval=0.5)`, starts a stream per file, adds chunks of 8,000 samples, then sets FORCE_UPDATE, stops, captures the final transcript and closes the stream. Python `add_audio` performs the periodic update itself; the runner does not duplicate that update. Production adapters must retain finalized lines and support bounded cancellation between native calls.

Whisper snapshots:

| Model | Hugging Face revision |
|---|---|
| openai/whisper-tiny.en | `87c7102498dcde7456f24cfd30239ca606ed9063` |
| openai/whisper-base.en | `911407f4214e0e1d82085af863093ec0b66f9cd6` |
| openai/whisper-small.en | `e8727524f962ee844a7319d92be39ac1bd25655a` |

Whisper: CPU FP32, greedy `num_beams=1`, `do_sample=false`, `temperature=0`, `condition_on_prev_tokens=false`, `return_timestamps=true`, `max_new_tokens=440`; processor `truncation=false`, `padding=longest`, attention mask enabled. The initial harness attempt used 448 new tokens and hit a start-token context-bound configuration error. That invalid run was retained outside the result directory and never counted as model failures or used to rank candidates. Every reported Whisper result was rerun with the valid 440 setting.

Normalizer JSON SHA256: `bf1c507dc8724ca9cf9903640dacfb69dae2f00edee4f21ceba106a7392f26dd`.

## Corpus provenance and reproduction

Both [LibriSpeech/OpenSLR12](https://www.openslr.org/12) and [FLEURS](https://huggingface.co/datasets/google/fleurs) are CC-BY4.0. LibriSpeech is read public-domain English audiobook speech, not spontaneous notes. The generated noise and concatenations are deterministic derivatives described above. Attribute the original datasets when redistributing derivatives. No raw audio or full transcript needs to enter the app repository.

LibriSpeech `test-clean.tar.gz` SHA256: `39fde525e59672dc6d1551919b1478f724438a95aa55f874b576be21967e6c23`.
FLEURS dataset revision: `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, config `en_us`, split `validation`, rows 0–9. Source audio sample counts match row metadata. The test-split preview endpoint failed on its row-group size limit, so validation was selected before model inference.
Corpus manifest SHA256: `877951b09711fb5ac35c2a9255cf03b433315e0da910f3556f20f35ce2422004`.

LibriSpeech short IDs:

`6930-76324-0001`, `61-70968-0055`, `7021-85628-0014`, `2094-142345-0016`, `5142-33396-0005`, `7176-88083-0015`, `3729-6852-0031`, `1580-141083-0049`, `237-134500-0028`, `1320-122617-0027`, `4446-2275-0035`, `5105-28241-0003`, `8463-287645-0012`, `4970-29095-0005`, `4992-41806-0005`, `8455-210777-0020`, `1089-134686-0007`, `1995-1826-0010`, `260-123440-0017`, `908-31957-0005`.

LibriSpeech long IDs:

`2961-960-0008`, `7729-102255-0032`, `1284-1180-0030`, `1188-133604-0009`, `4507-16021-0023`, `1221-135767-0022`, `3575-170457-0043`, `8224-274381-0008`, `4077-13751-0005`, `3570-5694-0009`, `121-123859-0000`, `6829-68771-0000`, `8555-284447-0010`, `2300-131720-0031`, `5639-40744-0000`, `8230-279154-0040`, `672-122797-0064`, `7127-75946-0028`, `5683-32879-0017`, `2830-3979-0007`.

FLEURS source IDs in row order: 1548, 1620, 1510, 1578, 1652, 1531, 1528, 1595, 1559, 1633.

External evidence directory: `/private/tmp/rhapsodos-asr-evaluation`. `prepare_corpus.py` reconstructs the selected/derived WAV files from the pinned archive and cached FLEURS rows/audio; `corpus-manifest.json` records every clip's ID, parent relation, duration, hash and reference. `preregistered-protocol.json` records the original plan and harness correction. `run_model.py` runs a pinned local model and writes per-case outputs; `summarize.py` recomputes all aggregate counts. `moonshine-artifacts.json`, `whisper-artifacts.json`, `environment.json` and per-run result JSON establish exact artifacts and runtime provenance.

Reproduction with the user-requested Python executable, after obtaining the pinned corpus and artifacts:

```sh
cd /private/tmp/rhapsodos-asr-evaluation
/Users/atlasmoth/miniconda3/envs/dl/bin/python prepare_corpus.py
/Users/atlasmoth/miniconda3/envs/dl/bin/python run_model.py moonshine-tiny --stream
/Users/atlasmoth/miniconda3/envs/dl/bin/python run_model.py moonshine-small --stream
/Users/atlasmoth/miniconda3/envs/dl/bin/python run_model.py whisper-tiny.en
/Users/atlasmoth/miniconda3/envs/dl/bin/python run_model.py whisper-base.en
/Users/atlasmoth/miniconda3/envs/dl/bin/python run_model.py whisper-small.en
/Users/atlasmoth/miniconda3/envs/dl/bin/python summarize.py
```

Primary runtime/model references: [Moonshine accuracy and quantization notes](https://moonshine-voice.readthedocs.io/en/latest/models/accuracy/), [Moonshine native runtime](https://github.com/moonshine-ai/moonshine), [pinned upstream accuracy script](https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/scripts/eval-librispeech.py), [OpenAI Whisper](https://github.com/openai/whisper).

## Remaining selection gates

Native qualification is being performed separately on the exact saved-file pipeline: quantized whisper.cpp on Android and WhisperKit/CoreML on iOS, including the quiet FLEURS omissions, silence/noise, long-note boundaries, cancellation and resource behavior. Emulator/simulator measurements remain host-dependent, so physical ordinary-phone sustained-memory, thermal, battery and latency checks remain required. The present sample cannot establish accent breadth, spontaneous disfluencies, names across cultures, multilingual quality, real-room noise or far-field microphone robustness. It is sufficient to reject an assumed automatic Moonshine upgrade and identify Whisper Base/Small as the next native candidates, not to claim universally best ASR.
