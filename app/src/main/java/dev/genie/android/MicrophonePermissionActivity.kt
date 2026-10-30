package dev.genie.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.provider.Settings

class MicrophonePermissionActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(newBase.fixedScaleConfiguration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            complete()
            return
        }

        val preferences = getSharedPreferences("microphone_permission", MODE_PRIVATE)
        if (preferences.getBoolean("requested", false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                REQUEST_MICROPHONE
            )
        } else {
            preferences.edit().putBoolean("requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) complete()
    }

    @Deprecated("Uses the platform Activity result callback without an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MICROPHONE) complete()
    }

    private fun complete() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        receiver?.send(if (granted) RESULT_OK else RESULT_CANCELED, null)
        finish()
    }

    companion object {
        private const val REQUEST_MICROPHONE = 1
        private const val EXTRA_RESULT_RECEIVER = "dev.genie.android.MICROPHONE_RESULT"

        fun launch(context: Context, receiver: ResultReceiver? = null) {
            context.startActivity(Intent(context, MicrophonePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_RESULT_RECEIVER, receiver)
            })
        }
    }
}
