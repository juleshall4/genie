# Genie

An Android assistant with a Space-triggered overlay, speech recognition, and answers through your connected ChatGPT account.

## Personality and model defaults

The **Personality** box in settings starts with Genie's original prompt:

> You are Genie, a helpful mobile assistant. Answer clearly and briefly in plain text. Avoid unnecessary formatting.

Edits save automatically on this installation and are sent as `instructions` on the next question over both the WebSocket and HTTP paths. **Reset personality** restores the original text. Space types normally while the box is focused; the global shortcut resumes when editing ends or settings closes.

Without an explicit saved model, Genie prefers **GPT-6 Luna** when available in the account's catalog. Without a saved reasoning choice, it prefers **Low** when the selected model supports it. If Luna is unavailable, the automatic selection uses the first available model; if Low is unavailable, the model's advertised default is used. Explicit saved selections remain unchanged.

Genie enables OpenAI web search with automatic tool choice, so the model can look up information when needed. Search responses offer a collapsed Sources button with clickable links, and a brief status indicates when a search runs. Citation markers are removed from spoken output. Search availability remains subject to the selected model and account policies. The current app still sends text only and does not yet expose photo input. It does not connect directly to Google Search. [Connection limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

### Answer formatting

Popup answers use Markwon to render Markdown as Android text spans during streaming, including **bold**, *italic*, headings, lists, and inline code. Reference cleanup runs before rendering; sources remain in the separate Sources panel. Speech uses its own plain-text cleanup and does not receive Markdown spans.

### Web search

The shared Responses request builder enables `web_search` with `tool_choice: "auto"` on both WebSocket and HTTP requests. Genie does not force a search for every question. The popup shows a searching status and keeps source links behind a compact **Sources** button below the answer. Tapping it expands clickable sources; tapping **Hide sources** collapses them. A new question clears the panel. Both structured URL annotations and Markdown source links are supported. Only HTTP(S) citation targets are accepted. URL-like link labels, bare web addresses, citation markers, numeric references, and trailing Sources/References sections are removed from speech. The streaming cleaner holds incomplete links and parenthetical references so fragments cannot leak into synthesis. Meaningful linked words remain part of the answer. Source lists are never sent to Chirp.

On October 29, 2026, a live check through the connected ChatGPT account observed a real web-search call, an incrementally delivered answer, and a clickable source. The check exercised the same request builder and transport selection as the popup. All 33 unit tests passed, including search request configuration, search-event/citation parsing, unsafe citation rejection, Markdown source compatibility, and speech-marker removal.

```sh
./gradlew installDebugAndroidTest -PgenieTestRunner=dev.genie.android.chatgpt.WebSearchCheck
python3 scripts/run_android_check.py dev.genie.android.chatgpt.WebSearchCheck -e live_chatgpt true
```

This live check uses the connected ChatGPT plan allowance. [OpenAI web-search guide](https://developers.openai.com/api/docs/guides/tools-web-search).

## Spoken answers with Chirp 3 HD

Genie reads answers with Google Cloud Chirp 3 HD. Settings include all 30 British Chirp voices and four American voices, speaking speeds from 0.25× to 2×, a preview, an on/off switch, and a monthly character meter. Speed and voice choices are saved and apply to the next spoken answer. Closing the popup or selecting **Listen again** cancels pending speech and stops playback. Silent and vibrate modes block speech requests, previews, and character reservations. Entering either mode cancels pending speech and playback; that answer does not resume automatically when sound is restored. TensorSpeak and Inflect Nano are no longer used.

### Set up your project and API key

1. Create or select your own [Google Cloud project](https://console.cloud.google.com/).
2. Enable billing and the [Cloud Text-to-Speech API](https://console.cloud.google.com/apis/library/texttospeech.googleapis.com).
3. Create an API key under **APIs & Services → Credentials**, and restrict its API access to **Cloud Text-to-Speech API**.
4. Copy `.env.example` to `.env` and set `GOOGLE_CLOUD_TTS_API_KEY`. This file is ignored by version control. Do not put a service-account JSON file in this field.
5. Run `./gradlew installDebug`, then open Genie and ask a question to check spoken answers. The install task copies the key over adb into private debug-app storage. Genie encrypts it with Android Keystore and deletes the temporary plaintext copy on opening. The key is never added to the APK. Alternatively, enter it directly with **Google Cloud API key**.

To provision a changed key without rebuilding, run `python3 scripts/provision_chirp.py /path/to/adb`, then reopen Genie. Key changes retain the existing usage count. Do not distribute your personal key in a public app; this setup is for your own device.

### Offline voice previews

**Preview voice · Offline** plays a packaged recording for the selected voice. All 34 samples work without an API key or internet connection and make no synthesis requests or usage reservations. The read-aloud switch does not prevent previewing voices; silent and vibrate modes still block playback. Leaving settings or selecting another voice/speed stops the current preview.

The recordings use mono Ogg Opus, 24 kHz, with a 12 kbps variable-bitrate target: 316,336 bytes total (about 309 KiB), or 8–11 KB each. Android adjusts playback speed locally while preserving pitch. This approximates the selected speech speed; it is not a new cloud synthesis at that pace. Samples were generated once during development using 3,638 input characters. Their text, voice IDs, and encoding are recorded in `app/src/main/assets/voice_samples/manifest.json`. App previews never repeat that generation.

### Usage meter and limit

The meter counts characters submitted from this installation, excluding offline voice previews, and stops new speech requests at **900,000 characters per UTC calendar month**. Text answers continue after the limit. Reservations are saved before network submission and are not refunded after errors or cancellation because Google may have processed those requests. The counter uses Unicode code points, including spaces and punctuation, after removing Markdown. It resets automatically for a new UTC month.

This is a conservative on-device estimate, not a Google billing meter or a guarantee of free usage. Other apps and projects, clearing app data or reinstalling, and Google's billing boundaries can affect actual totals. Check the Cloud console for authoritative usage. Google's published Chirp 3 HD allowance is 1 million characters per month, followed by $30 per million; a billing-enabled project is required. [Pricing](https://cloud.google.com/text-to-speech/pricing).

Genie opens one bidirectional gRPC stream per answer and sends complete words as ChatGPT generates them. It does not wait for sentence punctuation. A small text buffer holds unfinished words, code blocks, and incomplete Markdown links. The final update flushes remaining text and closes the input side while audio continues to arrive.

Google returns headerless 24 kHz mono PCM. Genie writes those packets directly to Android's streaming `AudioTrack`, starting before the full audio response is available. The connection is shared across answers, and stream configuration is sent during ChatGPT generation to overlap connection setup with answer generation. Dismissing Genie, listening again, or entering silent/vibrate cancels the stream and flushes playback. No failed stream is automatically resubmitted.

The gRPC implementation uses an Android-compatible transport and a protobuf-lite subset of Google's public wire schema, rather than the unsupported-on-Android high-level Cloud Java client. [Streaming guide](https://docs.cloud.google.com/text-to-speech/docs/create-audio-text-streaming) · [Protocol schema](https://github.com/googleapis/googleapis/blob/master/google/cloud/texttospeech/v1/cloud_tts.proto).

### Verification

On October 29, 2026, the emulator passed 26 unit tests and instrumentation checks for month rollover, persistent cutoff enforcement, concurrent usage reservations, encrypted credential import, and key changes preserving usage. Live checks using the configured Cloud Text-to-Speech key completed synthesis and playback with British Aoede at normal speed and British Zephyr at 1.25×, then stopped a second answer when playback began. Voice and speed preferences persisted across settings reloads. The shortcut reconnected after successful and skipped checks; repair preserved an intentionally disabled shortcut. This verifies the emulator path, not physical Titan performance or subjective voice quality.

To repeat the live check (a few short billable speech requests, counted in the meter):

```sh
./gradlew installDebugAndroidTest -PgenieTestRunner=dev.genie.android.ChirpPlaybackCheck
python3 scripts/run_android_check.py dev.genie.android.ChirpPlaybackCheck -e live_chirp true
```

### Streaming speech verification

On October 29, 2026, a live US Aoede streaming check at 1.25× started audio before the final text was supplied, with first audio at **868 ms** from submitting the initial text. The earlier sentence/MP3 path measured 1,383 ms in a separate sample. These are single emulator samples, exclude ChatGPT generation time, and do not establish a stable latency guarantee. The live check also drained final audio and stopped a subsequent answer on interruption.

Unit coverage checks configuration-first gRPC messages, selected locale/speed, raw PCM arriving before input closes, remote cancellation, and word-by-word text buffering without repetition. Emulator checks cover silent/vibrate suppression of stream opening and text submission, character accounting, and cancellation when ringer mode changes. Offline voice previews remain separate from the network speech path.

## Response latency

Genie prepares account access, the model catalog, and a Responses WebSocket connection while you speak. Successful catalog results are reused for five minutes for the same account and access token. Switching accounts, signing out, and API errors invalidate the cached catalog. Settings can still explicitly refresh available models. OpenAI checks usage and model access on every answer request.

Answers stream over the prepared connection, which closes when the popup closes or the account changes. Each request still sends its full conversation context with `store: false`. If the connection cannot open or cannot enqueue a request, Genie falls back to HTTP/SSE. It does not automatically resend an answer after submission, which avoids duplicate inference after an interrupted stream. The transport follows OpenAI's [WebSocket guide](https://developers.openai.com/api/docs/guides/websocket-mode) and [ChatGPT plan preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

To measure first answer text using the saved account and model settings (three live ChatGPT requests, with connection preparation excluded):

```sh
./gradlew installDebugAndroidTest -PgenieTestRunner=dev.genie.android.chatgpt.ResponseLatencyCheck
python3 scripts/run_android_check.py dev.genie.android.chatgpt.ResponseLatencyCheck -e live_chatgpt true
```

On the emulator on October 28, 2026, GPT-6 Luna with low reasoning took 2,617 ms and 2,044 ms to first text before catalog caching, versus 1,949 ms and 1,175 ms afterward. These are two samples per version, measured after a model lookup; they show the warm path and do not measure speech recognition, physical-device speed, or guarantee future network latency.

The subsequent production WebSocket path measured **718 ms, 617 ms, and 738 ms** to first text, with completed one-sentence answers at **900 ms, 800 ms, and 948 ms**. The test used the same model and low reasoning, fetched the catalog first, and allowed 1.5 seconds for connection preparation before timing each request. In normal use, preparation runs during speech input. Cold startup and network/server variability can exceed one second.

## Space shortcut during development

Android instrumentation stops the target app process. On the Android 16 emulator, this can leave the accessibility shortcut marked as crashed even though its setting remains enabled. Run checks with `scripts/run_android_check.py` so the connection is restored in a `finally` block, including when a check fails. `installDebug` also verifies and repairs an already-enabled connection. Both paths preserve a shortcut that the user has turned off and leave other accessibility services configured.

## Build and install

With Android SDK 36, a compatible JDK, and an emulator or device connected through adb:

```sh
./gradlew testDebugUnitTest installDebug
```

### Low reasoning and Fast mode

Low remains the active reasoning floor for Genie's emulator setup. The reasoning picker follows the account catalog; Genie does not add an unlisted None option. Answer execution has its own worker so background catalog requests do not queue ahead of an answer with a prepared connection and cached catalog. Authentication refresh still waits when needed.

On October 29, 2026, a live Luna/Low test through the same ChatGPT-plan connection rejected `service_tier: "fast"` with `Unsupported service_tier: fast`. A request using the documented `priority` alias completed, but reported `service_tier: "default"`. Accepting the request did not enable Fast mode. No premium tier is enabled in Genie. API Fast mode is documented separately and charges a premium; API access is not evidence that a ChatGPT-plan token can use it. [Fast mode](https://developers.openai.com/api/docs/guides/priority-processing).

The concurrent-refresh smoke check (`-e background_refresh true` on `ResponseLatencyCheck`) measured first text at 4,484 ms, 700 ms, and 768 ms; the corresponding background catalog refreshes completed at 2,205 ms, 964 ms, and 3,152 ms. The last result demonstrates that a slow background refresh no longer serializes ahead of the answer. The first result is an unresolved latency outlier, so these results do not establish a stable subsecond guarantee.
