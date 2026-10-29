package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in live check: submits a few short speech requests to the user's Google project. */
class ChirpPlaybackCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("live_chirp") == "true") start()
        else finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Skipped: live_chirp=true required.") })
    }

    override fun onStart() {
        val settings = ChirpSettings(targetContext)
        val originalRate = settings.speakingRate
        val originalVoice = settings.voice
        var speech: ChirpSpeechOutput? = null
        val audio = targetContext.getSystemService(android.media.AudioManager::class.java)
        val originalMode = audio.ringerMode
        val notifications = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val hadPolicyAccess = notifications.isNotificationPolicyAccessGranted
        fun policy(command: String) {
            uiAutomation.executeShellCommand("cmd notification $command ${targetContext.packageName}").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        }
        val complete = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val firstSentence = "Hello, I’m Genie. My American voice can start before the answer finishes. "
        val fullAnswer = firstSentence + "Here is the rest of the answer."
        var startedAt = 0L
        var firstAudioMs = 0L
        val interrupted = CountDownLatch(1)
        var error: Int? = null
        var speaking = false
        var stopping = false
        try {
            uiAutomation.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.ACCESS_NOTIFICATION_POLICY")
            if (!hadPolicyAccess) policy("allow_dnd")
            audio.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            runOnMainSync {
                settings.speakingRate = 1.25f
                settings.voice = "en-US-Chirp3-HD-Aoede"
                speech = ChirpSpeechOutput(targetContext) { status ->
                    when (status) {
                        R.string.read_aloud_preparing -> Unit
                        R.string.read_aloud_speaking -> {
                            speaking = true
                            if (firstStarted.count > 0) {
                                firstAudioMs = (System.nanoTime() - startedAt) / 1_000_000
                                firstStarted.countDown()
                            }
                            if (stopping) { speech?.stop(); interrupted.countDown() }
                        }
                        R.string.read_aloud_ready -> complete.countDown()
                        else -> { error = status; firstStarted.countDown(); complete.countDown(); interrupted.countDown() }
                    }
                }
                startedAt = System.nanoTime()
                speech?.beginAnswer()
                speech?.updateAnswer(firstSentence + "Here")
            }
            check(firstStarted.await(60, TimeUnit.SECONDS)) { "Speech did not start before final text" }
            check(error == null) { targetContext.getString(error!!) }
            runOnMainSync { speech?.updateAnswer(fullAnswer, true) }
            check(complete.await(60, TimeUnit.SECONDS)) { "Voice test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            check(speaking) { "Playback never started" }
            runOnMainSync {
                stopping = true
                speech?.speak("This is a second short voice test. Genie should interrupt this answer as soon as playback starts.")
            }
            check(interrupted.await(60, TimeUnit.SECONDS)) { "Interruption test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Passed: live US Aoede at 1.25x started before final text (first audio ${firstAudioMs}ms), completed queued playback, and stopped playback.\n") })
        } catch (failure: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${failure.message}\n") })
        } finally {
            runOnMainSync {
                speech?.close()
                settings.speakingRate = originalRate
                settings.voice = originalVoice

            }
            audio.ringerMode = originalMode
            if (!hadPolicyAccess) policy("disallow_dnd")
            uiAutomation.dropShellPermissionIdentity()
        }
    }
}
