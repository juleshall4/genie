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
import android.view.Gravity
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

// A finished question and answer, shown in the chat log above the latest response.
private data class LoggedTurn(val question: CharSequence, val answer: String)

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
    private val log = mutableListOf<LoggedTurn>()
    private var finishedTurn: LoggedTurn? = null
    private var expanded = false
    private var focusedAnswer: TextView? = null
    private var topInset = 0
    private var linkCard: LinkCard? = null
    private var boxBlur: BoxBlur? = null

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
        log.clear()
        finishedTurn = null
        expanded = false
        focusedAnswer = null
        linkCard?.clear()
        linkCard = null
        boxBlur?.dismiss()
        boxBlur = null
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
        linkCard = LinkCard(view.findViewById(R.id.link_card)) { url ->
            hide()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.findViewById<PopupScrollView>(R.id.answer_scroll).apply {
            // Scrolling up from the latest response opens the chat log; scrolling down past its end closes it.
            onPullPastTop = { if (mode == BoxMode.Answer && !expanded && log.isNotEmpty()) setExpanded(view, true) }
            onPullPastBottom = { if (expanded) setExpanded(view, false) }
            setOnScrollChangeListener { _, _, _, _, _ -> if (expanded) focusNearestAnswer(view) }
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            topInset = insets.getInsets(WindowInsets.Type.systemBars()).top
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
        // The blur window goes in first so it sits under the overlay.
        boxBlur = BoxBlur(context).also { it.show() }
        windowManager.addView(view, params)
        boxBlur?.follow(view.findViewById(R.id.genie_box))
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
        if (expanded) setExpanded(view, false)
        linkCard?.clear()
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
        view.findViewById<TextView>(R.id.current_question).text = transcript
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
        // The handle only appears once there are earlier turns to scroll back to.
        val handle = answer && log.isNotEmpty()
        view.findViewById<View>(R.id.swipe_handle).visibility = if (handle) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.genie_header).visibility = if (answer) View.GONE else View.VISIBLE
        if (answer) box.setPadding(dp(16), dp(if (handle) 8 else 16), dp(16), dp(18))
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

    private fun setExpanded(view: View, open: Boolean) {
        if (expanded == open) return
        expanded = open
        TransitionManager.beginDelayedTransition(view as ViewGroup, AutoTransition().setDuration(220))
        val box = view.findViewById<LinearLayout>(R.id.genie_box)
        box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (open) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            topMargin = if (open) topInset + dp(16) else 0
        }
        val scroll = view.findViewById<PopupScrollView>(R.id.answer_scroll)
        scroll.expanded = open
        scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (open) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            weight = if (open) 1f else 0f
        }
        scroll.isVerticalFadingEdgeEnabled = open
        scroll.isVerticalScrollBarEnabled = open
        view.findViewById<View>(R.id.history_log).visibility = if (open) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.link_card).visibility =
            if (!open && linkCard?.url != null) View.VISIBLE else View.GONE
        styleQuestion(view.findViewById(R.id.current_question), bubble = open, first = !open)
        val answer = view.findViewById<TextView>(R.id.chatgpt_answer)
        answer.layoutParams = (answer.layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(if (open) 18 else 12)
        }
        focusAnswer(view, answer)
        // The chat log opens on the latest response, then you keep scrolling up through it.
        scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                scroll.removeOnLayoutChangeListener(this)
                scroll.scrollTo(0, if (open) view.findViewById<View>(R.id.conversation).height else 0)
            }
        })
    }

    private fun showLog(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.history_log)
        container.removeAllViews()
        for ((index, turn) in log.withIndex()) {
            container.addView(TextView(context).apply {
                typeface = context.resources.getFont(R.font.inter)
                text = turn.question
                styleQuestion(this, bubble = true, first = index == 0)
            })
            container.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
                typeface = context.resources.getFont(R.font.inter)
                answerRenderer.setAnswer(this, turn.answer)
                styleAnswer(this, focused = false)
            })
        }
    }

    // A question is a muted line above the latest response, or a bubble in the chat log.
    private fun styleQuestion(question: TextView, bubble: Boolean, first: Boolean) {
        question.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (bubble) Gravity.END else Gravity.START
            topMargin = if (first) 0 else dp(18)
        }
        question.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        question.lineHeight = dp(20)
        question.setTextColor(context.getColor(if (bubble) R.color.genie_text else R.color.genie_muted))
        question.background = if (bubble) context.getDrawable(R.drawable.genie_bubble) else null
        if (bubble) question.setPadding(dp(14), dp(9), dp(14), dp(9)) else question.setPadding(0, 0, 0, 0)
        question.maxWidth = if (bubble) (context.resources.displayMetrics.widthPixels * 0.7f).toInt() else Int.MAX_VALUE
    }

    // The answer you're reading is large and white; the rest of the log is smaller and muted.
    private fun styleAnswer(answer: TextView, focused: Boolean) {
        answer.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (focused) 22f else 17f)
        answer.lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (focused) 29f else 24f,
            context.resources.displayMetrics).toInt()
        answer.letterSpacing = if (focused) -0.01f else 0f
        answer.setTextColor(context.getColor(if (focused) R.color.genie_text else R.color.genie_muted))
    }

    private fun answers(view: View): List<TextView> {
        val container = view.findViewById<ViewGroup>(R.id.history_log)
        return (0 until container.childCount).map { container.getChildAt(it) }.filterIndexed { index, _ -> index % 2 == 1 }
            .map { it as TextView } + view.findViewById<TextView>(R.id.chatgpt_answer)
    }

    private fun focusAnswer(view: View, answer: TextView) {
        focusedAnswer = answer
        answers(view).forEach { styleAnswer(it, focused = it === answer || !expanded) }
    }

    private fun focusNearestAnswer(view: View) {
        val scroll = view.findViewById<PopupScrollView>(R.id.answer_scroll)
        val content = view.findViewById<ViewGroup>(R.id.conversation)
        val all = answers(view)
        val center = scroll.scrollY + scroll.height / 2
        fun bounds(answer: TextView) = android.graphics.Rect().also {
            answer.getDrawingRect(it)
            content.offsetDescendantRectToMyCoords(answer, it)
        }
        val nearest = if (!scroll.canScrollVertically(1)) all.last()
            else all.minBy { kotlin.math.abs(bounds(it).centerY() - center) }
        if (nearest === focusedAnswer) return
        // Keep the newly focused answer where it is on screen while the text sizes change.
        val anchor = bounds(nearest).top - scroll.scrollY
        focusAnswer(view, nearest)
        content.post { scroll.scrollTo(0, bounds(nearest).top - anchor) }
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
        linkCard?.clear()
        val account = chatGpt.store.active()
        if (account?.connected != true) {
            setMode(view, BoxMode.Thinking)
            setStatus(view, R.string.chatgpt_connect_needed)
            action(view, R.string.chatgpt_continue) { connectChatGpt(question) }
            return
        }
        if (conversationAccount != account.clientId) {
            history.clear()
            log.clear()
            finishedTurn = null
            conversationAccount = account.clientId
        }
        finishedTurn?.let { log += it }
        finishedTurn = null
        showLog(view)
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
            if (popup === view) {
                linkCard?.setSources(citations)
                if (expanded) view.findViewById<View>(R.id.link_card).visibility = View.GONE
            }
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
                        finishedTurn = LoggedTurn(view.findViewById<TextView>(R.id.current_question).text, answer)
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
