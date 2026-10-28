package dev.genie.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class GenieOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var popup: View? = null
    private var speechInput: NativeSpeechInput? = null
    private var generation = 0

    val isShowing: Boolean
        get() = popup != null

    fun toggle() {
        if (isShowing) hide() else show()
    }

    fun hide() {
        generation++
        speechInput?.close()
        speechInput = null
        popup?.let(windowManager::removeView)
        popup = null
    }

    private fun show() {
        generation++
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_genie, null)
        view.findViewById<View>(R.id.close_overlay).setOnClickListener { hide() }
        view.findViewById<TextView>(R.id.speech_transcript).movementMethod = ScrollingMovementMethod()
        view.findViewById<Button>(R.id.speech_action).setOnClickListener {
            if (hasMicrophonePermission()) startListening(view) else requestMicrophonePermission()
        }
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
        if (hasMicrophonePermission()) {
            startListening(view)
        } else {
            showPermissionNeeded(view)
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening(view: View) {
        speechInput?.close()
        view.findViewById<TextView>(R.id.speech_transcript).apply {
            text = ""
            visibility = View.GONE
            scrollTo(0, 0)
        }
        speechInput = NativeSpeechInput(context) { status, transcript, finished ->
            if (popup === view) {
                view.findViewById<TextView>(R.id.speech_status).setText(status)
                transcript?.let {
                    view.findViewById<TextView>(R.id.speech_transcript).apply {
                        text = it
                        visibility = View.VISIBLE
                    }
                }
                view.findViewById<Button>(R.id.speech_action).apply {
                    setText(if (hasMicrophonePermission()) R.string.speech_retry else R.string.enable_microphone)
                    visibility = if (finished) View.VISIBLE else View.GONE
                }
            }
        }.also { it.start() }
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
}
