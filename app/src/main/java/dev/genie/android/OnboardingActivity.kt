package dev.genie.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient
import java.lang.ref.WeakReference

/**
 * First launch: Puff talks you through setup, one step at a time, in a babble voice. Tap to finish a
 * line or move on. Steps that are already done are skipped, unless the activity starts with
 * [EXTRA_RESET], which plays it like a fresh install.
 */
class OnboardingActivity : Activity() {
    private enum class Step { Hello, HowItWorks, Microphone, Shortcut, Button, ChatGpt, AllSet }

    private class Choice(val primary: Int, val onPrimary: () -> Unit, val secondary: Int? = null, val onSecondary: () -> Unit = {})

    private val handler = Handler(Looper.getMainLooper())
    private val puff = PuffDrawable().apply { state = PuffState.Idle }
    private lateinit var babble: Babble
    private lateinit var line: TextView
    private lateinit var actions: View
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    // The small arrow that says "tap to continue".
    private lateinit var hint: View
    private lateinit var nudge: ObjectAnimator
    private lateinit var dots: LinearLayout

    private var everyStep = false
    private var step = Step.Hello
    private var lines = emptyList<String>()
    private var lineAt = 0
    private var choice: Choice? = null
    private var text = ""
    private var shown = 0
    private var typing = false
    // The outside screen Puff sent you to, so it can react when you come back.
    private var away: Step? = null
    private var waitingForButton = false
    // How Puff looks once it stops talking.
    private var afterLine = PuffState.Idle

    private val tick = object : Runnable {
        override fun run() {
            val ch = text[shown++]
            render()
            if (ch.isLetter() && shown % 2 == 1) babble.say(ch)
            if (shown >= text.length) finishLine()
            else handler.postDelayed(this, when (ch) { '.', '!', '?' -> 280L; ',', ':' -> 150L; else -> 34L })
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(newBase.fixedScaleConfiguration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        everyStep = intent.getBooleanExtra(EXTRA_RESET, false)
        if (everyStep) preferences().edit().putBoolean(DONE, false).apply()
        setContentView(R.layout.activity_onboarding)
        babble = Babble(this)

        val root = findViewById<View>(R.id.onboarding_root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.setOnClickListener { tap() }
        line = findViewById<TextView>(R.id.onboarding_line).apply { glow(bright = true) }
        actions = findViewById(R.id.onboarding_actions)
        primary = findViewById(R.id.onboarding_primary)
        secondary = findViewById<TextView>(R.id.onboarding_secondary).apply { glow(bright = false) }
        hint = findViewById(R.id.onboarding_next)
        nudge = ObjectAnimator.ofFloat(hint, View.TRANSLATION_Y, 0f, dp(3).toFloat()).apply {
            duration = 450
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        dots = findViewById(R.id.onboarding_dots)
        findViewById<TextView>(R.id.onboarding_skip).apply {
            glow(bright = false)
            setOnClickListener { go(Step.AllSet) }
        }
        findViewById<ImageView>(R.id.onboarding_puff).setImageDrawable(puff)
        findViewById<ImageView>(R.id.onboarding_pill_puff).setImageDrawable(PuffDrawable())
        findViewById<View>(R.id.onboarding_pill).background = GenieBoxDrawable(resources.displayMetrics.density).apply {
            cornerRadius = 32 * resources.displayMetrics.density
        }
        findViewById<TextView>(R.id.onboarding_pill_text).glow(bright = true)
        val size = dp(6)
        repeat(Step.entries.size) {
            dots.addView(View(this), LinearLayout.LayoutParams(size, size).apply { marginEnd = size })
        }

        // Puff appears first, then starts talking.
        handler.postDelayed({ go(Step.Hello) }, 1300)
    }

    override fun onResume() {
        super.onResume()
        when (away) {
            Step.Shortcut -> if (shortcutOn()) react(true, R.string.onboarding_shortcut_on)
                else react(false, R.string.onboarding_shortcut_off, Choice(R.string.onboarding_try_again, ::openShortcut, R.string.onboarding_later, ::next))
            Step.Button -> {
                waitingForButton = true
                listening = WeakReference(this)
                say(listOf(getString(R.string.onboarding_button_check)),
                    Choice(R.string.onboarding_button_action, ::openButtonSettings, R.string.onboarding_later, ::next))
            }
            else -> Unit
        }
        away = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nudge.cancel()
        if (listening?.get() === this) listening = null
        babble.release()
        super.onDestroy()
    }

    private fun go(next: Step) {
        if (!everyStep && done(next)) return go(Step.entries[next.ordinal + 1])
        step = next
        waitingForButton = false
        afterLine = if (next == Step.AllSet) PuffState.Done else PuffState.Idle
        findViewById<View>(R.id.onboarding_skip).visibility = if (next == Step.AllSet) View.INVISIBLE else View.VISIBLE
        val demo = next == Step.HowItWorks
        findViewById<View>(R.id.onboarding_puff).visibility = if (demo) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.onboarding_pill).visibility = if (demo) View.VISIBLE else View.GONE
        if (demo) typeDemo()
        for (i in 0 until dots.childCount) dots.getChildAt(i).apply {
            layoutParams.width = dp(if (i == next.ordinal) 18 else 6)
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(getColor(when {
                    i == next.ordinal -> R.color.genie_text
                    i < next.ordinal -> R.color.genie_muted
                    else -> R.color.genie_faint
                }))
            }
            requestLayout()
        }
        when (next) {
            Step.Hello -> say(listOf(getString(R.string.onboarding_hello_1), getString(R.string.onboarding_hello_2)))
            Step.HowItWorks -> say(listOf(getString(R.string.onboarding_how_1), getString(R.string.onboarding_how_2)))
            Step.Microphone -> say(listOf(getString(R.string.onboarding_microphone)),
                Choice(R.string.onboarding_microphone_action, ::askMicrophone))
            Step.Shortcut -> say(listOf(getString(R.string.onboarding_shortcut)),
                Choice(R.string.onboarding_shortcut_action, ::openShortcut))
            Step.Button -> say(listOf(getString(R.string.onboarding_button)),
                Choice(R.string.onboarding_button_action, ::openButtonSettings))
            Step.ChatGpt -> say(listOf(getString(R.string.onboarding_chatgpt)),
                Choice(R.string.onboarding_chatgpt_action, ::connectChatGpt))
            Step.AllSet -> say(listOf(getString(R.string.onboarding_done)), Choice(R.string.onboarding_done_action, ::complete))
        }
    }

    private fun next() = go(Step.entries[step.ordinal + 1])

    private fun tap() {
        when {
            typing -> finishLine()
            lineAt < lines.size - 1 -> speak(lines[++lineAt])
            choice != null -> ObjectAnimator.ofFloat(primary, View.ROTATION, 0f, -3f, 3f, 0f).setDuration(400).start()
            step != Step.AllSet -> next()
        }
    }

    // Puff says each line in turn, then offers the choice, if there is one.
    private fun say(next: List<String>, then: Choice? = null) {
        lines = next
        lineAt = 0
        choice = then
        speak(lines[0])
    }

    private fun speak(next: String) {
        handler.removeCallbacks(tick)
        text = next
        shown = 0
        typing = true
        puff.state = PuffState.Speaking
        fade(actions, false)
        nudge.cancel()
        hint.animate().cancel()
        hint.alpha = 0f
        render()
        handler.post(tick)
    }

    private fun finishLine() {
        handler.removeCallbacks(tick)
        typing = false
        shown = text.length
        puff.state = afterLine
        render()
        line.announceForAccessibility(text)
        val offer = choice?.takeIf { lineAt == lines.size - 1 }
        if (offer != null) {
            primary.setText(offer.primary)
            primary.setOnClickListener { offer.onPrimary() }
            secondary.visibility = if (offer.secondary == null) View.GONE else View.VISIBLE
            offer.secondary?.let { secondary.setText(it) }
            secondary.setOnClickListener { offer.onSecondary() }
            fade(actions, true)
        } else {
            hint.translationY = 0f
            hint.animate().alpha(1f).setDuration(200).withEndAction { nudge.start() }.start()
        }
    }

    // Unwritten letters keep their place, so a word never starts on one line and jumps to the next.
    private fun render() {
        line.text = SpannableString(text).apply { setSpan(Unwritten, shown, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    // Puff says how it went, then hops for joy or droops.
    private fun react(happy: Boolean, message: Int, then: Choice? = null) {
        afterLine = if (happy) PuffState.Done else PuffState.Error
        say(listOf(getString(message)), then)
    }

    private fun typeDemo() {
        val pill = findViewById<TextView>(R.id.onboarding_pill_text)
        val words = getString(R.string.onboarding_how_demo)
        pill.text = ""
        for (n in 1..words.length) handler.postDelayed({ pill.text = words.substring(0, n) }, 500L + n * 70L)
    }

    private fun askMicrophone() {
        MicrophonePermissionActivity.launch(this, object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (resultCode == RESULT_OK) react(true, R.string.onboarding_microphone_allowed)
                else react(false, R.string.onboarding_microphone_denied,
                    Choice(R.string.onboarding_try_again, ::askMicrophone, R.string.onboarding_later, ::next))
            }
        })
    }

    private fun openShortcut() {
        away = Step.Shortcut
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // The Titan 2 keeps its programmable key in its own settings app; other phones get the main Settings.
    private fun openButtonSettings() {
        away = Step.Button
        try {
            startActivity(Intent().setComponent(BUTTON_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: SecurityException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun onButtonPressed() {
        waitingForButton = false
        react(true, R.string.onboarding_button_pressed)
    }

    private fun connectChatGpt() {
        ChatGptSignInActivity.launch(this, receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                // Sign-in runs in its own task, so bring Puff back to the front.
                startActivity(Intent(this@OnboardingActivity, OnboardingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                if (chatGptConnected()) react(true, R.string.onboarding_chatgpt_connected)
                else react(false, R.string.onboarding_chatgpt_cancelled,
                    Choice(R.string.onboarding_try_again, ::connectChatGpt, R.string.onboarding_later, ::next))
            }
        })
    }

    private fun complete() {
        preferences().edit().putBoolean(DONE, true).apply()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun done(step: Step) = when (step) {
        Step.Microphone -> checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Step.Shortcut -> shortcutOn()
        Step.ChatGpt -> chatGptConnected()
        else -> false
    }

    private fun shortcutOn(): Boolean {
        val component = ComponentName(this, GenieShortcutService::class.java)
        return getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component }
    }

    private fun chatGptConnected() = ChatGptClient.get(this).store.active()?.connected == true

    private fun fade(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = dp(6).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(300).start()
        } else {
            view.alpha = 0f
            view.visibility = View.INVISIBLE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun preferences() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    private object Unwritten : CharacterStyle() {
        override fun updateDrawState(paint: TextPaint) {
            paint.color = Color.TRANSPARENT
            paint.clearShadowLayer()
        }
    }

    companion object {
        /** Plays onboarding like a fresh install: forgets it was finished and shows every step. */
        const val EXTRA_RESET = "reset"
        private const val PREFERENCES = "onboarding"
        private const val DONE = "done"
        private val BUTTON_SETTINGS = ComponentName("com.agui.shortcutsettings", "com.agui.shortcutsettings.ui.EntryAppActivity")
        private var listening: WeakReference<OnboardingActivity>? = null

        fun isDone(context: Context) = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(DONE, false)

        /** Hands an action button press to onboarding while Puff is waiting for one. Returns whether it took it. */
        fun buttonPressed(): Boolean {
            val activity = listening?.get()?.takeIf { it.waitingForButton && !it.isFinishing } ?: return false
            activity.onButtonPressed()
            return true
        }
    }
}
