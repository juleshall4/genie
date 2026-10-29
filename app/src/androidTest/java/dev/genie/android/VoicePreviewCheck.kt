package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.MediaExtractor
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises packaged samples through the same offline player as settings. */
class VoicePreviewCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val name = "preview-check-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), name).apply { mkdirs() }
        }
        val settings = ChirpSettings(isolated)
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val originalMode = audio.ringerMode
        val notifications = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val hadPolicyAccess = notifications.isNotificationPolicyAccessGranted
        fun policy(command: String) {
            uiAutomation.executeShellCommand("cmd notification $command ${targetContext.packageName}").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        }
        var preview: VoicePreviewOutput? = null
        var playing = CountDownLatch(1)
        var completed = CountDownLatch(1)
        var silent = CountDownLatch(1)
        var failure: Int? = null
        try {
            uiAutomation.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.ACCESS_NOTIFICATION_POLICY")
            if (!hadPolicyAccess) policy("allow_dnd")
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            settings.enabled = false
            check(settings.apiKey.isEmpty())
            val usage = settings.usage()
            runOnMainSync {
                preview = VoicePreviewOutput(isolated) { status ->
                    when (status) {
                        R.string.voice_preview_playing -> playing.countDown()
                        R.string.voice_preview_ready -> completed.countDown()
                        R.string.read_aloud_silent -> silent.countDown()
                        R.string.voice_preview_failed -> { failure = status; playing.countDown(); completed.countDown() }
                    }
                }
            }
            for ((index, voice) in ChirpSettings.VOICES.withIndex()) {
                val extractor = MediaExtractor()
                try {
                    targetContext.assets.openFd("voice_samples/$voice.ogg").use {
                        extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    check(extractor.trackCount == 1)
                    val format = extractor.getTrackFormat(0)
                    check(format.getString("mime") == "audio/opus")
                    check(format.getInteger("channel-count") == 1)
                } finally { extractor.release() }
                playing = CountDownLatch(1)
                runOnMainSync { preview?.play(voice, if (index % 2 == 0) 0.25f else 2f) }
                check(playing.await(5, TimeUnit.SECONDS)) { "Preview did not start: $voice" }
                check(failure == null) { "Preview failed: $voice" }
                runOnMainSync { preview?.stop() }
            }
            // A US sample also runs to completion through the local player.
            completed = CountDownLatch(1)
            runOnMainSync { preview?.play("en-US-Chirp3-HD-Aoede", 2f) }
            check(completed.await(15, TimeUnit.SECONDS))
            check(failure == null)
            for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
                audio.ringerMode = mode
                silent = CountDownLatch(1)
                runOnMainSync { preview?.play(ChirpSettings.VOICES.first(), 1f) }
                check(silent.await(2, TimeUnit.SECONDS))
            }
            check(settings.usage() == usage)
            check(settings.apiKey.isEmpty())
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "Passed: all 34 packaged mono Opus voices decode and start at 0.25x/2x without credentials; completion, silent/vibrate blocking, and unchanged usage.\n")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${error.message}\n") })
        } finally {
            runOnMainSync { preview?.close() }
            audio.ringerMode = originalMode
            if (!hadPolicyAccess) policy("disallow_dnd")
            uiAutomation.dropShellPermissionIdentity()
            isolated.deleteSharedPreferences(name)
            isolated.noBackupFilesDir.deleteRecursively()
        }
    }
}
