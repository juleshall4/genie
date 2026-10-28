# Genie

An Android assistant with a Space-triggered overlay, speech recognition, and answers through your connected ChatGPT account.

## Offline spoken answers

Genie reads completed answers with **owensong/Inflect-Nano-v2**, using its official FP32 ONNX export through the separate [TensorSpeak](https://github.com/AljGe/TensorSpeak) Android speech engine. Genie explicitly selects the offline `nano` voice; it does not select cloud voices or another system voice.

1. Install the appropriate TensorSpeak APK from [its releases](https://github.com/AljGe/TensorSpeak/releases). Use `arm64-v8a` for the Titan 2 Elite.
2. In TensorSpeak's **Voices** tab, install the **Nano** model pack (about 15 MB compressed).
3. Open Genie and select **Try offline voice** to check playback.

Genie selects the engine directly, so changing Android's default speech engine is unnecessary. After the one-time model download, speech generation stays offline. ChatGPT requests still require a connection. Closing the popup or selecting **Listen again** stops spoken output.

The companion engine is separately installed and licensed (GPL-3.0-or-later); Inflect weights are Apache-2.0. Genie communicates through Android's standard text-to-speech interface and does not bundle TensorSpeak source or binaries.

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
