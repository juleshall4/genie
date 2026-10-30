package dev.genie.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import dev.genie.android.chatgpt.ChatGptClient
import dev.genie.android.chatgpt.ChatGptFailure
import dev.genie.android.chatgpt.ChatGptModels
import dev.genie.android.chatgpt.ChatGptRequest
import java.util.Locale

class MainActivity : Activity() {
    private var modelRequest: ChatGptRequest? = null
    private var modelCatalog: ChatGptModels? = null
    private var modelAccountId: String? = null
    private var voicePreview: VoicePreviewOutput? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(newBase.fixedScaleConfiguration())
    }

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
        findViewById<Button>(R.id.chatgpt_settings).setOnClickListener { manageChatGpt() }
        findViewById<Button>(R.id.chatgpt_usage).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
        }
        findViewById<Button>(R.id.chatgpt_model).setOnClickListener { chooseModel() }
        findViewById<Button>(R.id.chatgpt_reasoning).setOnClickListener { chooseReasoning() }
        val personalitySettings = ChatGptClient.get(this).settings
        findViewById<android.widget.EditText>(R.id.chatgpt_personality).apply {
            setText(personalitySettings.personality)
            setOnFocusChangeListener { _, focused -> GenieShortcutService.editingPersonality = focused }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    personalitySettings.personality = text?.toString().orEmpty()
                }
                override fun afterTextChanged(text: android.text.Editable?) = Unit
            })
        }
        findViewById<Button>(R.id.chatgpt_reset_personality).setOnClickListener {
            findViewById<android.widget.EditText>(R.id.chatgpt_personality).setText(
                dev.genie.android.chatgpt.ChatGptSettings.DEFAULT_PERSONALITY)
        }
        findViewById<Button>(R.id.chatgpt_refresh_models).setOnClickListener { loadModels() }
        findViewById<Button>(R.id.read_aloud_settings).setOnClickListener { configureSpeech() }
        findViewById<Button>(R.id.read_aloud_voice).setOnClickListener {
            val settings = ChirpSettings(this)
            AlertDialog.Builder(this).setTitle(R.string.read_aloud_voice)
                .setSingleChoiceItems(ChirpSettings.VOICES.map {
                    getString(R.string.read_aloud_voice_option, voiceLabel(it),
                        getString(if (it in ChirpSettings.MALE_VOICES) R.string.voice_male else R.string.voice_female))
                }.toTypedArray(),
                    ChirpSettings.VOICES.indexOf(settings.voice)) { dialog, index ->
                    voicePreview?.stop()
                    settings.voice = ChirpSettings.VOICES[index]
                    renderSpeech()
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<Button>(R.id.read_aloud_speed).setOnClickListener {
            val settings = ChirpSettings(this)
            AlertDialog.Builder(this).setTitle(R.string.read_aloud_speed)
                .setSingleChoiceItems(ChirpSettings.SPEAKING_RATES.map {
                    getString(R.string.read_aloud_speed_option, it)
                }.toTypedArray(), ChirpSettings.SPEAKING_RATES.indexOf(settings.speakingRate)) { dialog, index ->
                    voicePreview?.stop()
                    settings.speakingRate = ChirpSettings.SPEAKING_RATES[index]
                    renderSpeech()
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<android.widget.Switch>(R.id.read_aloud_enabled).apply {
            isChecked = ChirpSettings(this@MainActivity).enabled
            setOnCheckedChangeListener { _, enabled ->
                ChirpSettings(this@MainActivity).enabled = enabled
                if (!enabled) voicePreview?.stop()
            }
        }
        findViewById<Button>(R.id.read_aloud_test).setOnClickListener {
            voicePreview?.close()
            voicePreview = VoicePreviewOutput(this) { status ->
                findViewById<TextView>(R.id.read_aloud_status).setText(status)
                renderSpeech()
            }.also {
                val settings = ChirpSettings(this)
                it.play(settings.voice, settings.speakingRate)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GenieShortcutService.editingPersonality = findViewById<android.widget.EditText>(R.id.chatgpt_personality).hasFocus()
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
        renderChatGpt()
        renderSpeech()
    }

    private fun renderSpeech() {
        val settings = ChirpSettings(this)
        val usage = settings.usage()
        findViewById<TextView>(R.id.read_aloud_usage).text = getString(
            R.string.read_aloud_usage_value, usage.month, usage.characters, ChirpSettings.MONTHLY_LIMIT)
        findViewById<android.widget.ProgressBar>(R.id.read_aloud_progress).apply {
            max = ChirpSettings.MONTHLY_LIMIT.toInt()
            progress = usage.characters.coerceAtMost(ChirpSettings.MONTHLY_LIMIT).toInt()
        }
        findViewById<Button>(R.id.read_aloud_speed).text = getString(
            R.string.read_aloud_speed_value, settings.speakingRate)
        findViewById<Button>(R.id.read_aloud_voice).text = getString(
            R.string.read_aloud_voice_value, voiceLabel(settings.voice))
    }

    private fun voiceLabel(voice: String): String = getString(
        R.string.read_aloud_voice_label, voice.substringAfterLast('-'),
        getString(if (voice.startsWith("en-US-")) R.string.voice_american else R.string.voice_british))

    private fun configurationStatus(message: Int?) {
        findViewById<TextView>(R.id.chatgpt_configuration_status).apply {
            visibility = if (message == null) android.view.View.GONE else android.view.View.VISIBLE
            if (message != null) setText(message)
        }
    }

    private fun configureSpeech() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.read_aloud_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.read_aloud_settings)
            .setMessage(R.string.read_aloud_key_help).setView(input)
            .setPositiveButton(R.string.read_aloud_save, null)
            .setNeutralButton(R.string.read_aloud_remove_key) { _, _ ->
                voicePreview?.stop()
                ChirpSettings(this).apiKey = ""
                findViewById<TextView>(R.id.read_aloud_status).setText(R.string.read_aloud_setup_needed)
            }.setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text.toString().trim()
                if (key.isBlank() || key.any { it.isWhitespace() }) {
                    input.error = getString(R.string.read_aloud_key_hint)
                } else {
                    try {
                        ChirpSettings(this).apiKey = key
                        findViewById<TextView>(R.id.read_aloud_status).setText(R.string.read_aloud_key_saved)
                        dialog.dismiss()
                    } catch (_: Exception) {
                        input.error = getString(R.string.read_aloud_key_save_failed)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun renderChatGpt() {
        val account = ChatGptClient.get(this).store.active()
        findViewById<TextView>(R.id.chatgpt_status).text = if (account?.connected == true) {
            getString(R.string.chatgpt_account_connected, account.email.ifBlank { getString(R.string.chatgpt_account) })
        } else getString(R.string.chatgpt_connect_needed)
        findViewById<Button>(R.id.chatgpt_settings).setText(
            if (account?.connected == true) R.string.chatgpt_manage_accounts else R.string.chatgpt_continue
        )
        loadModels()
    }

    override fun onStop() {
        GenieShortcutService.editingPersonality = false
        voicePreview?.close()
        voicePreview = null
        modelRequest?.cancel()
        super.onStop()
    }

    private fun loadModels() {
        modelRequest?.cancel()
        modelCatalog = null
        val client = ChatGptClient.get(this)
        val account = client.store.active()?.takeIf { it.connected }
        modelAccountId = account?.clientId
        findViewById<Button>(R.id.chatgpt_model).apply {
            isEnabled = false
            setText(R.string.chatgpt_choose_model)
        }
        findViewById<Button>(R.id.chatgpt_reasoning).apply {
            isEnabled = false
            setText(R.string.chatgpt_choose_reasoning)
        }
        findViewById<Button>(R.id.chatgpt_refresh_models).isEnabled = account != null
        configurationStatus(if (account == null) null else R.string.chatgpt_loading_models)
        if (account == null) return
        modelRequest = client.models { catalog, error ->
            if (client.store.active()?.clientId == account.clientId && !isFinishing) {
                modelCatalog = catalog
                if (error != null) configurationStatus(error)
                else renderConfiguration()
            }
        }
    }

    private fun renderConfiguration() {
        val catalog = modelCatalog ?: return
        val account = modelAccountId ?: return
        val settings = ChatGptClient.get(this).settings
        val savedModel = settings.model(account)
        findViewById<Button>(R.id.chatgpt_model).isEnabled = catalog.models.isNotEmpty()
        configurationStatus(null)
        val model = try {
            catalog.selectModel(savedModel)
        } catch (failure: ChatGptFailure) {
            findViewById<Button>(R.id.chatgpt_model).setText(R.string.chatgpt_model_unavailable)
            findViewById<Button>(R.id.chatgpt_reasoning).isEnabled = false
            configurationStatus(failure.messageResource)
            return
        }
        val modelName = if (savedModel == null) getString(R.string.chatgpt_automatic_model, model.displayName) else model.displayName
        findViewById<Button>(R.id.chatgpt_model).text = getString(R.string.chatgpt_model_value, modelName)
        val effort = settings.reasoning(account, model.slug)
        findViewById<Button>(R.id.chatgpt_reasoning).apply {
            isEnabled = model.reasoningLevels.isNotEmpty() || effort != null
            text = getString(R.string.chatgpt_reasoning_value, if (effort == null) defaultReasoning(model.preferredEffort) else effortLabel(effort))
        }
        try {
            model.selectReasoning(effort)
        } catch (failure: ChatGptFailure) {
            configurationStatus(failure.messageResource)
        }
    }

    private fun chooseModel() {
        val catalog = modelCatalog ?: return
        val account = modelAccountId ?: return
        if (catalog.models.isEmpty()) return
        val settings = ChatGptClient.get(this).settings
        val selected = settings.model(account)
        val labels = listOf(getString(R.string.chatgpt_automatic_model, catalog.selectModel(null).displayName)) +
            catalog.models.map { it.displayName }
        val checked = if (selected == null) 0 else catalog.models.indexOfFirst { it.slug == selected }.let { if (it < 0) -1 else it + 1 }
        AlertDialog.Builder(this).setTitle(R.string.chatgpt_choose_model)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
                if (ChatGptClient.get(this).store.active()?.clientId == account) {
                    settings.setModel(account, if (index == 0) null else catalog.models[index - 1].slug)
                    renderConfiguration()
                }
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseReasoning() {
        val catalog = modelCatalog ?: return
        val account = modelAccountId ?: return
        val settings = ChatGptClient.get(this).settings
        val model = catalog.selectModel(settings.model(account))
        val selected = settings.reasoning(account, model.slug)
        val labels = listOf(defaultReasoning(model.preferredEffort)) + model.reasoningLevels.map {
            effortLabel(it.effort) + if (it.description.isBlank()) "" else " — ${it.description}"
        }
        val checked = if (selected == null) 0 else model.reasoningLevels.indexOfFirst { it.effort == selected }.let { if (it < 0) -1 else it + 1 }
        AlertDialog.Builder(this).setTitle(R.string.chatgpt_choose_reasoning)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
                if (ChatGptClient.get(this).store.active()?.clientId == account) {
                    settings.setReasoning(account, model.slug, if (index == 0) null else model.reasoningLevels[index - 1].effort)
                    renderConfiguration()
                }
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun defaultReasoning(effort: String?): String = if (effort == null) getString(R.string.chatgpt_model_default)
        else getString(R.string.chatgpt_default_reasoning, effortLabel(effort))

    private fun effortLabel(effort: String) = when (effort) {
        "xhigh" -> getString(R.string.chatgpt_reasoning_extra_high)
        else -> effort.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun manageChatGpt() {
        val client = ChatGptClient.get(this)
        val accounts = client.store.accounts()
        if (accounts.isEmpty()) {
            ChatGptSignInActivity.launch(this)
            return
        }
        val labels = accounts.map { "${it.email.ifBlank { getString(R.string.chatgpt_account) }} · ${it.clientId.takeLast(6)}" } +
            getString(R.string.chatgpt_add_account) + getString(R.string.chatgpt_sign_out)
        AlertDialog.Builder(this).setTitle(R.string.chatgpt_manage_accounts).setItems(labels.toTypedArray()) { _, index ->
            when {
                index < accounts.size -> {
                    val account = accounts[index]
                    if (account.connected) { client.select(account.clientId); renderChatGpt() }
                    else ChatGptSignInActivity.launch(this, account.clientId)
                }
                index == accounts.size -> ChatGptSignInActivity.launch(this)
                else -> {
                    client.signOut { revoked ->
                        renderChatGpt()
                        if (!revoked) AlertDialog.Builder(this).setMessage(R.string.chatgpt_revocation_unconfirmed)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                    renderChatGpt()
                }
            }
        }.show()
    }
}
