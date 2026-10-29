# Genie

An Android assistant with a Space-triggered overlay, speech recognition, and answers through your connected ChatGPT account.

## Offline spoken answers

Genie reads completed answers with **owensong/Inflect-Nano-v2**, using its official FP32 ONNX export through the separate [TensorSpeak](https://github.com/AljGe/TensorSpeak) Android speech engine. Genie explicitly selects the offline `nano` voice; it does not select cloud voices or another system voice.

1. Install the appropriate TensorSpeak APK from [its releases](https://github.com/AljGe/TensorSpeak/releases). Use `arm64-v8a` for the Titan 2 Elite.
2. In TensorSpeak's **Voices** tab, install the **Nano** model pack (about 15 MB compressed).
3. Open Genie and select **Try offline voice** to check playback.

Genie selects the engine directly, so changing Android's default speech engine is unnecessary. After the one-time model download, speech generation stays offline. ChatGPT requests still require a connection. Closing the popup or selecting **Listen again** stops spoken output.

The companion engine is separately installed and licensed (GPL-3.0-or-later); Inflect weights are Apache-2.0. Genie communicates through Android's standard text-to-speech interface and does not bundle TensorSpeak source or binaries.

## Response latency

Genie prepares account access, the model catalog, and a Responses WebSocket connection while you speak. Successful catalog results are reused for five minutes for the same account and access token. Switching accounts, signing out, and API errors invalidate the cached catalog. Settings can still explicitly refresh available models. OpenAI checks usage and model access on every answer request.

Answers stream over the prepared connection, which closes when the popup closes or the account changes. Each request still sends its full conversation context with `store: false`. If the connection cannot open or cannot enqueue a request, Genie falls back to HTTP/SSE. It does not automatically resend an answer after submission, which avoids duplicate inference after an interrupted stream. The transport follows OpenAI's [WebSocket guide](https://developers.openai.com/api/docs/guides/websocket-mode) and [ChatGPT plan preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

To measure first answer text using the saved account and model settings (three live ChatGPT requests, with connection preparation excluded):

```sh
./gradlew installDebugAndroidTest -PgenieTestRunner=dev.genie.android.chatgpt.ResponseLatencyCheck
adb shell am instrument -w -e live_chatgpt true dev.genie.android.test/dev.genie.android.chatgpt.ResponseLatencyCheck
```

On the emulator on October 28, 2026, GPT-6 Luna with low reasoning took 2,617 ms and 2,044 ms to first text before catalog caching, versus 1,949 ms and 1,175 ms afterward. These are two samples per version, measured after a model lookup; they show the warm path and do not measure speech recognition, physical-device speed, or guarantee future network latency.

The subsequent production WebSocket path measured **718 ms, 617 ms, and 738 ms** to first text, with completed one-sentence answers at **900 ms, 800 ms, and 948 ms**. The test used the same model and low reasoning, fetched the catalog first, and allowed 1.5 seconds for connection preparation before timing each request. In normal use, preparation runs during speech input. Cold startup and network/server variability can exceed one second.

## Build and install

With Android SDK 36, a compatible JDK, and an emulator or device connected through adb:

```sh
./gradlew testDebugUnitTest installDebug
```

To check actual Nano playback and cancellation after installing the model:

```sh
./gradlew installDebugAndroidTest -PgenieTestRunner=dev.genie.android.LocalSpeechCheck
adb shell am instrument -w dev.genie.android.test/dev.genie.android.LocalSpeechCheck
```

### Low reasoning and Fast mode

Low remains the active reasoning floor for Genie's emulator setup. The reasoning picker follows the account catalog; Genie does not add an unlisted None option. Answer execution has its own worker so background catalog requests do not queue ahead of an answer with a prepared connection and cached catalog. Authentication refresh still waits when needed.

On October 29, 2026, a live Luna/Low test through the same ChatGPT-plan connection rejected `service_tier: "fast"` with `Unsupported service_tier: fast`. A request using the documented `priority` alias completed, but reported `service_tier: "default"`. Accepting the request did not enable Fast mode. No premium tier is enabled in Genie. API Fast mode is documented separately and charges a premium; API access is not evidence that a ChatGPT-plan token can use it. [Fast mode](https://developers.openai.com/api/docs/guides/priority-processing).

The concurrent-refresh smoke check (`-e background_refresh true` on `ResponseLatencyCheck`) measured first text at 4,484 ms, 700 ms, and 768 ms; the corresponding background catalog refreshes completed at 2,205 ms, 964 ms, and 3,152 ms. The last result demonstrates that a slow background refresh no longer serializes ahead of the answer. The first result is an unresolved latency outlier, so these results do not establish a stable subsecond guarantee.
