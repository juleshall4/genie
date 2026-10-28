package dev.genie.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.main_root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<Button>(R.id.shortcut_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.microphone_settings).setOnClickListener {
            MicrophonePermissionActivity.launch(this)
        }
    }

    override fun onResume() {
        super.onResume()
        val manager = getSystemService(AccessibilityManager::class.java)
        val component = ComponentName(this, GenieShortcutService::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { service ->
            val info = service.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == component
        }

        findViewById<TextView>(R.id.shortcut_status).setText(
            if (enabled) R.string.shortcut_enabled else R.string.shortcut_disabled
        )
        findViewById<Button>(R.id.shortcut_settings).setText(
            if (enabled) R.string.manage_shortcut else R.string.enable_shortcut
        )
        findViewById<Button>(R.id.microphone_settings).apply {
            visibility = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }
    }
}
