package dev.genie.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.net.Uri
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient
import dev.genie.android.chatgpt.ChatGptRequest
import dev.genie.android.chatgpt.ChatTurn

// The box starts as a small listening pill, widens for the live transcript and
// thinking, then grows into the response box.
private enum class BoxMode { Listening, Transcript, Thinking, Answer }

class GenieOverlay(private val context: Context) {
    private val answerRenderer = AnswerRenderer(context)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var popup: View? = null
    private var speechInput: NativeSpeechInput? = null
    private var speechOutput: ChirpSpeechOutput? = null
    private var generation = 0
    private val chatGpt = ChatGptClient.get(context)
    private var answerRequest: ChatGptRequest? = null
    private var formattingRequest: ChatGptRequest? = null
    private var preparation: ChatGptRequest? = null
    private val history = mutableListOf<ChatTurn>()
    private var conversationAccount: String? = null
    private var mode: BoxMode? = null

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
        formattingRequest?.cancel()
        formattingRequest = null
        preparation?.cancel()
        preparation = null
        chatGpt.closeConnection()
        history.clear()
        conversationAccount = null
        popup?.let {
            windowManager.removeView(it)
            it.findViewById<WebView>(R.id.orb).destroy()
        }
        popup = null
        mode = null
    }

    private fun show(question: String? = null) {
        generation++
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_genie, null)
        view.findViewById<View>(R.id.overlay_scrim).setOnClickListener { hide() }
        view.findViewById<Button>(R.id.speech_action).setOnClickListener {
            if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
        }
        view.findViewById<View>(R.id.chatgpt_plan).setOnClickListener { manageUsage() }
        view.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            view.findViewById<View>(R.id.genie_box).apply {
                layoutParams = (layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = dp(8) + bottom }
            }
            insets
        }
        showOrb(view)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            fitInsetsTypes = 0
            title = context.getString(R.string.app_name)
        }
        windowManager.addView(view, params)
        popup = view
        setMode(view, BoxMode.Listening)
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
        view.findViewById<View>(R.id.answer_sources).visibility = View.GONE
        view.findViewById<View>(R.id.source_links).visibility = View.GONE
        view.findViewById<View>(R.id.read_aloud_status).visibility = View.GONE
        speechInput?.close()
        answerRequest?.cancel()
        answerRequest = null
        formattingRequest?.cancel()
        formattingRequest = null
        preparation?.cancel()
        preparation = chatGpt.prepare()
        view.findViewById<TextView>(R.id.chatgpt_answer).apply { text = ""; visibility = View.GONE }
        view.findViewById<View>(R.id.chatgpt_plan).visibility = View.GONE
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            text = ""
            visibility = View.GONE
            scrollTo(0, 0)
        }
        setMode(view, BoxMode.Listening)
        speechInput = NativeSpeechInput(context, onUpdate = { status, transcript, finished ->
            if (popup === view) {
                if (finished || view.findViewById<View>(R.id.speech_transcript).visibility != View.VISIBLE) {
                    setStatus(view, status)
                }
                transcript?.let { displayTranscript(view, it) }
                if (finished && transcript == null) setMode(view, BoxMode.Transcript)
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
        }, onLevel = { rmsdB ->
            if (popup === view) pulseOrb(view, rmsdB)
        }).also { it.start() }
    }

    private fun showPermissionNeeded(view: View) {
        setMode(view, BoxMode.Transcript)
        setStatus(view, R.string.speech_permission_needed)
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
        if (mode == BoxMode.Listening) setMode(view, BoxMode.Transcript)
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            text = transcript
            visibility = View.VISIBLE
        }
        // Your words replace the status line while you speak.
        if (mode == BoxMode.Transcript) view.findViewById<View>(R.id.speech_status).visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showOrb(view: View) = view.findViewById<WebView>(R.id.orb).apply {
        // The real thinking-orbs engine, bundled in assets/orb.
        settings.javaScriptEnabled = true
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        loadUrl("file:///android_asset/orb/index.html")
    }

    private fun pulseOrb(view: View, rmsdB: Float) {
        if (mode != BoxMode.Listening && mode != BoxMode.Transcript) return
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        view.findViewById<View>(R.id.orb_frame).animate()
            .scaleX(0.92f + 0.22f * level).scaleY(0.92f + 0.22f * level).setDuration(120).start()
    }

    private fun setStatus(view: View, status: Int) {
        view.findViewById<TextView>(R.id.speech_status).apply {
            setText(status)
            visibility = View.VISIBLE
        }
    }

    private fun setMode(view: View, next: BoxMode) {
        if (mode == next) return
        mode = next
        TransitionManager.beginDelayedTransition(view as ViewGroup, AutoTransition().setDuration(220))
        val wide = next != BoxMode.Listening
        val answer = next == BoxMode.Answer
        val box = view.findViewById<LinearLayout>(R.id.genie_box)
        box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        (box.background as GradientDrawable).cornerRadius = dp(if (answer) 32 else 28).toFloat()
        if (answer) box.setPadding(dp(16), dp(16), dp(16), dp(18))
        else box.setPadding(dp(10), dp(10), dp(if (wide) 20 else 22), dp(10))
        view.findViewById<View>(R.id.genie_text).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = if (wide) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (wide) 1f else 0f
            }
        }
        view.findViewById<View>(R.id.orb_frame).apply {
            visibility = if (answer) View.GONE else View.VISIBLE
            background = if (next == BoxMode.Transcript) context.getDrawable(R.drawable.genie_orb_glow) else null
            if (next == BoxMode.Thinking) animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        val listening = next == BoxMode.Listening || next == BoxMode.Transcript
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            setTextColor(context.getColor(if (listening) R.color.genie_text else R.color.genie_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (listening) 17f else 15f)
        }
        view.findViewById<TextView>(R.id.speech_status).apply {
            setTextColor(context.getColor(if (next == BoxMode.Thinking) R.color.genie_text else R.color.genie_muted))
            visibility = if (answer) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.answer_scroll).visibility = if (answer) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun sendTranscript(view: View, question: String) {
        formattingRequest?.cancel()
        answerTranscript(view, question)
        if (chatGpt.store.active()?.connected != true) return
        formattingRequest = chatGpt.formatTranscript(question) { formatted, _ ->
            if (popup === view) {
                displayTranscript(view, formatted)
            }
        }
    }

    private fun answerTranscript(view: View, question: String) {
        answerRequest?.cancel()
        val sources = AnswerSources(view.findViewById(R.id.answer_sources), view.findViewById(R.id.source_links)) { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val account = chatGpt.store.active()
        if (account?.connected != true) {
            setMode(view, BoxMode.Thinking)
            setStatus(view, R.string.chatgpt_connect_needed)
            action(view, R.string.chatgpt_continue) { connectChatGpt(question) }
            return
        }
        if (conversationAccount != account.clientId) {
            history.clear()
            conversationAccount = account.clientId
        }
        setMode(view, BoxMode.Thinking)
        setStatus(view, R.string.chatgpt_thinking)
        view.findViewById<View>(R.id.chatgpt_plan).visibility = View.VISIBLE
        view.findViewById<View>(R.id.speech_action).visibility = View.GONE
        view.findViewById<TextView>(R.id.chatgpt_answer).apply { text = ""; visibility = View.VISIBLE }
        var answer = ""
        speechOutput?.beginAnswer()
        answerRequest = chatGpt.answer(history.toList() + ChatTurn("user", question), onText = { text ->
            if (popup === view) {
                answer = text
                if (mode != BoxMode.Answer && text.isNotBlank()) setMode(view, BoxMode.Answer)
                answerRenderer.setAnswer(view.findViewById(R.id.chatgpt_answer), text)
                speechOutput?.updateAnswer(text)
            }
        }, onCitations = { citations ->
            if (popup === view) sources.setSources(citations)
        }, onSearching = {
            if (popup === view && mode == BoxMode.Thinking) setStatus(view, R.string.chatgpt_searching)
        }, onDone = { error ->
            if (popup === view) {
                if (error != null) speechOutput?.stop()
                error?.let { setStatus(view, it) }
                when (error) {
                    null -> {
                        history.add(ChatTurn("user", question))
                        history.add(ChatTurn("assistant", answer))
                        speechOutput?.updateAnswer(answer, true)
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
                    else -> action(view, R.string.chatgpt_retry) { answerTranscript(view, question) }
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
