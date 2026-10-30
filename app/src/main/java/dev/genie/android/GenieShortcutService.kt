package dev.genie.android

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class GenieShortcutService : AccessibilityService() {
    private lateinit var overlay: GenieOverlay
    private val capturedKeys = mutableSetOf<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        overlay = GenieOverlay(this)
        connectedService = this
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::overlay.isInitialized) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return event.keyCode in capturedKeys

                when {
                    emulatorSpaceShortcut && event.keyCode == KeyEvent.KEYCODE_SPACE && event.hasNoModifiers() && !editingPersonality -> {
                        capturedKeys.add(event.keyCode)
                        mainHandler.post { overlay.activate() }
                        return true
                    }
                    overlay.isShowing && event.keyCode in dismissKeys -> {
                        capturedKeys.add(event.keyCode)
                        mainHandler.post { overlay.hide() }
                        return true
                    }
                }
            }
            KeyEvent.ACTION_UP -> return capturedKeys.remove(event.keyCode)
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        capturedKeys.clear()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connectedService === this) connectedService = null
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        capturedKeys.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    companion object {
        private var connectedService: GenieShortcutService? = null
        private val emulatorSpaceShortcut = Build.HARDWARE in setOf("ranchu", "goldfish")
        @Volatile var editingPersonality = false
        private val dismissKeys = setOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK)

        fun requestActivation(): Boolean {
            val service = connectedService ?: return false
            service.mainHandler.post { service.overlay.activate() }
            return true
        }
    }
}
