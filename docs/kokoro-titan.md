# Kokoro-82M on Titan 2 Elite

**Reviewed:** 2026-10-29

## Assessment

Kokoro is a plausible offline TTS candidate for the Titan 2 Elite. Its 12 GB of RAM is ample for the published model bundles, but model file size is not a peak-memory estimate and there is no Titan-specific latency or battery measurement here. The main implementation choices are the maintained sherpa-onnx Android binding and a newer LiteRT-LM port. Do not treat their artifacts as interchangeable.

## Model and download size

The original [`hexgrad/Kokoro-82M`](https://huggingface.co/hexgrad/Kokoro-82M) card describes 82 million parameters and Apache-2.0 weights. The Android ONNX files are conversions published separately by [ONNX Community](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/tree/main/onnx): current v1.0 files are 86,033,585 bytes for `model_q8f16.onnx` and 92,361,116 bytes for `model_quantized.onnx`. The repository has 54 separate voice files, typically 522,240 bytes each (about 0.52 MB); one chosen voice adds about 0.5 MB, while downloading all voices adds about 28 MB. These are download sizes, excluding the runtime, tokenizer, lexicons, and text frontend. They do not predict RAM use.

The maintained sherpa package is a different export: its current Kokoro v1.1 int8 bundle is 114,203,914 bytes for `model.int8.onnx` and 53,790,720 bytes for `voices.bin`, plus lexicons and phonemizer data. The compressed archive is about 147 MB and contains 103 voices. Its layout and quantization are not the same as the ONNX Community `model_q8f16` or `model_quantized` files. [sherpa Kokoro packages](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/tts/pretrained_models/kokoro.rst) · [current sherpa package files](https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/tree/main)

## Android integration and voices

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) is actively maintained, supports Android ARM64 and Kotlin through a native JNI runtime, and documents Kokoro TTS configuration. Its current [Android TTS APK list](https://k2-fsa.github.io/sherpa/onnx/tts/apk.html) includes Kokoro int8 v1.1. That makes it a credible Android path; the published Kotlin sample and native library can be integrated into Genie without using Python. Follow the sherpa-specific model bundle and API rather than assuming every Hugging Face ONNX export works with it. [Android guide](https://k2-fsa.github.io/sherpa/onnx/android/index.html) · [TTS API](https://k2-fsa.github.io/sherpa/onnx/c-api/html/tts.html)

Kokoro includes British English. Voices include `bf_alice`, `bf_emma`, `bf_isabella`, `bf_lily`, `bm_daniel`, `bm_fable`, `bm_george`, and `bm_lewis`. The original model card links voice samples and descriptions; listen to those before choosing a default. [Voice list and samples](https://github.com/hexgrad/Kokoro-82M/blob/main/VOICES.md)

## Performance evidence and caveats

There is no Titan 2 Elite benchmark in the sources reviewed. The [LiteRT-LM Kokoro integration](https://huggingface.co/litert-community/Kokoro-82M) reports 2.2 seconds to synthesize 18.3 seconds of speech on a Galaxy S26 with GPU, versus 9.1 seconds on a Pixel 10 Pro with GPU. Those results show that runtime and phone model can change results substantially; they are not a Titan prediction. The integration also says its GPU file was tested with Adreno and PowerVR, and advises using its CPU bundle on unsupported GPUs. Its newer `.litertlm` bundle is a separate conversion from sherpa’s ONNX package.

Quantization reduces download size but does not guarantee a faster or smaller-memory run; benchmark the exact runtime, export, voice, and device. sherpa maintainers closed an Android/ARM int8 audio-corruption report as fixed by a quantizer change on 2026-10-26. Check that the chosen prebuilt int8 model was regenerated with that change; older released model archives may predate it. [Fix PR](https://github.com/k2-fsa/sherpa-onnx/pull/4087) · [Android/ARM report](https://github.com/k2-fsa/sherpa-onnx/issues/3754)

**Recommendation:** keep Kokoro on the shortlist. For a first Genie prototype, use the current LiteRT-LM bundle if its Android integration fits the app; otherwise use sherpa-onnx’s Kotlin/JNI path and verify the int8 artifact. Measure first-audio delay, sustained generation, peak process memory, and device temperature on the Titan before selecting a default voice or making a battery claim.

## New LiteRT-LM bundle

The current conversion card recommends `kokoro_int8_gpu_fp16.litertlm`, a **295 MB** self-contained bundle containing the acoustic model, vocoder, 54 voices, and language frontends. This is not equivalent to the 92 MB ONNX graph alone. A separate `kokoro_int8_fp16.litertlm` bundle supports CPU fallback. The older individual `.tflite` preview is now in `legacy/` and deprecated. [Current model card](https://huggingface.co/litert-community/Kokoro-82M)

For an 18.3-second English paragraph, the conversion maintainer reports these warm results (four CPU threads, mean of three timed runs after a warm-up):

| Device | Backend | Synthesis time | Peak process memory |
| --- | --- | ---: | ---: |
| Galaxy S26 | GPU | 2.2 seconds | 340–360 MB |
| Galaxy S26 | CPU | 4.8 seconds | 915–930 MB |
| Pixel 10 Pro | GPU | 9.1 seconds | 400–505 MB |
| Pixel 10 Pro | CPU | 17.0 seconds | 925–945 MB |

These measure whole-paragraph synthesis, not delay to the first playable sample. Loading the GPU engine separately takes a reported 1.2–3 seconds; initial kernel compilation can take longer. Keeping the engine ready during an interaction and producing short clauses is therefore relevant to Genie.

The standard Titan's Dimensity 7400 uses four Cortex-A78 cores up to 2.6 GHz, four Cortex-A55 cores, and a Mali-G615 MC2 GPU. The newer Kokoro port's GPU benchmarks cover Adreno and PowerVR, not Mali. LiteRT has Android OpenCL/OpenGL acceleration, but framework support alone does not establish that this particular model and Titan driver work well together. GPU compatibility, sound quality, and thermal behavior remain device checks. [MediaTek specifications](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7400) · [Unihertz specifications](https://www.unihertz.com/products/titan-2-elite) · [LiteRT GPU documentation](https://developers.google.com/edge/litert/next/gpu)
