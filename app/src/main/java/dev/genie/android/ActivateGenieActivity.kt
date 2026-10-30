package dev.genie.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Entry point for a device's programmable action button. */
class ActivateGenieActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While onboarding waits for a test press, the press goes to Puff instead of opening Genie.
        if (savedInstanceState == null && !OnboardingActivity.buttonPressed() && !GenieShortcutService.requestActivation()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
