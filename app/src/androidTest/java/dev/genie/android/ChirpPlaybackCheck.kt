package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.io.ByteArrayOutputStream
import java.io.File

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
        val originalEnabled = settings.enabled
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
        val firstSentence = "The temperature is high. "
        val fullAnswer = firstSentence + "Here is the rest of the answer."
        val submitted = CopyOnWriteArrayList<String>()
        val firstAudio = ByteArrayOutputStream()
        var streamNumber = 0
        val transport = ChirpStreamingApi()
        val api = ChirpStreamFactory { key, voice, rate, listener ->
            val first = ++streamNumber == 1
            val stream = transport.open(key, voice, rate, object : ChirpStreamFactory.Listener by listener {
                override fun onAudio(pcm: ByteArray) {
                    if (first) firstAudio.write(pcm)
                    listener.onAudio(pcm)
                }
            })
            object : ChirpStream by stream {
                override fun send(text: String) {
                    if (first) submitted.add(text)
                    stream.send(text)
                }
            }
        }
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
                settings.enabled = true
                speech = ChirpSpeechOutput(targetContext, api) { status ->
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
                val streaming = firstSentence + "Here"
                for (end in 1..streaming.length) speech?.updateAnswer(streaming.take(end))
            }
            check(firstStarted.await(60, TimeUnit.SECONDS)) { "Speech did not start before final text" }
            check(error == null) { targetContext.getString(error!!) }
            check(submitted.toList() == listOf(firstSentence)) { "The first sentence was fragmented: $submitted" }
            runOnMainSync { speech?.updateAnswer(fullAnswer, true) }
            check(complete.await(60, TimeUnit.SECONDS)) { "Voice test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            check(speaking) { "Playback never started" }
            check(submitted.joinToString("") == fullAnswer + " ") { "Submitted speech changed or repeated words: $submitted" }
            File(targetContext.cacheDir, "chirp-buffering-check.pcm").writeBytes(firstAudio.toByteArray())
            runOnMainSync {
                stopping = true
                speech?.speak("This is a second short voice test. Genie should interrupt this answer as soon as playback starts.")
            }
            check(interrupted.await(60, TimeUnit.SECONDS)) { "Interruption test timed out" }
            check(error == null) { targetContext.getString(error!!) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "Passed: $originalVoice at ${originalRate}x sent 'is high' in one complete sentence, started before final text (first audio ${firstAudioMs}ms), completed playback, and stopped playback.\n") })
        } catch (failure: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${failure.message}\n") })
        } finally {
            runOnMainSync {
                speech?.close()
                settings.speakingRate = originalRate
                settings.voice = originalVoice
                settings.enabled = originalEnabled
            }
            audio.ringerMode = originalMode
            if (!hadPolicyAccess) policy("disallow_dnd")
            uiAutomation.dropShellPermissionIdentity()
        }
    }
}
