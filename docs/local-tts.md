# Local text-to-speech for Genie

Status checked October 28, 2026.

## Recommendation

Start with **KittenTTS Micro 0.8** using sherpa-onnx's Kotlin API. Its model and voice files total about 44.7 MB (41.4 MB model + 3.28 MB voices). It has eight English voices, produces 24 kHz audio, and the model is Apache 2.0 licensed. KittenML describes Micro as the better-quality choice over Nano int8 when size is less important. [Kitten files](https://huggingface.co/KittenML/kitten-tts-micro-0.8/tree/main), [model comparison](https://github.com/KittenML/KittenTTS-web), [sherpa-onnx Kotlin example](https://k2-fsa.github.io/sherpa/onnx/tts/all/English/kitten-micro-en-v0_8.html)

For the smallest download, **Kitten Nano 0.8 int8** is about 27.7 MB (24.4 MB model + 3.28 MB voices), with the same voice count and sample rate. Try it against Micro with typical Genie answers before choosing the default voice. [Nano model files](https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8/tree/main), [primary release README](https://github.com/KittenML/KittenTTS/blob/0.8/README.md), [sherpa-onnx Nano Android/Kotlin example](https://k2-fsa.github.io/sherpa/onnx/tts/all/English/kitten-nano-en-v0_8-int8.html)

Use an explicit **0.8** model ID. KittenML also offers KittenTTS 2, with 954 MB default weights or a smaller 492 MB option; neither fits this size goal. [KittenTTS model list](https://github.com/KittenML/KittenTTS)

## Other candidates

- **Kokoro 82M:** the 8-bit ONNX model alone is 92.4 MB, before voice files. It is Apache 2.0 licensed and sherpa-onnx supports it, including a multilingual package with many speakers. Consider it if voice choice matters more than download size. [Kokoro model card](https://huggingface.co/hexgrad/Kokoro-82M), [quantized model file](https://huggingface.co/onnx-community/Kokoro-82M-ONNX/blob/main/onnx/model_quantized.onnx), [sherpa-onnx models](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html)
- **Piper:** a flexible voice catalog rather than one model size. Each voice needs an ONNX file and JSON config, and voice licenses vary. [Piper voice catalog](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/VOICES.md)
- **Pocket TTS:** Kyutai released English models from 1.9M to 18.2M parameters in October 2026. This is promising, but sherpa-onnx's documented Kotlin integration uses a different Pocket model and requires reference audio; I found no documented Android export for the new tiny variants. Revisit when that integration is available. [Kyutai Pocket TTS](https://github.com/kyutai-labs/pocket-tts), [sherpa-onnx Pocket TTS](https://k2-fsa.github.io/sherpa/onnx/tts/pocket.html)

## Android footprint

sherpa-onnx provides Kotlin APIs and Android examples for Kitten. The model files are not the full app cost: native runtime libraries and text-processing data add more. As a reference, its Nano int8 standalone Android TTS-engine APK is about 54.5 MB; this is not an estimate for embedding the library in Genie. [sherpa-onnx Kotlin API](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/Tts.kt), [Nano APK metadata](https://huggingface.co/api/models/csukuangfj2/sherpa-onnx-apk/tree/main/tts-engine-new/2.0.1?recursive=false&limit=1000)

No Titan 2 Elite latency or peak-memory benchmark was available. Measure first-speech delay and listen for names, numbers, punctuation, and longer answers on the phone before shipping a default voice.
