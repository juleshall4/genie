package dev.genie.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient
import dev.genie.android.chatgpt.ChatGptRequest
import dev.genie.android.chatgpt.ChatTurn

class GenieOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var popup: View? = null
    private var speechInput: NativeSpeechInput? = null
    private var speechOutput: ChirpSpeechOutput? = null
    private var generation = 0
    private val chatGpt = ChatGptClient.get(context)
    private var answerRequest: ChatGptRequest? = null
    private var preparation: ChatGptRequest? = null
    private val history = mutableListOf<ChatTurn>()
    private var conversationAccount: String? = null

    val isShowing: Boolean
        get() = popup != null

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun hide() {
        generation++
        speechOutput?.close()
        speechOutput = null
        speechInput?.close()
        speechInput = null
        answerRequest?.cancel()
        answerRequest = null
        preparation?.cancel()
        preparation = null
        chatGpt.closeConnection()
        history.clear()
        conversationAccount = null
        popup?.let(windowManager::removeView)
        popup = null
    }

    private fun show(question: String? = null) {
        generation++
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_genie, null)
        view.findViewById<View>(R.id.close_overlay).setOnClickListener { hide() }
        view.findViewById<Button>(R.id.speech_action).setOnClickListener {
            if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
        }
        view.findViewById<View>(R.id.chatgpt_plan).setOnClickListener { manageUsage() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (16 * context.resources.displayMetrics.density).toInt()
            title = context.getString(R.string.app_name)
        }
        windowManager.addView(view, params)
        popup = view
        speechOutput = ChirpSpeechOutput(context) { status ->
            if (popup === view) view.findViewById<TextView>(R.id.read_aloud_status).apply {
                setText(status)
                visibility = View.VISIBLE
            }
        }
        if (question != null) {
            displayTranscript(view, question)
            sendTranscript(view, question)
        } else if (hasMicrophonePermission()) {
            startListening(view)
        } else {
            showPermissionNeeded(view)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening(view: View) {
        speechOutput?.stop()
        view.findViewById<View>(R.id.read_aloud_status).visibility = View.GONE
        speechInput?.close()
        answerRequest?.cancel()
        answerRequest = null
        preparation?.cancel()
        preparation = chatGpt.prepare()
        view.findViewById<TextView>(R.id.chatgpt_answer).apply { text = ""; visibility = View.GONE }
        view.findViewById<View>(R.id.chatgpt_plan).visibility = View.GONE
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            text = ""
            visibility = View.GONE
            scrollTo(0, 0)
        }
        speechInput = NativeSpeechInput(context, onUpdate = { status, transcript, finished ->
            if (popup === view) {
                view.findViewById<TextView>(R.id.speech_status).setText(status)
                transcript?.let { displayTranscript(view, it) }
                view.findViewById<Button>(R.id.speech_action).apply {
                    setText(if (hasMicrophonePermission()) R.string.speech_retry else R.string.enable_microphone)
                    visibility = if (finished) View.VISIBLE else View.GONE
                    setOnClickListener {
                        if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
                    }
                }
            }
        }, onFinalTranscript = { question ->
            if (popup === view) sendTranscript(view, question)
        }).also { it.start() }
    }

    private fun showPermissionNeeded(view: View) {
        view.findViewById<TextView>(R.id.speech_status).setText(R.string.speech_permission_needed)
        view.findViewById<Button>(R.id.speech_action).apply {
            setText(R.string.enable_microphone)
            visibility = View.VISIBLE
        }
    }

    private fun requestMicrophonePermission() {
        // Keep Genie out of the way of Android's permission dialog.
        hide()
        val requestGeneration = generation
        MicrophonePermissionActivity.launch(context, object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (generation == requestGeneration) show()
            }
        })
    }

    private fun displayTranscript(view: View, transcript: String) {
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            text = transcript
            visibility = View.VISIBLE
        }
    }

    private fun sendTranscript(view: View, question: String) {
        answerRequest?.cancel()
        val account = chatGpt.store.active()
        if (account?.connected != true) {
            view.findViewById<TextView>(R.id.speech_status).setText(R.string.chatgpt_connect_needed)
            action(view, R.string.chatgpt_continue) { connectChatGpt(question) }
            return
        }
        if (conversationAccount != account.clientId) {
            history.clear()
            conversationAccount = account.clientId
        }
        view.findViewById<TextView>(R.id.speech_status).setText(R.string.chatgpt_thinking)
        view.findViewById<View>(R.id.chatgpt_plan).visibility = View.VISIBLE
        view.findViewById<View>(R.id.speech_action).visibility = View.GONE
        view.findViewById<TextView>(R.id.chatgpt_answer).apply { text = ""; visibility = View.VISIBLE }
        var answer = ""
        answerRequest = chatGpt.answer(history.toList() + ChatTurn("user", question), onText = { text ->
            if (popup === view) {
                answer = text
                view.findViewById<TextView>(R.id.chatgpt_answer).text = text
            }
        }, onDone = { error ->
            if (popup === view) {
                view.findViewById<TextView>(R.id.speech_status).setText(error ?: R.string.chatgpt_answered)
                when (error) {
                    null -> {
                        history.add(ChatTurn("user", question))
                        history.add(ChatTurn("assistant", answer))
                        speechOutput?.speak(answer)
                        action(view, R.string.speech_retry) { startListening(view) }
                    }
                    R.string.chatgpt_connect_needed, R.string.chatgpt_plan_permission_needed ->
                        action(view, R.string.chatgpt_continue) { connectChatGpt(question) }
                    R.string.chatgpt_usage_limit -> action(view, R.string.chatgpt_manage_usage) { manageUsage() }
                    R.string.chatgpt_ineligible, R.string.chatgpt_model_unavailable,
                    R.string.chatgpt_reasoning_unavailable -> action(view, R.string.chatgpt_open_settings) {
                        hide()
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    else -> action(view, R.string.chatgpt_retry) { sendTranscript(view, question) }
                }
            }
        })
    }

    private fun action(view: View, label: Int, onClick: () -> Unit) {
        view.findViewById<Button>(R.id.speech_action).apply {
            setText(label)
            visibility = View.VISIBLE
            setOnClickListener { onClick() }
        }
    }

    private fun connectChatGpt(question: String) {
        hide()
        val requestGeneration = generation
        ChatGptSignInActivity.launch(context, chatGpt.store.active()?.clientId,
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (generation == requestGeneration) show(question)
                }
            })
    }

    private fun manageUsage() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
