package com.ereader.shared

import kotlinx.serialization.json.Json

internal object ModelCatalog {
    val packs: List<ModelPack> = Json.decodeFromString("""
[
  {
    "id": "android-kitten-english",
    "name": "Ember • English voice",
    "capability": "tts",
    "platform": "android",
    "runtime": "litert",
    "version": "d4662d891f9b",
    "languageTags": [
      "en"
    ],
    "minimumOS": 28,
    "minimumMemoryMB": 3072,
    "license": "Apache-2.0 + BSD-3-Clause-Clear + MIT",
    "licenseUrl": "https://huggingface.co/litert-community/kitten-tts-nano-0.8",
    "description": "A local English voice for phones with at least 3 GB of device memory. Short passages are prepared on this device; system narration remains available.",
    "files": [
      {
        "path": "kitten_predictor_fp16.tflite",
        "url": "https://huggingface.co/litert-community/kitten-tts-nano-0.8/resolve/d4662d891f9bf54b3d93432610d0d296d229e026/kitten_predictor_fp16.tflite",
        "sha256": "5f00a375b858be4a2ef655d1387cb6d172e0d4ca8ae6b93fdbad3cd49c0515e7",
        "bytes": 16990756
      },
      {
        "path": "kitten_prosody_fp16.tflite",
        "url": "https://huggingface.co/litert-community/kitten-tts-nano-0.8/resolve/d4662d891f9bf54b3d93432610d0d296d229e026/kitten_prosody_fp16.tflite",
        "sha256": "ea4501f8c0bcb617f85dc3703cd2dde5e1f49de175fcadaf05d6fccfdb080b6c",
        "bytes": 1697636
      },
      {
        "path": "kitten_vocoder_fp16.tflite",
        "url": "https://huggingface.co/litert-community/kitten-tts-nano-0.8/resolve/d4662d891f9bf54b3d93432610d0d296d229e026/kitten_vocoder_fp16.tflite",
        "sha256": "626497f75d550e70b82955a3f212417a3c1eebb9d05176716069c92bf41a712d",
        "bytes": 13367840
      },
      {
        "path": "voices.npz",
        "url": "https://huggingface.co/litert-community/kitten-tts-nano-0.8/resolve/d4662d891f9bf54b3d93432610d0d296d229e026/voices.npz",
        "sha256": "8aa7cee235abb0739cb51e6559685f65a4dacd95568833d05699b1633f519b3f",
        "bytes": 3278902
      },
      {
        "path": "dp_g2p_matcha_fp16.tflite",
        "url": "https://huggingface.co/litert-community/Matcha-TTS/resolve/8d650e794583c0b0869c87027c2f3a7c293902cb/dp_g2p_matcha_fp16.tflite",
        "sha256": "6e4b481f6874dfabc32ce73bf6f0ea1ba6ab5986ee6f76a27779364be8a53c73",
        "bytes": 25785872
      },
      {
        "path": "config.json",
        "url": "https://raw.githubusercontent.com/google-ai-edge/litert-samples/1a320c1c1bffe8126e9756ae47db1ad5fb014af6/samples/litert/text_to_speech_streaming/kotlin_cpu/android/app/src/main/assets/config.json",
        "sha256": "7363e9e4dda1613aebff7005f2e8c0c76d9b0a1cac31de0f3483bef3089c6906",
        "bytes": 2071
      },
      {
        "path": "g2p_dict.txt.gz",
        "url": "https://raw.githubusercontent.com/google-ai-edge/litert-samples/1a320c1c1bffe8126e9756ae47db1ad5fb014af6/samples/litert/text_to_speech_streaming/kotlin_cpu/android/app/src/main/assets/g2p_dict.txt.gz",
        "sha256": "5b3493a8cd4d20b72c7b91415afaf3f32335ebd81f349698e1cedc898c59f979",
        "bytes": 1762038
      },
      {
        "path": "g2p_meta.json",
        "url": "https://raw.githubusercontent.com/google-ai-edge/litert-samples/1a320c1c1bffe8126e9756ae47db1ad5fb014af6/samples/litert/text_to_speech_streaming/kotlin_cpu/android/app/src/main/assets/g2p_meta.json",
        "sha256": "7b87bfeaaa072be236e8491d771b0cb97cc92c3e5d83e3558fff8849868810f5",
        "bytes": 1904
      }
    ],
    "totalBytes": 62887019
  },
  {
    "id": "android-moonshine-english",
    "name": "English transcription",
    "capability": "asr",
    "platform": "android",
    "runtime": "litert",
    "version": "4d0fd016df00-g1",
    "languageTags": [
      "en"
    ],
    "minimumOS": 28,
    "minimumMemoryMB": 1024,
    "license": "MIT + Apache-2.0 synthetic validation sample",
    "licenseUrl": "https://huggingface.co/litert-community/moonshine-tiny",
    "description": "Compact local transcription for saved thoughts. Your recording remains available while transcription runs.",
    "files": [
      {
        "path": "moonshine_tiny_5s_i8.tflite",
        "url": "https://huggingface.co/litert-community/moonshine-tiny/resolve/4d0fd016df005c889aaa738b4765ede92736424f/moonshine_tiny_5s_i8.tflite",
        "sha256": "97abdeea122d579229091659c24c59d988c6419d453a200f6471241a53b9a9b9",
        "bytes": 51936896
      },
      {
        "path": "tokenizer.json",
        "url": "https://huggingface.co/UsefulSensors/moonshine-tiny/resolve/390624ed33d594443aa4aa221f5b9f283b545b5a/tokenizer.json",
        "sha256": "6579793438bc4fbafffacf699169ff53e3769c5a0a0f5e71cdee8853e8130deb",
        "bytes": 1985530
      },
      {
        "path": "golden.wav",
        "url": "https://huggingface.co/litert-community/kitten-tts-nano-0.8/resolve/d4662d891f9bf54b3d93432610d0d296d229e026/samples/litert_golden.wav",
        "sha256": "4fed2852ebbbb2aaf41c30f7ca1a5be1bf96f0f673f31650e94d1e8ce638bf15",
        "bytes": 218444
      }
    ],
    "totalBytes": 54140870
  },
  {
    "id": "whisper-tiny-en-coreml",
    "name": "English transcription",
    "capability": "asr",
    "platform": "ios",
    "runtime": "whisperkit",
    "version": "0f63a780-tokenizer87c710",
    "languageTags": [
      "en"
    ],
    "minimumOS": 17,
    "minimumMemoryMB": 2048,
    "license": "MIT",
    "licenseUrl": "https://github.com/openai/whisper/blob/main/LICENSE",
    "description": "Offline English transcription of saved notes. Compact CoreML model; quality varies with noise and accents.",
    "files": [
      {
        "path": "AudioEncoder.mlmodelc/analytics/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/analytics/coremldata.bin",
        "sha256": "eaaaa6671a96a359a0bbd5e97885246dcc17f7435b6ffad8d871bb940964500b",
        "bytes": 243
      },
      {
        "path": "AudioEncoder.mlmodelc/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/coremldata.bin",
        "sha256": "325b182d0a4266730a81795ae6b7a787b5111dd091500fc0c04dedf610015d46",
        "bytes": 347
      },
      {
        "path": "AudioEncoder.mlmodelc/metadata.json",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/metadata.json",
        "sha256": "c0468bfcd4c940e479812dc35db6c9bb380c1a9da1786471c0b916e1ee784ff0",
        "bytes": 1807
      },
      {
        "path": "AudioEncoder.mlmodelc/model.mil",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/model.mil",
        "sha256": "6423991df85b6811ef08a935c86ab4f0cc0a7a072fe24913f3bcfab2c5bebd84",
        "bytes": 308780
      },
      {
        "path": "AudioEncoder.mlmodelc/model.mlmodel",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/model.mlmodel",
        "sha256": "030d64a3ddd296d6f709691a66a870aab7ee9f19e5fe07e8086245fb85302802",
        "bytes": 54965
      },
      {
        "path": "AudioEncoder.mlmodelc/weights/weight.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/AudioEncoder.mlmodelc/weights/weight.bin",
        "sha256": "f3706dac8d9d4bec269d3cee10fa4eda39b4240a46091c8323c1731a8c6d59c2",
        "bytes": 16422784
      },
      {
        "path": "MelSpectrogram.mlmodelc/analytics/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/MelSpectrogram.mlmodelc/analytics/coremldata.bin",
        "sha256": "160d9737169d22dc01a899e1c6a0a9c44d0637d41f0dedb2a0b7c1422c4035d2",
        "bytes": 243
      },
      {
        "path": "MelSpectrogram.mlmodelc/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/MelSpectrogram.mlmodelc/coremldata.bin",
        "sha256": "cb3b3f51b080f58b12a6888a5e8ad57419be9e4c6843b96a7577f171b300e660",
        "bytes": 328
      },
      {
        "path": "MelSpectrogram.mlmodelc/metadata.json",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/MelSpectrogram.mlmodelc/metadata.json",
        "sha256": "d92e196c14b4d03167a8ded97d9f925617ff302942588aa460257eb0c58de35b",
        "bytes": 1848
      },
      {
        "path": "MelSpectrogram.mlmodelc/model.mil",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/MelSpectrogram.mlmodelc/model.mil",
        "sha256": "5fe83a96c41965219d39734aa3248bd4956d2427705539fba2d406aa4eeb4862",
        "bytes": 10132
      },
      {
        "path": "MelSpectrogram.mlmodelc/weights/weight.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/MelSpectrogram.mlmodelc/weights/weight.bin",
        "sha256": "801024dbc7a89c677be1f8b285de3409e35f7d1786c9c8d9d0d6842ac57a1c83",
        "bytes": 354080
      },
      {
        "path": "TextDecoder.mlmodelc/analytics/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/analytics/coremldata.bin",
        "sha256": "edb99a30ccee8e157fbec80dc3dce49349ba0982391b327d753e10ccab0a01c3",
        "bytes": 243
      },
      {
        "path": "TextDecoder.mlmodelc/coremldata.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/coremldata.bin",
        "sha256": "65c043a081845d190918b4c7d244f94a55df1a15fae796abedc1f414995542c6",
        "bytes": 633
      },
      {
        "path": "TextDecoder.mlmodelc/metadata.json",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/metadata.json",
        "sha256": "41dccfd0fe75a8f333abf74a1b0ab5ca007a589e5cb50fa34ec6033936763b58",
        "bytes": 4754
      },
      {
        "path": "TextDecoder.mlmodelc/model.mil",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/model.mil",
        "sha256": "0faaeee40f86f6e4bc69b81c7142bc9ca8af7859a597ee0a22eb3df8ad30ab65",
        "bytes": 135272
      },
      {
        "path": "TextDecoder.mlmodelc/model.mlmodel",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/model.mlmodel",
        "sha256": "5c3e91bc036014426708e2ceb0e35cb1bbbf34e8121d2070d2b174a7957581d0",
        "bytes": 108558
      },
      {
        "path": "TextDecoder.mlmodelc/weights/weight.bin",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/TextDecoder.mlmodelc/weights/weight.bin",
        "sha256": "763f915f0126093fc2c506572b3ab0fad134c04cfc2221333ccc7d73552c9252",
        "bytes": 59215664
      },
      {
        "path": "config.json",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/config.json",
        "sha256": "4d7b75a6f18d3cd9a4f936cdc16d9e5d855c0acbca21d6a11fbac602a422b6a8",
        "bytes": 1454
      },
      {
        "path": "generation_config.json",
        "url": "https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-tiny.en/generation_config.json",
        "sha256": "aea4069565b5adaec331005991a1d8613192687aa4cedf2e9dd91b126991df5a",
        "bytes": 1006
      },
      {
        "path": "tokenizer.json",
        "url": "https://huggingface.co/openai/whisper-tiny.en/resolve/87c7102498dcde7456f24cfd30239ca606ed9063/tokenizer.json",
        "sha256": "5eb60cec1e77aeeb6869a2bb5a8e01a84c3fe5d072d75369343021fe6f5310d0",
        "bytes": 2405679
      },
      {
        "path": "tokenizer_config.json",
        "url": "https://huggingface.co/openai/whisper-tiny.en/resolve/87c7102498dcde7456f24cfd30239ca606ed9063/tokenizer_config.json",
        "sha256": "14f84bdf4b9ecdbd4738ddc81c17a1baedfc02bb93c6e049c951e15a1b40b70d",
        "bytes": 805
      },
      {
        "path": "special_tokens_map.json",
        "url": "https://huggingface.co/openai/whisper-tiny.en/resolve/87c7102498dcde7456f24cfd30239ca606ed9063/special_tokens_map.json",
        "sha256": "014f8f802366ed818919550be0ad9e35907327cb9e142e8aaa102420f460bda8",
        "bytes": 1830
      },
      {
        "path": "added_tokens.json",
        "url": "https://huggingface.co/openai/whisper-tiny.en/resolve/87c7102498dcde7456f24cfd30239ca606ed9063/added_tokens.json",
        "sha256": "560be47bea388757f8d4cc185c5d82067426cbb6361e38016dd90ddc01ab203a",
        "bytes": 34604
      }
    ],
    "totalBytes": 79066059
  },
  {
    "id": "kokoro-english-coreml",
    "name": "Enhanced English voices",
    "capability": "tts",
    "platform": "ios",
    "runtime": "kokoro-coreml-e2e-fp32",
    "version": "f8ff771e4cab0bb3368e8af3a090a7e847485401",
    "languageTags": [
      "en"
    ],
    "minimumOS": 18,
    "minimumMemoryMB": 3072,
    "license": "Apache-2.0",
    "licenseUrl": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/blob/f8ff771e4cab0bb3368e8af3a090a7e847485401/README.md",
    "description": "Three English voices you can download for offline listening. Optional preview; built-in voices work without this download.",
    "files": [
      {
        "path": "kokoro_5s.mlmodelc/analytics/coremldata.bin",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/kokoro_5s.mlmodelc/analytics/coremldata.bin",
        "bytes": 243,
        "sha256": "4ab1ce05ed4689825180da77f37ff86d7bcee01877732c6426c5fc8834fbc798"
      },
      {
        "path": "kokoro_5s.mlmodelc/coremldata.bin",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/kokoro_5s.mlmodelc/coremldata.bin",
        "bytes": 478,
        "sha256": "c177e635173de37545490ea0b431274816491e77bb187ac197ab63ad6a9b902d"
      },
      {
        "path": "kokoro_5s.mlmodelc/metadata.json",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/kokoro_5s.mlmodelc/metadata.json",
        "bytes": 4586,
        "sha256": "23876f10def2950b7e127a7b769bf18ed4c7e4a2456237962c852d1e24caf568"
      },
      {
        "path": "kokoro_5s.mlmodelc/model.mil",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/kokoro_5s.mlmodelc/model.mil",
        "bytes": 804060,
        "sha256": "b91217bd39429f72bbb049c564506d099ed3373a92d3a90249cf8ccb064984da"
      },
      {
        "path": "vocab_index.json",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/vocab_index.json",
        "bytes": 2034,
        "sha256": "433690ab1da1256639c00179e7961a9e9f50ec91077a9c0877107c14e04fb989"
      },
      {
        "path": "voices/af_heart.json",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/voices/af_heart.json",
        "bytes": 5517,
        "sha256": "c00984469c315ee8709bebb8ea0bd8321eae2d952f6db7c6214d660028d01ed2"
      },
      {
        "path": "kokoro_5s.mlmodelc/weights/weight.bin",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/kokoro_5s.mlmodelc/weights/weight.bin",
        "bytes": 324488320,
        "sha256": "48b6d7301be895e25d80a6024b71ba023d1baa9ec71e26a66cc9e32f78f83a86"
      },
      {
        "path": "voices/af_bella.json",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/voices/af_bella.json",
        "bytes": 5505,
        "sha256": "3fee6c5728f669f8b3bbca31cca30ebfee6fb00723f5fce3f2c7d47bcaa14a36"
      },
      {
        "path": "voices/am_michael.json",
        "url": "https://huggingface.co/aufklarer/Kokoro-82M-CoreML/resolve/f8ff771e4cab0bb3368e8af3a090a7e847485401/voices/am_michael.json",
        "bytes": 5477,
        "sha256": "df8ba32be906749caee1be1d26ad5f913bae14fdaefbda03da17fe50da3f4827"
      }
    ],
    "totalBytes": 325316220
  },
  {
    "id": "android-phonon-english",
    "name": "Phonon • English voice",
    "capability": "tts",
    "platform": "android",
    "runtime": "phonon-cpu-q8",
    "version": "0.4.0-eos1-52433c46975b",
    "languageTags": [
      "en"
    ],
    "minimumOS": 28,
    "minimumMemoryMB": 3072,
    "license": "Apache-2.0",
    "licenseUrl": "https://huggingface.co/gradium/phonon-31m-en/blob/52433c46975bfa13144c56204c9f4ee1c5b148aa/README.md",
    "description": "An optional local English voice for 64-bit devices with at least 3 GB of memory. Device checking is required before selection; sustained physical-device qualification remains pending.",
    "files": [
      {
        "path": "config.json",
        "bytes": 2851,
        "sha256": "7062ea376e6b158543c8abec8e5af67df171c49ad70eb7e2a0fb25c16f265e06",
        "url": "https://huggingface.co/gradium/phonon-31m-en/resolve/52433c46975bfa13144c56204c9f4ee1c5b148aa/config.json"
      },
      {
        "path": "default-voice.safetensors",
        "bytes": 256136,
        "sha256": "f739e8c4c76ee7f92e7c94a82002748c8cd22e99582ac49f7b9b94cd63786e18",
        "url": "https://huggingface.co/gradium/phonon-31m-en/resolve/52433c46975bfa13144c56204c9f4ee1c5b148aa/default-voice.safetensors"
      },
      {
        "path": "model.q8.gguf",
        "bytes": 64512352,
        "sha256": "a7d9aad2cd6dc66473f24a08bc6d2939030ef4d751ef82349361fbef3272dbae",
        "url": "https://huggingface.co/gradium/phonon-31m-en/resolve/52433c46975bfa13144c56204c9f4ee1c5b148aa/model.q8.gguf"
      },
      {
        "path": "tokenizer.json",
        "bytes": 277967,
        "sha256": "691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936",
        "url": "https://huggingface.co/gradium/phonon-31m-en/resolve/52433c46975bfa13144c56204c9f4ee1c5b148aa/tokenizer.json"
      }
    ],
    "totalBytes": 65049306
  },
  {
    "id": "phonon-english-coreml",
    "name": "Phonon • English voice",
    "capability": "tts",
    "platform": "ios",
    "runtime": "phonon-coreml",
    "version": "0.4.0-eos1",
    "languageTags": [
      "en"
    ],
    "minimumOS": 18,
    "minimumMemoryMB": 3072,
    "license": "Apache-2.0",
    "licenseUrl": "https://huggingface.co/gradium/phonon-31m-en/blob/52433c46975bfa13144c56204c9f4ee1c5b148aa/README.md",
    "description": "An optional local English voice for iOS 18 or newer. First use prepares the voice on this device; sustained physical-device qualification remains pending.",
    "files": [
      {
        "path": "phonon-31m-en-coreml.zip",
        "url": "https://github.com/gradium-ai/xn-ptts/releases/download/v0.4.0/phonon-31m-en-coreml.zip",
        "sha256": "10f3a99fd39256db44f826267ef21d78cca959b16c0554926344c09fde27b436",
        "bytes": 137096081
      }
    ],
    "totalBytes": 137096081
  }
]
    """.trimIndent())
}
