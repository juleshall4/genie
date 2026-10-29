package dev.genie.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

internal interface SpeechEngine {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun downloadModel(intent: Intent, listener: ModelDownloadListener)
    fun close()
}

internal class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

    override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    override fun startListening(intent: Intent) = recognizer.startListening(intent)
    override fun downloadModel(intent: Intent, listener: ModelDownloadListener) {
        if (Build.VERSION.SDK_INT >= 34) recognizer.triggerModelDownload(intent, context.mainExecutor, listener)
    }
    override fun close() {
        recognizer.cancel()
        recognizer.destroy()
    }
}
