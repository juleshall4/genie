package dev.genie.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
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
        findViewById<Button>(R.id.chatgpt_refresh_models).setOnClickListener { loadModels() }
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
        renderChatGpt()
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
        findViewById<TextView>(R.id.chatgpt_configuration_status).setText(
            if (account == null) R.string.chatgpt_connect_needed else R.string.chatgpt_loading_models
        )
        if (account == null) return
        modelRequest = client.models { catalog, error ->
            if (client.store.active()?.clientId == account.clientId && !isFinishing) {
                modelCatalog = catalog
                if (error != null) findViewById<TextView>(R.id.chatgpt_configuration_status).setText(error)
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
        findViewById<TextView>(R.id.chatgpt_configuration_status).setText(R.string.chatgpt_configuration_help)
        val model = try {
            catalog.selectModel(savedModel)
        } catch (failure: ChatGptFailure) {
            findViewById<Button>(R.id.chatgpt_model).setText(R.string.chatgpt_model_unavailable)
            findViewById<Button>(R.id.chatgpt_reasoning).isEnabled = false
            findViewById<TextView>(R.id.chatgpt_configuration_status).setText(failure.messageResource)
            return
        }
        val modelName = if (savedModel == null) getString(R.string.chatgpt_automatic_model, model.displayName) else model.displayName
        findViewById<Button>(R.id.chatgpt_model).text = getString(R.string.chatgpt_model_value, modelName)
        val effort = settings.reasoning(account, model.slug)
        findViewById<Button>(R.id.chatgpt_reasoning).apply {
            isEnabled = model.reasoningLevels.isNotEmpty() || effort != null
            text = getString(R.string.chatgpt_reasoning_value, if (effort == null) defaultReasoning(model.defaultEffort) else effortLabel(effort))
        }
        try {
            model.selectReasoning(effort)
        } catch (failure: ChatGptFailure) {
            findViewById<TextView>(R.id.chatgpt_configuration_status).setText(failure.messageResource)
        }
    }

    private fun chooseModel() {
        val catalog = modelCatalog ?: return
        val account = modelAccountId ?: return
        if (catalog.models.isEmpty()) return
        val settings = ChatGptClient.get(this).settings
        val selected = settings.model(account)
        val labels = listOf(getString(R.string.chatgpt_automatic_model, catalog.models.first().displayName)) +
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
        val labels = listOf(defaultReasoning(model.defaultEffort)) + model.reasoningLevels.map {
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
