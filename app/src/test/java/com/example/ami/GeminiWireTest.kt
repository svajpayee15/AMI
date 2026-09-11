package com.example.ami

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the OpenRouter-to-Gemini move.
 *
 * Every one of these covers a difference that fails *silently* if it is wrong: the call
 * sites all fall back to a scripted path when the model returns null, so a malformed
 * request is indistinguishable from a flat network failure until someone reads a log.
 */
class GeminiWireTest {

    private val gson = Gson()

    private fun body(systemPrompt: String, vararg turns: AmiPlannerManager.Turn) =
        GeminiWire.requestBody(systemPrompt, turns.toList())

    // --- The system prompt is not a message -------------------------------------------

    @Test
    fun `the system prompt goes in system_instruction, not contents`() {
        val json = body("you are a nurse", AmiPlannerManager.Turn.user("hello"))

        val instruction = json.getAsJsonObject("system_instruction")
        assertNotNull(instruction)
        assertEquals(
            "you are a nurse",
            instruction.getAsJsonArray("parts")[0].asJsonObject.get("text").asString
        )

        // And it must not also appear as a turn.
        val contents = json.getAsJsonArray("contents")
        assertEquals(1, contents.size())
        assertEquals("hello", contents[0].asJsonObject.getAsJsonArray("parts")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `no content is ever sent with role system`() {
        // Gemini rejects the whole request rather than ignoring the unknown role.
        val json = body(
            "system text",
            AmiPlannerManager.Turn.user("a"),
            AmiPlannerManager.Turn.assistant("b")
        )
        val roles = json.getAsJsonArray("contents").map { it.asJsonObject.get("role").asString }
        assertFalse(roles.contains("system"))
    }

    // --- assistant becomes model -------------------------------------------------------

    @Test
    fun `an assistant turn is sent as role model`() {
        val json = body(
            "s",
            AmiPlannerManager.Turn.user("how are you"),
            AmiPlannerManager.Turn.assistant("SAY: I am well"),
            AmiPlannerManager.Turn.user("good")
        )
        val roles = json.getAsJsonArray("contents").map { it.asJsonObject.get("role").asString }
        assertEquals(listOf("user", "model", "user"), roles)
    }

    @Test
    fun `turn order is preserved`() {
        val json = body(
            "s",
            AmiPlannerManager.Turn.user("one"),
            AmiPlannerManager.Turn.assistant("two"),
            AmiPlannerManager.Turn.user("three")
        )
        val texts = json.getAsJsonArray("contents").map {
            it.asJsonObject.getAsJsonArray("parts")[0].asJsonObject.get("text").asString
        }
        assertEquals(listOf("one", "two", "three"), texts)
    }

    @Test
    fun `no turns still produces a valid empty contents array`() {
        val json = body("s")
        assertEquals(0, json.getAsJsonArray("contents").size())
    }

    // --- thinking is off ---------------------------------------------------------------

    @Test
    fun `thinking is explicitly disabled`() {
        // Left on, 2.5 Flash spends the output budget thinking and can return
        // finishReason MAX_TOKENS with no text, which reads as "model unavailable".
        val budget = body("s", AmiPlannerManager.Turn.user("x"))
            .getAsJsonObject("generationConfig")
            .getAsJsonObject("thinkingConfig")
            .get("thinkingBudget").asInt
        assertEquals(0, budget)
    }

    // --- Response parsing --------------------------------------------------------------

    @Test
    fun `text is read out of the first candidate`() {
        val response = gson.fromJson(
            """{"candidates":[{"content":{"parts":[{"text":"SAY: hello"}],"role":"model"},
               "finishReason":"STOP"}]}""",
            AmiPlannerManager.GeminiResponse::class.java
        )
        assertEquals("SAY: hello", response.text)
    }

    @Test
    fun `multiple parts are joined rather than only the first being read`() {
        val response = gson.fromJson(
            """{"candidates":[{"content":{"parts":[{"text":"SAY: hello "},{"text":"there"}]}}]}""",
            AmiPlannerManager.GeminiResponse::class.java
        )
        assertEquals("SAY: hello there", response.text)
    }

    @Test
    fun `a blocked response with no candidates is null, not a crash`() {
        val response = gson.fromJson(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            AmiPlannerManager.GeminiResponse::class.java
        )
        assertNull(response.text)
        assertEquals("SAFETY", response.promptFeedback?.blockReason)
    }

    @Test
    fun `a candidate with no content is null, not a crash`() {
        // What a MAX_TOKENS truncation actually looks like on the wire.
        val response = gson.fromJson(
            """{"candidates":[{"finishReason":"MAX_TOKENS"}]}""",
            AmiPlannerManager.GeminiResponse::class.java
        )
        assertNull(response.text)
        assertEquals("MAX_TOKENS", response.candidates?.first()?.finishReason)
    }

    @Test
    fun `whitespace-only text counts as no text`() {
        val response = gson.fromJson(
            """{"candidates":[{"content":{"parts":[{"text":"   "}]}}]}""",
            AmiPlannerManager.GeminiResponse::class.java
        )
        assertNull(response.text)
    }

    // --- Errors ------------------------------------------------------------------------

    @Test
    fun `the message is pulled out of a Google error body`() {
        val pretty = """{
          "error": {
            "code": 400,
            "message": "API key not valid. Please pass a valid API key.",
            "status": "INVALID_ARGUMENT"
          }
        }"""
        assertEquals("API key not valid. Please pass a valid API key.", GeminiWire.messageFrom(pretty, 400))
    }

    @Test
    fun `a compact error body is handled too`() {
        val compact = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        assertEquals("Quota exceeded", GeminiWire.messageFrom(compact, 429))
    }

    @Test
    fun `an unparseable error falls back to the status code`() {
        assertEquals("Brain Error 503", GeminiWire.messageFrom("<html>Service Unavailable</html>", 503))
        assertEquals("Brain Error 500", GeminiWire.messageFrom("", 500))
    }

    // --- The endpoint ------------------------------------------------------------------

    @Test
    fun `the model name is the bare Gemini id, not an OpenRouter path`() {
        // "google/gemini-2.5-flash" would build the URL models/google/gemini-2.5-flash:generateContent.
        assertEquals("gemini-2.5-flash", AmiPlannerManager.MODEL_NAME)
        assertFalse(AmiPlannerManager.MODEL_NAME.contains("/"))
    }

    @Test
    fun `the key name matches what local properties is asked for`() {
        assertEquals("GEMINI_API_KEY", AmiPlannerManager.KEY_NAME)
    }
}
