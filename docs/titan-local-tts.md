# Local TTS shortlist for the Titan 2 Elite

Status checked October 28, 2026.

## Recommendation

Shortlist **Inflect Micro v2 and Nano v2, FP32**, alongside Kitten Micro and Nano. If speech quality is the priority, audition Inflect Micro first: its author positions it as the quality-focused option under 10M parameters. It is English-only with one fixed voice. A third-party Android implementation has measured its first-audio latency on a Pixel 9a: median warm time to first audio was **566 ms for a short phrase**, **1,494 ms for a sentence**, and **2,462 ms for an expanded phrase**. These are first-chunk times, not full-response generation time. The available measurements do not establish that Inflect sounds better or runs faster than Kitten on a phone. [Inflect model card](https://huggingface.co/owensong/Inflect-Micro-v2), [TensorSpeak Pixel 9a measurements](https://github.com/AljGe/TensorSpeak/blob/master/docs/INT8_DECODE.md)

Keep **Inflect Nano v2, FP32** as the responsiveness and footprint candidate. In the same Pixel 9a test, its warm first-chunk medians were **251 ms**, **768 ms**, and **1,213 ms** for those three phrase types. Nano is the footprint-focused model. The tests ran on Pixel 9a with ONNX Runtime CPU and four threads; they are not Titan 2 Elite results. [TensorSpeak measurements](https://github.com/AljGe/TensorSpeak/blob/master/docs/INT8_DECODE.md), [TensorSpeak Android implementation](https://github.com/AljGe/TensorSpeak)

The standard Titan 2 Elite has a Dimensity 7400 and 12 GB RAM, but those specifications do not predict TTS latency. I found no published TensorSpeak benchmark for this handset. Compare first audio, full response time, and listening quality for Inflect and Kitten on the Titan before setting a default. [Unihertz Titan 2 Elite specifications](https://www.unihertz.com/products/titan-2-elite)

## Quantization caveat

TensorSpeak reports experimental INT8 decode results on Pixel 9a. Full QDQ INT8 reduced Micro's warm first-chunk medians to 197 ms, 571 ms, and 943 ms, but the authors mark the graphs experimental and require human listening sign-off before shipping. Nano's full QDQ INT8 failed its quality gate; a smaller `conv_only` quantization profile was a conditional pass, also awaiting listening sign-off. Do not use those results as the production recommendation yet. [Experimental INT8 results and acceptance notes](https://github.com/AljGe/TensorSpeak/blob/master/docs/INT8_DECODE.md)

## Comparison limits

Kitten 0.8 has a maintained Android/Kotlin path through sherpa-onnx, while Piper and Kokoro also have local runtimes. The primary model and runtime sources reviewed do not provide same-device Titan 2 Elite latency comparisons for these models. Model size, parameter count, and desktop CPU throughput are not enough to rank their Android processing cost. TensorSpeak's own server CPU figures use different runtimes and should not be compared as phone-speed results. [sherpa-onnx Kitten Android/Kotlin example](https://k2-fsa.github.io/sherpa/onnx/tts/all/English/kitten-micro-en-v0_8.html), [Inflect benchmark conditions](https://huggingface.co/owensong/Inflect-Micro-v2)
