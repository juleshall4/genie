# Genie

Genie is a Kotlin Android assistant prototype for the Unihertz Titan 2 Elite. Press Space in an Android emulator to open a compact popup above other apps and transcribe your voice with Android's native speech service.

## Run the app

1. Open this folder in Android Studio and let Gradle sync. Use Android Studio's bundled JDK (17–25). Install Android SDK Platform 36 and Build Tools 36.0.0 if prompted.
2. Start an Android 11 or later emulator with hardware keyboard input enabled.
3. Run the `app` configuration.
4. In Genie, select **Enable Space shortcut**.
5. In Android Accessibility settings, select **Genie Space shortcut** (under **Downloaded apps** if shown), turn it on, and accept Android's permission prompt.
6. Open any other app. Click inside the emulator so it receives keyboard input, then press Space.
7. On first use, select **Enable microphone** in the popup and allow microphone access. Genie reopens and starts listening automatically. You can also grant permission from Genie's setup screen.

Genie displays partial text when the speech service provides it, then keeps the final transcript visible. Select **Listen again** for another attempt. Closing the popup cancels recognition and releases the microphone. Each new popup starts a fresh transcript; this version does not save transcripts or send them to ChatGPT.

Press Space again, press Escape, or select the popup's close button to dismiss Genie. Holding Space does not repeatedly toggle the popup.

While the service is enabled, unmodified Space is reserved for Genie across apps. Turn **Genie Space shortcut** off in Accessibility settings to restore normal typing. Keyboard shortcuts that include modifiers, such as Shift+Space, pass through.

If Android Studio captures Space instead of forwarding it, check the emulator's keyboard input settings. The emulator must receive a hardware keyboard event: `adb shell input keyevent` bypasses the accessibility shortcut and does not test this path.

## Build from the command line

Set `JAVA_HOME` to a JDK 17–25 and set `ANDROID_HOME` to your Android SDK directory, or configure the SDK path in an untracked `local.properties` file. Then run:

```sh
./gradlew assembleDebug lintDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## How the shortcut works

The prototype uses an Android `AccessibilityService` to receive hardware key events before the active app. The service shows a `TYPE_ACCESSIBILITY_OVERLAY` window without launching an activity or taking keyboard focus from the underlying app.

The service cannot retrieve window content and does not subscribe to screen events. The app requests microphone permission but no camera or network permissions. Android's speech provider may use the network to recognize speech; offline support depends on the installed provider and language models. Accessibility is a temporary mechanism for the emulator shortcut; the real phone's programmable button integration still needs to be checked.

## Configure emulator audio

Enable **Virtual microphone uses host audio input** in the emulator's **Extended controls > Microphone** panel and allow Android Studio or the emulator to use the Mac microphone if macOS prompts you. Turn off Android's microphone privacy toggle if it is enabled.

An Android speech recognition service must be installed and enabled. On Android 14 and later, Genie asks the provider to download missing language support once per listening attempt and resumes listening if the download succeeds. If the provider cannot supply the model, configure its language models in Android settings. Genie displays permission, microphone, connection, and recognition errors in the popup so you can retry.

ChatGPT requests, spoken answers, camera input, and long-press actions are not implemented yet.
# genie
