# Small offline TTS alternatives for Genie

Status checked October 28, 2026.

## Strongest new candidate: Inflect v2

Inflect is a more compelling size/quality option than the Kitten models if one fixed English voice is acceptable. The model author publishes two verified, unquantized ONNX graphs and reports complete model assets of about **37.9 MB for Micro** (9.36M parameters) and **16.3 MB for Nano** (3.97M parameters). Those totals include both duration and waveform-decoder graphs; there is no separate voice pack. Both produce 24 kHz mono speech, are Apache 2.0 licensed, and use a fixed male English voice. [Inflect Micro ONNX card](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX), [Inflect Nano ONNX card](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX), [Inflect model card and evaluation](https://huggingface.co/owensong/Inflect-Micro-v2)

The ONNX sizes are much lower than the parameter counts alone might suggest: Micro's 7.32 MB duration graph plus 30.43 MB decoder graph total 37.75 MB; Nano's 3.64 MB plus 12.57 MB total 16.21 MB. The model author's CPU evaluation reports 6.28× real time for Micro and 10.72× for Nano on a four-thread server CPU; do not read these as Titan 2 Elite results. [Micro graph sizes](https://huggingface.co/owensong/Inflect-Micro-v2-ONNX), [Nano graph sizes](https://huggingface.co/owensong/Inflect-Nano-v2-ONNX), [benchmark conditions](https://huggingface.co/owensong/Inflect-Micro-v2)

### Android path and caveats

[TensorSpeak](https://github.com/AljGe/TensorSpeak) is a third-party Android system TTS engine that runs the official Inflect ONNX graphs through Kotlin, ONNX Runtime, and eSpeak-ng. Its current release lists separate Micro and Nano model packs of about 38 MB and 16 MB, downloaded on demand rather than bundled in the APK. This is concrete Android evidence, but it is an independent implementation, not an Inflect or Genie library. The app's code is GPL-3.0-or-later; the model weights are Apache 2.0. Review licensing before reusing its code. [TensorSpeak Android implementation](https://github.com/AljGe/TensorSpeak), [TensorSpeak release assets](https://github.com/AljGe/TensorSpeak/releases)

For Genie, the practical recommendation is to evaluate **Inflect Micro** first for its stated quality focus, then **Inflect Nano** if a 16 MB model download matters more. Both have one fixed voice and are English-only; unfamiliar names and numbers may still need listening checks. TensorSpeak has on-device benchmarks, but they are not Titan 2 Elite measurements. [Inflect model limitations](https://huggingface.co/owensong/Inflect-Nano-v2), [TensorSpeak benchmark notes](https://github.com/AljGe/TensorSpeak/blob/master/docs/LATENCY.md)

## Pocket TTS: tiny models, less direct Android support

Kyutai's October 2026 Pocket TTS release lists English variants at **18.2M, 6.9M, and 1.9M parameters**. The release gives parameter counts, but I did not find an authoritative per-variant Android package size. Kyutai's main implementation is Python/PyTorch; its repository also lists a community LiteRT/Kotlin Android implementation, but does not verify that it supports these newest variants. Sherpa-onnx has a Kotlin API for Pocket TTS, but documents a separate model that needs reference audio. Treat the small Pocket models as a promising follow-up, not a confirmed drop-in choice for Genie. [Kyutai Pocket TTS v4 releases](https://github.com/kyutai-labs/pocket-tts/releases), [Pocket TTS project and Android alternatives](https://github.com/kyutai-labs/pocket-tts), [sherpa-onnx Pocket integration](https://k2-fsa.github.io/sherpa/onnx/tts/pocket.html)
