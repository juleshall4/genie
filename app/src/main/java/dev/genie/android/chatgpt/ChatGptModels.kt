package dev.genie.android.chatgpt

import dev.genie.android.R
import org.json.JSONArray
import org.json.JSONObject

data class ReasoningLevel(val effort: String, val description: String)

data class ChatGptModel(
    val slug: String,
    val displayName: String,
    val defaultEffort: String?,
    val reasoningLevels: List<ReasoningLevel>
) {
    val preferredEffort: String?
        get() = if (reasoningLevels.any { it.effort == "low" }) "low" else defaultEffort

    fun selectReasoning(effort: String?): ChatGptModelSelection {
        if (effort != null && reasoningLevels.none { it.effort == effort }) {
            throw ChatGptFailure(R.string.chatgpt_reasoning_unavailable)
        }
        return ChatGptModelSelection(this, effort ?: preferredEffort)
    }
}

data class ChatGptModelSelection(val model: ChatGptModel, val effort: String?) {
    fun requestBody(turns: List<ChatTurn>, personality: String = ChatGptSettings.DEFAULT_PERSONALITY): JSONObject {
        val input = JSONArray(turns.map { JSONObject().put("role", it.role).put("content", it.text) })
        return JSONObject().put("model", model.slug).put("input", input)
            .put("instructions", personality)
            .put("store", false).put("stream", true).apply {
                if (effort != null) put("reasoning", JSONObject().put("effort", effort))
            }
    }
}

class ChatGptModels(val models: List<ChatGptModel>) {
    fun selectModel(slug: String?): ChatGptModel = if (slug == null) {
        models.firstOrNull { it.slug == ChatGptSettings.DEFAULT_MODEL }
            ?: models.firstOrNull() ?: throw ChatGptFailure(R.string.chatgpt_no_models)
    } else {
        models.firstOrNull { it.slug == slug } ?: throw ChatGptFailure(R.string.chatgpt_model_unavailable)
    }

    companion object {
        // "ultra" is advertised by some catalogs but isn't a documented direct Responses effort.
        private val directEfforts = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun parse(json: JSONObject): ChatGptModels {
            val array = json.getJSONArray("models")
            val models = mutableListOf<ChatGptModel>()
            for (index in 0 until array.length()) {
                val model = array.getJSONObject(index)
                if (model.optString("visibility") != "list") continue
                val slug = model.getString("slug")
                val levels = model.optJSONArray("supported_reasoning_levels") ?: JSONArray()
                val reasoning = (0 until levels.length()).map { levels.getJSONObject(it) }
                    .filter { it.optString("effort") in directEfforts }
                    .map { ReasoningLevel(it.getString("effort"), it.optString("description")) }
                    .distinctBy { it.effort }
                val default = model.optString("default_reasoning_level").takeIf { value -> reasoning.any { it.effort == value } }
                models.add(ChatGptModel(slug, model.optString("display_name").ifBlank { slug }, default, reasoning))
            }
            return ChatGptModels(models)
        }
    }
}
