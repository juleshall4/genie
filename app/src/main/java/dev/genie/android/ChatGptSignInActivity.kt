package dev.genie.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient

class ChatGptSignInActivity : Activity() {
    private lateinit var task: ChatGptClient.BrowserSignIn
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var delivered = false
    private var welcomeShowing = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(newBase.fixedScaleConfiguration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = ChatGptClient.get(this)
        if (intent.action == Intent.ACTION_VIEW && client.signIn == null) {
            finish()
            return
        }
        status = TextView(this).apply {
            setText(R.string.chatgpt_browser_help)
            textSize = 18f
        }
        retry = Button(this).apply {
            setText(R.string.chatgpt_continue)
            setOnClickListener {
                task = client.beginSignIn(intent.getStringExtra(EXTRA_CLIENT_ID))
                task.listener = ::render
                render()
            }
        }
        val cancel = Button(this).apply {
            setText(android.R.string.cancel)
            setOnClickListener { finish() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 3, padding, padding)
            addView(status)
            addView(retry)
            addView(cancel)
        })
        task = client.signIn?.takeIf { !it.finished || intent.action == Intent.ACTION_VIEW }
            ?: client.beginSignIn(intent.getStringExtra(EXTRA_CLIENT_ID))
    }

    override fun onStart() {
        super.onStart()
        if (::task.isInitialized) {
            task.listener = ::render
            render()
        }
    }

    override fun onStop() {
        if (::task.isInitialized) task.listener = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != Intent.ACTION_VIEW) setIntent(intent)
        if (::task.isInitialized) render()
    }

    private fun render() {
        if (isFinishing || welcomeShowing) return
        retry.visibility = if (task.finished && task.error != null) android.view.View.VISIBLE else android.view.View.GONE
        if (task.finished) {
            val error = task.error
            if (error != null) status.setText(error)
            else if (ChatGptClient.get(this).store.firstWelcome()) {
                welcomeShowing = true
                AlertDialog.Builder(this).setTitle(R.string.chatgpt_welcome_title)
                    .setMessage(R.string.chatgpt_plan_explanation)
                    .setPositiveButton(R.string.chatgpt_got_it) { _, _ -> complete() }
                    .setOnCancelListener { complete() }.show()
            } else complete()
            return
        }
        val url = task.url
        if (url != null && !task.browserOpened) {
            task.browserOpened = true
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: android.content.ActivityNotFoundException) {
                task.cancel()
                status.setText(R.string.chatgpt_browser_unavailable)
                retry.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun complete() {
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        receiver?.send(RESULT_OK, null)
        delivered = true
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && ::task.isInitialized) {
            task.cancel()
            if (!delivered) {
                @Suppress("DEPRECATION")
                val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
                receiver?.send(RESULT_CANCELED, null)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RESULT_RECEIVER = "dev.genie.android.CHATGPT_RESULT"
        private const val EXTRA_CLIENT_ID = "dev.genie.android.CHATGPT_CLIENT_ID"

        fun launch(context: Context, clientId: String? = null, receiver: ResultReceiver? = null) {
            context.startActivity(Intent(context, ChatGptSignInActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_CLIENT_ID, clientId)
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            })
        }
    }
}
