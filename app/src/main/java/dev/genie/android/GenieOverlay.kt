package dev.genie.android

import android.Manifest
import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.net.Uri
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient
import dev.genie.android.chatgpt.ChatGptRequest
import dev.genie.android.chatgpt.ChatTurn

// The box starts as a small listening pill, grows with the live transcript, shrinks
// back while thinking, then grows into the response box.
private enum class BoxMode { Listening, Transcript, Thinking, Answer }

// How far the box sits from the screen edges, in dp.
private const val BOX_INSET = 8
private const val ENTER_MILLIS = 320L
private const val EXIT_MILLIS = 240L
// How long your final words stay on screen before Genie starts thinking.
private const val FINAL_WORDS_MILLIS = 450L
private const val RESIZE_MILLIS = 200L

// A finished question and answer, shown in the chat log above the latest response.
private data class LoggedTurn(val question: CharSequence, val answer: String)

class GenieOverlay(private val service: Context) {
    // Views use a fixed scale; windows still come from the service, which owns the overlay's window token.
    private val context = service.withFixedScale()
    private val answerRenderer = AnswerRenderer(context)
    private val windowManager = service.getSystemService(WindowManager::class.java)
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
    private var bottomInset = 0
    private var displayCorner = 0
    private var linkCard: LinkCard? = null
    private var boxBlur: BoxBlur? = null
    // Puff appears in the pill, then beside the response once it arrives.
    private var pillPuff: PuffDrawable? = null
    private var leaving = false
    private var statusDots: CountingDots? = null
    private var pendingSend: Runnable? = null
    private var answerPuff: PuffDrawable? = null

    val isShowing: Boolean
        get() = popup != null

    /** Opens Genie, or starts listening again once it is open. */
    fun activate() {
        if (leaving) hide()
        val view = popup ?: return show()
        if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
    }

    /** Closes Genie, playing the entrance in reverse. */
    fun dismiss() {
        val view = popup ?: return
        if (leaving) return
        leaving = true
        view.findViewById<View>(R.id.overlay_scrim).animate().alpha(0f).setDuration(EXIT_MILLIS)
        view.findViewById<View>(R.id.genie_box).apply {
            // The box drops below the bottom of the screen, however tall it is.
            animate().translationY((view.height - top).toFloat()).setDuration(EXIT_MILLIS)
                .setInterpolator(AnticipateInterpolator(1.2f))
                .withEndAction { if (popup === view) hide() }
        }
    }

    fun hide() {
        generation++
        leaving = false
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
        popup?.let { windowManager.removeView(it) }
        popup = null
        pillPuff = null
        answerPuff = null
        statusDots = null
        mode = null
    }

    private fun show(question: String? = null) {
        generation++
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_genie, null)
        view.findViewById<View>(R.id.overlay_scrim).setOnClickListener { dismiss() }
        view.findViewById<Button>(R.id.speech_action).setOnClickListener {
            if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
        }
        linkCard = LinkCard(view.findViewById(R.id.link_card)) { url ->
            hide()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.findViewById<PopupScrollView>(R.id.answer_scroll).apply {
            // Scrolling up from the latest response opens the chat log; scrolling down past its end closes it.
            onPullPastTop = { (mode == BoxMode.Answer && !expanded && log.isNotEmpty()).also { if (it) setExpanded(view, true) } }
            onPullPastBottom = { expanded.also { if (it) setExpanded(view, false) } }
            // The log fades out under the box's top and bottom edges; the response alone doesn't need to.
            isVerticalFadingEdgeEnabled = false
            setOnScrollChangeListener { _, _, _, _, _ -> if (expanded) focusNearestAnswer(view) }
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            // A window that lays out beyond the screen's limits gets no insets of its own, so ask for the screen's.
            val screen = windowManager.currentWindowMetrics.windowInsets
            val bars = screen.getInsets(WindowInsets.Type.systemBars())
            bottomInset = bars.bottom
            topInset = bars.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                displayCorner = screen.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            }
            placeBox(view)
            (view.findViewById<View>(R.id.genie_box).background as GenieBoxDrawable).cornerRadius = boxCorner()
            insets
        }
        view.findViewById<View>(R.id.overlay_scrim).background = scrim()
        view.findViewById<View>(R.id.genie_box).background = GenieBoxDrawable(context.resources.displayMetrics.density)
        pillPuff = PuffDrawable().also { view.findViewById<ImageView>(R.id.puff).setImageDrawable(it) }
        answerPuff = PuffDrawable().also { view.findViewById<ImageView>(R.id.answer_puff).setImageDrawable(it) }
        for (id in listOf(R.id.speech_status, R.id.speech_transcript, R.id.chatgpt_answer, R.id.link_title)) view.findViewById<TextView>(id).glow(bright = true)
        for (id in listOf(R.id.current_question, R.id.link_domain, R.id.link_description)) view.findViewById<TextView>(id).glow(bright = false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // The window covers the status bar too, so the scrim and the box's shade aren't cut off below it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = context.getString(R.string.app_name)
        }
        // The blur window goes in first so it sits under the overlay.
        boxBlur = BoxBlur(service).also { it.show() }
        windowManager.addView(view, params)
        boxBlur?.follow(view.findViewById(R.id.genie_box))
        popup = view
        setMode(view, BoxMode.Listening)
        enter(view)
        speechOutput = ChirpSpeechOutput(context) {}
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
        pendingSend?.let(view::removeCallbacks)
        pendingSend = null
        speechOutput?.stop()
        if (expanded) setExpanded(view, false)
        linkCard?.clear()
        view.findViewById<View>(R.id.speech_action).visibility = View.GONE
        speechInput?.close()
        answerRequest?.cancel()
        answerRequest = null
        formattingRequest?.cancel()
        formattingRequest = null
        preparation?.cancel()
        preparation = chatGpt.prepare()
        view.findViewById<TextView>(R.id.chatgpt_answer).text = ""
        view.findViewById<View>(R.id.answer_row).visibility = View.GONE
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
                if (finished && transcript == null) {
                    setMode(view, BoxMode.Listening)
                    setPuff(PuffState.Error)
                }
                // The shortcut listens again; a button only appears when microphone permission is needed.
                if (finished && !hasMicrophonePermission()) action(view, R.string.enable_microphone) { requestMicrophonePermission() }
            }
        }, onFinalTranscript = { question ->
            // The last words often only arrive with the final result, so they stay up a moment first.
            if (popup === view) {
                pendingSend = Runnable {
                    pendingSend = null
                    if (popup === view) sendTranscript(view, question)
                }.also { view.postDelayed(it, FINAL_WORDS_MILLIS) }
            }
        }, onLevel = { rmsdB ->
            pillPuff?.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }).also { it.start() }
    }

    private fun showPermissionNeeded(view: View) {
        setMode(view, BoxMode.Transcript)
        setPuff(PuffState.Idle)
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
            // Once Genie is thinking, the pill shows only Puff and the status.
            visibility = if (mode == BoxMode.Transcript) View.VISIBLE else View.GONE
        }
        view.findViewById<TextView>(R.id.current_question).text = transcript
        // Your words replace the status line while you speak.
        if (mode == BoxMode.Transcript) view.findViewById<View>(R.id.speech_status).visibility = View.GONE
    }

    private fun setPuff(state: PuffState) {
        pillPuff?.state = state
        answerPuff?.state = state
    }

    private fun setStatus(view: View, status: Int) {
        view.findViewById<TextView>(R.id.speech_status).apply {
            // Genie shows Listening while the microphone starts, so there's no separate starting message.
            val text = context.getText(if (status == R.string.speech_starting) R.string.speech_listening else status)
            (statusDots ?: CountingDots(this).also { statusDots = it }).setText(text)
            visibility = View.VISIBLE
        }
    }

    // The pill rises onto the screen from below while the scrim fades in. Once it's there, every
    // container eases to its new size, whether the mode changes or the text inside it grows.
    private fun enter(view: View) {
        view.findViewById<View>(R.id.overlay_scrim).apply {
            alpha = 0f
            animate().alpha(1f).setDuration(ENTER_MILLIS)
        }
        view.findViewById<View>(R.id.genie_box).apply {
            translationY = dp(120).toFloat()
            animate().translationY(0f).setDuration(ENTER_MILLIS).setInterpolator(OvershootInterpolator(1.2f))
        }
        view.post {
            // Only the box and its pill animate; text inside the scrolling log moves instantly, so it never overlaps.
            for (id in listOf(R.id.overlay_root, R.id.genie_box, R.id.genie_header, R.id.genie_text)) {
                view.findViewById<ViewGroup>(id).layoutTransition = smoothLayout()
            }
        }
    }

    private fun smoothLayout() = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(RESIZE_MILLIS)
        for (type in listOf(LayoutTransition.CHANGING, LayoutTransition.CHANGE_APPEARING,
            LayoutTransition.CHANGE_DISAPPEARING, LayoutTransition.APPEARING, LayoutTransition.DISAPPEARING)) {
            setStartDelay(type, 0)
            setInterpolator(type, DecelerateInterpolator(1.5f))
        }
        setStagger(LayoutTransition.CHANGING, 0)
        setStagger(LayoutTransition.CHANGE_APPEARING, 0)
        setStagger(LayoutTransition.CHANGE_DISAPPEARING, 0)
    }

    private fun setMode(view: View, next: BoxMode) {
        if (mode == next) return
        mode = next
        // Only the response fills the width. The pill fits its status, and grows with your words as you speak.
        val transcript = next == BoxMode.Transcript
        val answer = next == BoxMode.Answer
        val wide = answer
        val box = view.findViewById<LinearLayout>(R.id.genie_box)
        box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        placeBox(view)
        (box.background as GenieBoxDrawable).apply {
            cornerRadius = boxCorner()
            lightAngle = if (transcript) 30f else 0f
            // A glow says Genie is listening or working; an answer just gets an outline.
            glow = !answer
        }
        // The handle only appears once there are earlier turns to scroll back to.
        val handle = answer && log.isNotEmpty()
        view.findViewById<View>(R.id.swipe_handle).visibility = if (handle) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.genie_header).visibility = if (answer) View.GONE else View.VISIBLE
        if (answer) box.setPadding(dp(16), dp(if (handle) 8 else 16), dp(16), dp(16))
        else box.setPadding(dp(13), dp(8), dp(24), dp(8))
        // The response scrolls inside the box's padding; the pill lets Puff's animations spill past it.
        box.clipToPadding = answer
        view.findViewById<View>(R.id.genie_text).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = if (wide) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (wide) 1f else 0f
            }
        }
        view.findViewById<View>(R.id.orb_frame).visibility = if (answer) View.GONE else View.VISIBLE
        setPuff(when (next) {
            BoxMode.Listening, BoxMode.Transcript -> PuffState.Listening
            BoxMode.Thinking -> PuffState.Thinking
            BoxMode.Answer -> PuffState.Speaking
        })
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            visibility = if (transcript && text.isNotEmpty()) View.VISIBLE else View.GONE
        }
        view.findViewById<View>(R.id.speech_status).visibility = if (answer) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.answer_scroll).visibility = if (answer) View.VISIBLE else View.GONE
    }

    // The box always sits the same inset from the screen edge, so the pill grows into the panel in place.
    private fun placeBox(view: View) {
        view.findViewById<View>(R.id.genie_box).apply {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = dp(BOX_INSET) + bottomInset
            }
        }
    }

    // The box follows the curve of the display's corners, less its inset. That's 32dp on most phones,
    // which also makes the 64dp pill fully round.
    private fun boxCorner(): Float = maxOf(dp(32), displayCorner - dp(BOX_INSET)).toFloat()

    // Clear at the top, darkening toward the bottom of the screen.
    private fun scrim() = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setColors(intArrayOf(0x00000000, 0x0D000000, 0x73000000, 0xD1000000.toInt()), floatArrayOf(0f, 0.25f, 0.6f, 1f))
    }

    private fun setExpanded(view: View, open: Boolean) {
        if (expanded == open) return
        expanded = open
        val scroll = view.findViewById<PopupScrollView>(R.id.answer_scroll)
        // The latest response stays where it is while the box grows up into the log, or shrinks back down.
        scroll.hold(view.findViewById(R.id.answer_row), RESIZE_MILLIS * 2)
        val box = view.findViewById<LinearLayout>(R.id.genie_box)
        box.layoutParams = (box.layoutParams as FrameLayout.LayoutParams).apply {
            height = if (open) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            topMargin = if (open) topInset + dp(BOX_INSET) else 0
        }
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
        view.findViewById<View>(R.id.answer_row).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(if (open) 16 else 12) }
        }
        focusAnswer(view, view.findViewById(R.id.chatgpt_answer))
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
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
                typeface = context.resources.getFont(R.font.inter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                lineHeight = sp(24f)
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
            topMargin = if (first) 0 else dp(16)
        }
        question.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        question.lineHeight = sp(20f)
        question.setTextColor(context.getColor(if (bubble) R.color.genie_text else R.color.genie_muted))
        question.background = if (bubble) context.getDrawable(R.drawable.genie_bubble) else null
        if (bubble) question.setPadding(dp(12), dp(8), dp(12), dp(8)) else question.setPadding(0, 0, 0, 0)
        question.maxWidth = if (bubble) (context.resources.displayMetrics.widthPixels * 0.7f).toInt() else Int.MAX_VALUE
        question.glow(bright = bubble)
    }

    // The answer you're reading is white; the rest of the log is muted. Only the colour changes, never the
    // size, so the log doesn't reflow and jump while you scroll it. Earlier answers are smaller than the latest.
    private fun styleAnswer(answer: TextView, focused: Boolean) {
        answer.setTextColor(context.getColor(if (focused) R.color.genie_text else R.color.genie_muted))
        answer.glow(bright = focused)
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
        // The answer under the middle of the box, or failing that, the closest one to it.
        val nearest = if (!scroll.canScrollVertically(1)) all.last()
            else all.minBy { bounds(it).run { if (center < top) top - center else maxOf(0, center - bottom) } }
        if (nearest !== focusedAnswer) focusAnswer(view, nearest)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun sp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics).toInt()

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
            setPuff(PuffState.Idle)
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
        view.findViewById<View>(R.id.speech_action).visibility = View.GONE
        view.findViewById<TextView>(R.id.chatgpt_answer).text = ""
        view.findViewById<View>(R.id.answer_row).visibility = View.VISIBLE
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
            if (popup === view && mode == BoxMode.Thinking) {
                setStatus(view, R.string.chatgpt_searching)
                setPuff(PuffState.Searching)
            }
        }, onDone = { error ->
            if (popup === view) {
                setPuff(if (error == null) PuffState.Done else PuffState.Error)
                if (error != null) speechOutput?.stop()
                error?.let { setStatus(view, it) }
                when (error) {
                    null -> {
                        history.add(ChatTurn("user", question))
                        history.add(ChatTurn("assistant", answer))
                        finishedTurn = LoggedTurn(view.findViewById<TextView>(R.id.current_question).text, answer)
                        speechOutput?.updateAnswer(answer, true)
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
