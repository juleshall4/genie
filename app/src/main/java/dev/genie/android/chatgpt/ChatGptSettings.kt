package dev.genie.android.chatgpt

import android.content.Context

class ChatGptSettings(context: Context) {
    private val preferences = context.getSharedPreferences("chatgpt_model_settings", Context.MODE_PRIVATE)

    fun model(clientId: String): String? = preferences.getString("$clientId.model", null)

    fun reasoning(clientId: String, model: String): String? = preferences.getString("$clientId.$model.reasoning", null)

    fun setModel(clientId: String, model: String?) {
        check(preferences.edit().putString("$clientId.model", model).commit())
    }

    fun setReasoning(clientId: String, model: String, effort: String?) {
        check(preferences.edit().putString("$clientId.$model.reasoning", effort).commit())
    }

    fun selection(clientId: String, catalog: ChatGptModels): ChatGptModelSelection {
        val model = catalog.selectModel(model(clientId))
        return model.selectReasoning(reasoning(clientId, model.slug))
    }
}
