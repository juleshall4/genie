package dev.genie.android.chatgpt

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly opted-in smoke check; never prints credentials or runs during unit tests. */
class ChatGptLiveCheck : Instrumentation() {
    private var settingsOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        settingsOnly = arguments?.getString("settings_only") == "true"
        if (!settingsOnly && arguments?.getString("live_chatgpt") != "true") {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Skipped: live_chatgpt=true is required.") })
        } else start()
    }

    override fun onStart() {
        if (settingsOnly) {
            checkSettingsPersistence()
            return
        }
        val client = ChatGptClient.get(targetContext)
        var answer = ""
        client.answer(listOf(ChatTurn("user", "Reply with exactly: Hello from Genie.")),
            onText = { answer = it }, onDone = { error ->
                if (error != null) {
                    finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", targetContext.getString(error)) })
                } else Thread {
                    try {
                        val account = requireNotNull(client.store.active())
                        val request = Request.Builder().url("https://api.openai.com/v1/models")
                            .header("Authorization", "Bearer ${account.accessToken}").build()
                        val catalog = OkHttpClient.Builder().followRedirects(false).build().newCall(request).execute().use {
                            check(it.isSuccessful)
                            JSONObject(it.body!!.string()).getJSONArray("models")
                        }
                        val models = JSONArray()
                        for (index in 0 until catalog.length()) {
                            val model = catalog.getJSONObject(index)
                            if (model.optString("visibility") != "list") continue
                            val summary = JSONObject().put("slug", model.getString("slug"))
                                .put("display_name", model.optString("display_name"))
                            for (key in model.keys()) {
                                if (key.contains("reasoning", ignoreCase = true)) summary.put(key, model.get(key))
                            }
                            models.put(summary)
                        }
                        val selected = client.settings.selection(account.clientId, ChatGptModels.parse(JSONObject().put("models", catalog)))
                        finish(Activity.RESULT_OK, Bundle().apply {
                            putString("stream", "Completed live response: $answer\nSelected model: ${selected.model.slug}; reasoning: ${selected.effort}\nAvailable models: $models\n")
                        })
                    } catch (failure: Exception) {
                        finish(Activity.RESULT_CANCELED, Bundle().apply {
                            putString("stream", "Live check failed: ${failure.javaClass.simpleName}")
                        })
                    }
                }.start()
            })
    }

    private fun checkSettingsPersistence() {
        val name = "genie_settings_check_${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(ignored: String, mode: Int) = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            val settings = ChatGptSettings(context)
            settings.setModel("account-a", "model-a")
            settings.setReasoning("account-a", "model-a", "medium")
            settings.setReasoning("account-a", "model-b", "low")
            settings.setModel("account-b", "model-b")
            val restored = ChatGptSettings(context)
            check(restored.model("account-a") == "model-a")
            check(restored.model("account-b") == "model-b")
            check(restored.reasoning("account-a", "model-a") == "medium")
            check(restored.reasoning("account-a", "model-b") == "low")
            check(restored.reasoning("account-b", "model-a") == null)
            restored.setModel("account-a", null)
            restored.setReasoning("account-a", "model-a", null)
            check(settings.model("account-a") == null && settings.reasoning("account-a", "model-a") == null)
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS: settings persist, remain scoped by account and model, and reset to defaults.") })
        } catch (failure: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Settings check failed: ${failure.javaClass.simpleName}") })
        } finally {
            targetContext.deleteSharedPreferences(name)
        }
    }
}
