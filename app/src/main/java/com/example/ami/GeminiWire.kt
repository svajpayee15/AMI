package com.example.ami

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The `generateContent` wire format, kept apart from [AmiPlannerManager] so it can be
 * tested without a network or an API key.
 *
 * This is the part of the OpenRouter-to-Gemini move most likely to be quietly wrong, and
 * wrong here fails in a way that is hard to read: a rejected request looks identical to a
 * flat network failure from the call site, because every caller falls back to a scripted
 * path on null. Each of the three differences called out in [AmiPlannerManager]'s comment
 * has a test against it.
 */
internal object GeminiWire {

    /**
     * The system prompt as `system_instruction`, the turns as `contents`, thinking off.
     */
    fun requestBody(systemPrompt: String, turns: List<AmiPlannerManager.Turn>): JsonObject =
        JsonObject().apply {
            add(
                "system_instruction",
                JsonObject().apply { add("parts", partsOf(systemPrompt)) }
            )
            add("contents", contentsOf(turns))
            add(
                "generationConfig",
                JsonObject().apply {
                    // 0 disables thinking on 2.5 Flash. Every prompt in this app wants one
                    // short structured line, and thinking tokens are drawn from the same
                    // output budget as the reply - left on, a reply can come back empty
                    // with finishReason MAX_TOKENS.
                    add("thinkingConfig", JsonObject().apply { addProperty("thinkingBudget", 0) })
                }
            )
        }

    /**
     * Gemini has no "assistant" and no "system" inside `contents`; the model's own turns
     * are role "model" and everything else is "user".
     */
    fun contentsOf(turns: List<AmiPlannerManager.Turn>): JsonArray = JsonArray().apply {
        turns.forEach { turn ->
            add(
                JsonObject().apply {
                    addProperty("role", if (turn.role == AmiPlannerManager.Turn.ROLE_ASSISTANT) "model" else "user")
                    add("parts", partsOf(turn.content))
                }
            )
        }
    }

    fun partsOf(text: String): JsonArray = JsonArray().apply {
        add(JsonObject().apply { addProperty("text", text) })
    }

    /**
     * Google's errors are `{"error": {"code":..., "message": "...", "status": "..."}}`,
     * pretty-printed by default but not always, so both spacings are tried before giving
     * up and reporting the status code on its own.
     */
    fun messageFrom(errorBody: String, code: Int): String =
        errorBody.substringAfter("\"message\": \"", "")
            .ifBlank { errorBody.substringAfter("\"message\":\"", "") }
            .substringBefore("\"")
            .ifBlank { "Brain Error $code" }
}
