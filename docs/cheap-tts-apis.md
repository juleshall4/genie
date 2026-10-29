# Low-cost text-to-speech APIs for Genie

**Reviewed:** 2026-10-29

## Recommendation

Try Google Chirp 3 HD within its monthly free allowance, or DeepInfra-hosted Kokoro for low usage costs beyond free allowances. FastInfra publishes lower prices, but its service quality and reliability have not been tested for Genie. This research changes no app code.

## Provider comparison

All prices below are USD, checked on 2026-10-29. Estimates cover speech synthesis only, excluding taxes, backend hosting, and any required prepaid deposit. Provider payment minimums and credit expiry were not established in this review. Prices are usage costs, not necessarily the amount charged at signup.

| Provider and model | Published character pricing | 100 answers/day, 300 characters each | 200 answers/day, 500 characters each |
| --- | --- | --- | --- |
| FastInfra Kokoro | $0.30/M | $0.27/month | $0.90/month |
| FastInfra Chatterbox Multilingual | $0.50/M | $0.45/month | $1.50/month |
| DeepInfra Kokoro | $0.99/M, default tier | $0.89/month | $2.97/month |
| Together Kokoro | $4/M | $3.60/month | $12/month |
| Google WaveNet | First 4M/month free, then $4/M | $0/month | $0/month |
| Google Chirp 3 HD | First 1M/month free, then $30/M | $0/month | $60/month |

Calculations use 30 days: 900,000 and 3,000,000 input characters respectively. Google estimates assume the account's monthly allowance remains available. Google requires billing to be enabled and automatically bills excess usage. The monthly allowances are separate from trial credits. [Google pricing](https://cloud.google.com/text-to-speech/pricing) · [DeepInfra Kokoro](https://deepinfra.com/hexgrad/Kokoro-82M) · [Together pricing](https://www.together.ai/pricing) · [FastInfra documentation](https://cpu.fastinfra.ai/docs)

### Quality and latency considerations

- **DeepInfra Kokoro:** Runs the same Kokoro model considered for local use, with streaming output and selectable voices. Hosting removes synthesis work from the phone, but adds network time. The quoted price is the default tier; priority costs extra. Do not use the stale $0.62/M comparison quoted on FastInfra's page: DeepInfra's own current page says $0.99/M. [Model endpoint](https://deepinfra.com/hexgrad/Kokoro-82M)
- **Google Chirp 3 HD:** A strong candidate for a listening test, with British English voices and streaming synthesis. At 300 characters per answer, 1M free characters covers about 111 answers a day over 30 days. Its overage is expensive relative to Kokoro. [Voice and streaming documentation](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd)
- **Google WaveNet:** An older voice family with a much larger free allowance. Google lists streaming for Chirp 3 HD, not WaveNet; compare perceived voice quality and first-audio delay before choosing it for Genie. [Voice types](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types)
- **FastInfra:** Publishes Kokoro and Chatterbox rates with no per-request fee. Its under-one-second first-audio claim is a vendor claim, not a measured Genie result. Docs mention prepaid billing, but do not clearly establish an audio-specific minimum deposit. Treat it as a low-cost trial candidate rather than a verified quality winner. [API documentation](https://cpu.fastinfra.ai/docs)

No provider audio was generated or benchmarked in this research. A useful next comparison is the same short answer played through British Kokoro, Chirp 3 HD, and WaveNet, recording time to first audio and listening quality.

## OpenAI assessment

For a short-term OpenAI trial, send Genie’s existing answer directly to the Speech API’s `input` field with `gpt-4o-mini-tts`. This is a dedicated text-to-speech request, so Luna can keep generating the answer at its current low setting; Genie does not need another text-answer request. The Speech API models are scheduled to shut down on **2027-01-06**, however, so avoid building a long-term integration around them. OpenAI’s named replacement is `gpt-realtime-2.1-mini` through the Realtime API, which uses a different, stateful workflow and is not a drop-in `/audio/speech` model. [Text-to-speech guide](https://developers.openai.com/api/docs/guides/text-to-speech) · [Deprecation notice](https://developers.openai.com/api/docs/deprecations)

## OpenAI pricing

| Model | Current route and price | Cost estimate |
| --- | --- | --- |
| `tts-1` | `/v1/audio/speech`; $15 per million input characters | $0.015 per 1,000 characters |
| `tts-1-hd` | `/v1/audio/speech`; $30 per million input characters | $0.030 per 1,000 characters |
| `gpt-4o-mini-tts` | `/v1/audio/speech`; $0.60 per million input text tokens plus $12 per million output audio tokens | About $0.0144 per minute of audio, plus a small text-input charge |
| `gpt-realtime-2.1-mini` | Realtime API; $0.60/M text input tokens, $2.40/M text output tokens, and $20/M audio output tokens | About $0.024 per minute of generated audio, plus text-token charges |

The minute estimates use OpenAI’s guidance of about one assistant audio token per 50 ms (1,200 tokens/minute). Actual usage can vary slightly because token counts include special tokens. For a rough character comparison only, if 1,000 spoken characters take one minute, `gpt-4o-mini-tts` is about $14.40 per million characters in audio cost, before input text; `gpt-realtime-2.1-mini` is about $24/M characters, before text charges. These are estimates, not character-priced rates. Read the Realtime response’s usage data for actual billing. [Model pricing](https://developers.openai.com/api/docs/models/gpt-4o-mini-tts) · [TTS-1 pricing](https://developers.openai.com/api/docs/models/tts-1) · [TTS-1 HD pricing](https://developers.openai.com/api/docs/models/tts-1-hd) · [Audio token accounting](https://developers.openai.com/api/docs/guides/voice-latency-cost)

The Speech API gives the clearest “speak this supplied answer” workflow: it accepts a text string, voice, output format, and optional delivery instructions. The guide recommends `marin` or `cedar` for quality; voices are optimized for English. Realtime can accept a text message and return audio with its transcript, but it is a broader model call. The migration guide’s example instructs the model to read supplied text aloud without commentary; it does not promise exact word-for-word rendering. [Speech API reference](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create) · [Realtime custom-voice guide](https://developers.openai.com/api/docs/guides/custom-voices)

## ChatGPT plan access

Sign in with ChatGPT’s open-source flow documents OAuth access for eligible **Responses API** requests. It does not document the Speech API or Realtime API as part of that flow, so do not assume Genie can use the ChatGPT plan allowance for TTS. Treat TTS as API-platform usage with its own API credentials and billing. Eligible paid ChatGPT Pro and Business subscribers can separately claim API credits for an API organization; this is API credit, not SIWC allowance, and the current eligibility page does not list Free or Plus plans. [SIWC models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) · [ChatGPT-linked API credits](https://developers.openai.com/api/docs/guides/chatgpt-api-credits)

## Fit for Genie

OpenAI’s current Speech API is simple and has quality voices, but its two cheapest dedicated TTS paths are temporary because of the January 2027 shutdown. The named Realtime replacement costs about 2.4 cents per spoken minute before text tokens and needs a Realtime session; it is less straightforward than sending the answer to a dedicated TTS endpoint. For a permanent, low-cost Genie setting, compare provider prices and listen to the same Luna answers in a blind voice test before choosing. Keep the answer-generation request and speech request separate so changing TTS does not change Genie’s response text.
