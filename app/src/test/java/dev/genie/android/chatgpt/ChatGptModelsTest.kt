package dev.genie.android.chatgpt

import dev.genie.android.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptModelsTest {
    @Test fun lunaKeepsTheAccountsAdvertisedReasoningFloor() {
        val catalog = ChatGptModels.parse(JSONObject("""{"models":[{
            "slug":"gpt-6-luna","visibility":"list","default_reasoning_level":"low",
            "supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"}]
        }]}"""))
        val model = catalog.selectModel("gpt-6-luna")
        assertThrows(ChatGptFailure::class.java) { model.selectReasoning("none") }
        assertEquals("low", model.selectReasoning(null).effort)
    }

    private fun catalog() = ChatGptModels.parse(JSONObject("""
        {"models":[
          {"slug":"hidden","visibility":"hide"},
          {"slug":"model-a","display_name":"Model A","visibility":"list","default_reasoning_level":"low",
           "supported_reasoning_levels":[{"effort":"low","description":"Quick"},{"effort":"medium"},{"effort":"ultra"}]},
          {"slug":"model-b","display_name":"Model B","visibility":"list","default_reasoning_level":"none",
           "supported_reasoning_levels":[{"effort":"none"},{"effort":"low"}]}
        ]}
    """.trimIndent()))

    @Test fun pickerOnlyIncludesVisibleModelsAndSupportedDirectApiReasoning() {
        val catalog = catalog()
        assertEquals(listOf("model-a", "model-b"), catalog.models.map { it.slug })
        assertEquals(listOf("low", "medium"), catalog.models.first().reasoningLevels.map { it.effort })
        assertEquals(listOf("none", "low"), catalog.models.last().reasoningLevels.map { it.effort })
    }

    @Test fun selectedModelAndEffortAreSerializedIntoTheActualResponseBody() {
        val selection = catalog().selectModel("model-b").selectReasoning("none")
        val body = selection.requestBody(listOf(ChatTurn("user", "Question"), ChatTurn("assistant", "Answer")))
        assertEquals("model-b", body.getString("model"))
        assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("Question", body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals("assistant", body.getJSONArray("input").getJSONObject(1).getString("role"))
    }

    @Test fun automaticSelectionUsesServerOrderAndTheModelsDefaultEffort() {
        val choice = catalog().selectModel(null).selectReasoning(null)
        assertEquals("model-a", choice.model.slug)
        assertEquals("low", choice.effort)
    }

    @Test fun unavailableSavedModelRequiresUserChoiceInsteadOfSilentSubstitution() {
        val error = assertThrows(ChatGptFailure::class.java) { catalog().selectModel("removed-model") }
        assertEquals(R.string.chatgpt_model_unavailable, error.messageResource)
    }

    @Test fun unsupportedEffortIsRejectedForTheSelectedModel() {
        val error = assertThrows(ChatGptFailure::class.java) { catalog().selectModel("model-b").selectReasoning("medium") }
        assertEquals(R.string.chatgpt_reasoning_unavailable, error.messageResource)
    }

    @Test fun modelsWithoutReasoningSupportOmitTheReasoningParameter() {
        val catalog = ChatGptModels.parse(JSONObject("""{"models":[{"slug":"simple","visibility":"list"}]}"""))
        assertFalse(catalog.selectModel(null).selectReasoning(null).requestBody(listOf(ChatTurn("user", "Hello"))).has("reasoning"))
    }
    @Test fun defaultPrefersLunaLowEvenWhenServerOrderAndDefaultsDiffer() {
        val catalog = ChatGptModels.parse(JSONObject("""{"models":[
          {"slug":"other","visibility":"list"},
          {"slug":"gpt-6-luna","visibility":"list","default_reasoning_level":"medium",
           "supported_reasoning_levels":[{"effort":"low"},{"effort":"medium"}]}
        ]}"""))
        val selection = catalog.selectModel(null).selectReasoning(null)
        val body = selection.requestBody(listOf(ChatTurn("user", "Hello")))
        assertEquals("gpt-6-luna", body.getString("model"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("medium", catalog.selectModel(null).selectReasoning("medium").effort)
    }

    @Test fun personalityIsSentAsInstructionsWithoutChangingTheQuestion() {
        val selection = catalog().selectModel(null).selectReasoning(null)
        val prompt = "Be warm and playful.\nUse short answers, with \"examples\"."
        val body = selection.requestBody(listOf(ChatTurn("user", "Hello")), prompt)
        assertEquals(prompt, body.getString("instructions"))
        assertEquals("Hello", body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(ChatGptSettings.DEFAULT_PERSONALITY,
            selection.requestBody(listOf(ChatTurn("user", "Hello"))).getString("instructions"))
    }

}
