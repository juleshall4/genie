package dev.genie.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point for a device's programmable action button. */
class ActivateGenieActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !GenieShortcutService.requestActivation()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
