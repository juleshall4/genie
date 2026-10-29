# AssemblyAI options for Genie

**Reviewed:** 2026-10-29

## Recommendation

AssemblyAI TTS-1 is a practical cloud voice option for Genie: it accepts text incrementally, supports British English voices, charges by the text sent, and has a documented short-lived token flow for mobile clients. It requires a small trusted backend to mint those tokens. It does not require putting the paid API key in the Android app.

## TTS-1

AssemblyAI lists TTS-1 at **$15 per million input characters**. Billing counts Unicode code points in the text sent, including spaces and punctuation; a WebSocket cancellation stops further audio but does not erase text already passed to synthesis from billing. The vendor says there are no voice, concurrency, or PAYG minimum fees. [Pricing](https://www.assemblyai.com/pricing/) · [TTS launch and pricing details](https://www.assemblyai.com/blog/text-to-speech-api) · [TTS billing and limits](https://www.assemblyai.com/docs/tts/api-reference/http-api/overview) · [WebSocket frames](https://www.assemblyai.com/docs/tts/message-frames)

TTS supports six languages. Its current [voice catalog](https://www.assemblyai.com/docs/tts/voices) includes British English voices such as `aai-carrie`, `aai-cloyd`, `aai-nancy`, and `aai-oliver`; the dashboard and Playground provide the live catalog and samples.

TTS-1 streams over WebSocket, so Genie can send completed LLM sentences while the answer is still being generated. `Cancel` stops the active synthesis, but a short tail of audio may still arrive; stop playback and discard late frames in the client as well. [Streaming TTS guide](https://www.assemblyai.com/blog/text-to-speech-python)

### Latency

The pricing page says **about 90 ms to first audio**. AssemblyAI’s launch benchmark reports a 47 ms median time to first byte, measured from one client against each provider’s streaming API with one request at a time, 30 prompts, three rounds, and four stock voices per model. These are provider-side first-byte results, not a guarantee of end-to-end time from Genie’s microphone to audible playback. A voice not recently used may take longer to load; opening the TTS session before the answer is ready reduces that delay. [Benchmark methodology](https://www.assemblyai.com/blog/text-to-speech-api) · [TTS latency guidance](https://www.assemblyai.com/docs/tts/getting-started/optimizing-accuracy-and-latency)

## Protect the AssemblyAI key

The TTS documentation explicitly says not to ship the long-lived API key in an app. A trusted server can mint a TTS-only temporary token using `product=tts` and a short `expires_in_seconds` window. The Android client then sends that token in the WebSocket `Authorization` header. Usage remains charged to the key that minted it, and the temporary token cannot mint another token. Mint a fresh token for each connection; avoid passing it in a URL when the client can set headers, because URLs can be logged. [Temporary TTS tokens](https://www.assemblyai.com/docs/tts/authenticate-with-a-temporary-token) · [WebSocket authentication](https://www.assemblyai.com/docs/tts/connect-parameters)

The TTS API has US and EU data-zone endpoints. TTS text and audio are processed in the selected zone, and the pricing page lists US and EU processing at the same price. Account and usage data remain in the US; support staff outside the selected region may access content for troubleshooting. The general retention page documents zero-retention controls for STT products, but does not specify equivalent retention or model-training terms for TTS. Treat TTS retention as unconfirmed until AssemblyAI confirms the applicable terms. [TTS regions](https://www.assemblyai.com/docs/tts/endpoints-and-data-zones) · [Data retention and training](https://www.assemblyai.com/docs/data-retention-and-model-training)

## Cost examples

Assume 30 days per month, the listed average answer length is the actual text sent to TTS, and realtime STT opens one session per question. STT figures use the $0.15/hour Universal-Streaming rate and assume each session stays open 5 seconds longer than the audio; a session is billed for its full open duration, including silence. LLM cost is not included.

| Usage | TTS characters/month | TTS | Realtime STT sessions/month | STT | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| 100 questions/day, 10 seconds of audio, 300-character answer; 15-second session | 900,000 | $13.50 | 12.5 hours | $1.88 | **$15.38/month** |
| 200 questions/day, 15 seconds of audio, 500-character answer; 20-second session | 3,000,000 | $45.00 | 33.3 hours | $5.00 | **$50.00/month** |

This shows why STT metering matters: pre-recorded and Sync STT are billed on submitted audio duration, while realtime STT is billed on WebSocket session duration. If Genie uses Sync instead, the STT portions above would be about $2.50 and $7.50 per month at $0.30/hour. TTS-only cost is unchanged. [Billing rules](https://www.assemblyai.com/docs/billing-and-pricing)

## Other AssemblyAI pricing categories

The [current pricing page](https://www.assemblyai.com/pricing/) lists these separate meters:

| Product | Public pay-as-you-go price |
| --- | --- |
| Pre-recorded STT | Universal-3.5 Pro $0.21/hour; Universal-2 $0.15/hour |
| Realtime STT | Universal-Streaming $0.15/hour; Universal-3.6 Pro Realtime $0.45/hour |
| Sync STT | $0.30/hour |
| Dictation | $0.62/hour |
| Speech Understanding and Guardrails | Per-feature audio-hour rates; see current price table |
| LLM Gateway | Per-model input and output token rates; regional endpoints add 10% |
| Text to Speech | $15 per million input characters |
| Self Hosted and Voice AI Cloud | Separate inference products; no directly comparable public unit rate is listed in the pricing table |

AssemblyAI’s **$50 new-account credits** explicitly cover Pre-recorded STT, Realtime STT, Speech Understanding, and Guardrails. LLM Gateway is excluded. TTS, Sync STT, and Dictation are not named in the current free-credit terms, so do not assume they are covered. [Billing and free-credit terms](https://www.assemblyai.com/docs/billing-and-pricing)

## Fit for Genie's interaction

### Voice input

- **Universal-Streaming:** the low-cost candidate for live English partial transcripts. Its multilingual sibling covers six languages at the same base rate. Benchmark both against Android's installed recognition service before claiming an improvement.
- **Universal-3.6 Pro Realtime:** the accuracy-focused candidate with 32 languages and entity-aware turn detection. Its `min_latency` mode checks for a turn end after 128 ms of silence, with a 640 ms fallback threshold. These thresholds are not the total transcription latency. It can avoid waiting through a long fixed silence interval, but an aggressive mode may split a thought if the user pauses. [Turn detection](https://www.assemblyai.com/docs/streaming/turn-detection)
- **Sync:** useful for one-shot push-to-talk. Audio can upload while the user speaks, with one finished transcript after recording ends; it does not supply live partial text. Live uploads accept up to 120 seconds; current docs also allow finished recordings up to 20 minutes, despite the pricing page's shorter description. Genie would need its own recording-end decision. [Sync live upload](https://www.assemblyai.com/docs/sync-stt/getting-started/transcribe-live-audio)
- **Dictation:** useful for a future keyboard-dictation feature, because it returns a cleaned version plus the verbatim transcript. The additional rewrite is not necessary to answer a spoken question and can change how the question is phrased. [Dictation](https://www.assemblyai.com/docs/dictation)
- **Pre-recorded:** suitable for future voice-note or recording transcription. The asynchronous job flow is a poor match for the current quick popup interaction. [API overview](https://www.assemblyai.com/docs)

### Optional extras

Keyterms prompting could improve names such as “Unihertz” or “Genie.” Voice Focus can suppress other speakers and background noise on Pro Realtime; it is not authentication of the device owner. Speaker diarization, speaker identification, sentiment, topics, key phrases, summaries, translation, and custom formatting are mainly useful for future recording, meeting, or translation features. Medical Mode is unnecessary for the current general assistant. Profanity filtering, PII text/audio redaction, and content moderation are optional transcript-processing controls; they do not speed up answering. [Product and add-on rates](https://www.assemblyai.com/pricing)

### LLM Gateway

The Gateway can generate Genie's answer as a standalone text-to-text service, authenticated and billed through AssemblyAI. It replaces the current inference connection rather than spending the user's ChatGPT-plan allowance. It does not provide existing ChatGPT chats or memory. The current quickstart is text-focused, so photo questions should retain a separately verified vision-capable route. [Gateway quickstart](https://www.assemblyai.com/docs/llm-gateway/quickstart)

The pricing page lists GPT-6 Luna at $0.10 per million input tokens and $0.50 per million output tokens under global routing. For 3,000 monthly requests budgeted at 1,000 billed input and 1,000 billed output tokens each, that is **$1.80/month**, or $1.98 with a 10% regional surcharge. This is an illustrative token budget, not a measured average: conversation history, actual reasoning/output usage, and the chosen model affect the bill. STT and TTS are additional. [Rates](https://www.assemblyai.com/pricing) · [Gateway billing](https://www.assemblyai.com/docs/billing-and-pricing)

AssemblyAI's changelog documents `reasoning: {effort: "low"}` and higher efforts, but the current API reference does not yet list that field. Its API reference currently states SSE streaming is supported on OpenAI models only. Validate the chosen model's accepted reasoning controls and streaming behavior before migrating; preserve the user's Low minimum. There is no evidence here that the Gateway will beat Genie's existing warmed Responses WebSocket. [Changelog](https://www.assemblyai.com/changelog) · [Chat completion schema](https://www.assemblyai.com/docs/llm-gateway/api-reference/create-chat-completion)

### Hosting and the Titan

AssemblyAI Cloud runs the models on AssemblyAI's servers. Self-hosting means containerized infrastructure with supported GPU configurations and usage charges; it is not a downloadable model supported on the Titan's mobile GPU. The public self-hosting page describes Realtime and Sync STT, not an Android runtime. [Self-hosting](https://www.assemblyai.com/deployments/self-hosted)

For a cost-conscious first experiment, benchmark cloud STT against native Android recognition while keeping ChatGPT/Luna with Low reasoning and local Inflect Nano. Cloud TTS is an optional quality/voice-choice upgrade. Sending completed sentences to TTS before the whole answer finishes can improve perceived response time, and that strategy can also be applied to local Inflect rather than requiring a cloud provider. No provider was integrated or benchmarked during this research.
