package dev.genie.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Bundle
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs through the production speech path with a transport that counts and rejects requests. */
class ChirpSilentCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val name = "chirp-silent-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
            override fun getNoBackupFilesDir() = java.io.File(super.getNoBackupFilesDir(), name).apply { mkdirs() }
        }
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val originalMode = audio.ringerMode
        val notifications = targetContext.getSystemService(android.app.NotificationManager::class.java)
        val hadPolicyAccess = notifications.isNotificationPolicyAccessGranted
        fun policy(command: String) {
            uiAutomation.executeShellCommand("cmd notification $command ${targetContext.packageName}").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
        }
        var speech: ChirpSpeechOutput? = null
        val requests = AtomicInteger()
        val failure = CountDownLatch(1)
        var modeChange: CountDownLatch? = null
        val api = ChirpApi(OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw IOException("Test transport: no Google request sent")
        }.build())
        try {
            uiAutomation.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS", "android.permission.ACCESS_NOTIFICATION_POLICY")
            if (!hadPolicyAccess) policy("allow_dnd")
            val settings = ChirpSettings(isolated)
            settings.apiKey = "test-only-key"
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            check(audio.ringerMode == AudioManager.RINGER_MODE_NORMAL)
            runOnMainSync {
                speech = ChirpSpeechOutput(isolated, api) { status ->
                    if (status == R.string.read_aloud_failed) failure.countDown()
                    if (status == R.string.read_aloud_silent) modeChange?.countDown()
                }
                speech?.speak("A normal-mode request reaches the test transport.")
            }
            check(failure.await(10, TimeUnit.SECONDS))
            check(requests.get() == 1)
            val before = settings.usage().characters
            for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
                audio.ringerMode = mode
                check(audio.ringerMode == mode)
                runOnMainSync {
                    speech?.speak("This voice preview must never reach Google.")
                    speech?.beginAnswer()
                    speech?.updateAnswer("This completed sentence must never reach Google. More text follows.")
                    speech?.updateAnswer("This completed sentence must never reach Google. More text follows.", true)
                }
                check(requests.get() == 1)
                check(settings.usage().characters == before)
            }
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            runOnMainSync {
                speech?.beginAnswer()
                speech?.updateAnswer("An unfinished")
            }
            modeChange = CountDownLatch(1)
            audio.ringerMode = AudioManager.RINGER_MODE_SILENT
            check(modeChange!!.await(5, TimeUnit.SECONDS)) { "Active answer did not observe silent mode" }
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            runOnMainSync { speech?.updateAnswer("An unfinished answer should stay cancelled after sound returns.", true) }
            check(requests.get() == 1)
            check(settings.usage().characters == before)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "Passed: normal mode submits; silent and vibrate block preview and streamed answers with no requests or usage reservations; entering silent cancels the current answer.\n")
            })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Failed: ${error.javaClass.simpleName}: ${error.message}\n") })
        } finally {
            runOnMainSync { speech?.close() }
            audio.ringerMode = originalMode
            if (!hadPolicyAccess) policy("disallow_dnd")
            uiAutomation.dropShellPermissionIdentity()
            isolated.deleteSharedPreferences(name)
            isolated.noBackupFilesDir.deleteRecursively()
        }
    }
}
