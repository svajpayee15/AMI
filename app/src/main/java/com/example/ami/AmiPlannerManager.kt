package com.example.ami

import android.util.Log
import com.example.ami.navigation.SettingsDeepLinks
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Single canonical LLM planner for AMI: Gemini 2.5 Flash, called directly on the Google
 * AI Studio (Generative Language) API.
 *
 * Previously this went through OpenRouter's OpenAI-shaped `/chat/completions`. Talking to
 * Google directly removes a hop and a second account, but the wire format is genuinely
 * different in three ways, each of which is a silent failure if missed:
 *
 * 1. **The system prompt is not a message.** It goes in `system_instruction`. Sent as a
 *    `contents` entry with role "system" it is rejected outright - Gemini accepts only
 *    "user" and "model" there.
 * 2. **The assistant role is called "model".** [Turn] keeps saying "assistant" because
 *    that is what reads well at the call sites; the mapping happens at the wire boundary
 *    in [contentsOf].
 * 3. **2.5 Flash thinks by default,** and thinking tokens are drawn from the same output
 *    budget as the reply. Left on, a request can come back `finishReason: MAX_TOKENS`
 *    with no text at all. Every prompt in this app asks for one short structured line -
 *    a single `SAY:`/`END:` line, or `BUTTON | VOICE` - so thinking buys nothing and
 *    costs latency on a call someone is waiting through. It is switched off explicitly.
 *
 * The response shape differs too: text arrives as `candidates[0].content.parts[].text`,
 * which can be absent entirely when a response is blocked. [GeminiResponse.text] folds
 * all of that into one nullable string so callers keep their existing fallbacks.
 */
class AmiPlannerManager {

    private val apiKey = BuildConfig.GEMINI_API_KEY
    private val modelName = MODEL_NAME

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/v1beta/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val gemini = retrofit.create(GeminiService::class.java)

    suspend fun findNextAction(screenText: String, goal: String, history: String, healthData: String? = null): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "$KEY_NAME is blank - planning unavailable")
            return@withContext Pair(null, "AMI's assistant is not set up on this phone yet.")
        }

        val systemPrompt = """
            You are AMI, a warm and patient Digital Caretaker for an elderly person.
            Goal: $goal. Context: ${healthData ?: "N/A"}. History: $history.

            SCREEN CONTENT is a list of visible labels separated by "|". Each label may
            carry an annotation: [tap] it is a button, [toggle:on] / [toggle:off] it is a
            switch and its current state, [scrollable] it is a scrolling list.

            GUIDELINES:
            1. Speak like a kind nurse. Use "Don't worry," "You're doing great."
            2. Point to one button at a time.
            3. If a switch is already [toggle:on] and the goal wanted it on, say so and
               reply GOAL_REACHED instead of pointing at it again.
            4. To reach a phone setting, prefer jumping straight there with
               SETTINGS:<key> rather than pointing at "Settings" and navigating.
               Valid keys: ${SettingsDeepLinks.promptVocabulary()}
            5. Other verbs: LAUNCH:<app name>, ACTION:HOME, ACTION:BACK,
               ACTION:NOTIFICATIONS, SWIPE:<direction>, GOAL_REACHED.
               Anything else is treated as the exact label to point at.
            6. Respond with exactly ONE line in the format below and nothing else -
               no markdown, no code fences, no preamble, no explanation.
            Format: BUTTON_TEXT | VOICE_RESPONSE
        """.trimIndent()

        try {
            Log.d(TAG, "Requesting Gemini: $modelName")
            val response = gemini.generateContent(
                model = modelName,
                apiKey = apiKey,
                request = requestBody(systemPrompt, listOf(Turn.user("SCREEN CONTENT:\n$screenText")))
            )

            val fullText = response.text.orEmpty()
            Log.d(TAG, "Response: $fullText")

            if (fullText.contains("|")) {
                val parts = fullText.split("|", limit = 2)
                Pair(parts[0].trim(), parts[1].trim())
            } else {
                Pair(null, fullText)
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            Log.e(TAG, "HTTP Error ${e.code()}: $errorBody")
            Pair(null, "Gemini Error: ${messageFrom(errorBody, e.code())}")
        } catch (e: Exception) {
            Log.e(TAG, "Failure", e)
            Pair(null, "Brain connection failed.")
        }
    }

    /**
     * Free-form multi-turn chat over the same transport, key and logging configuration
     * as [findNextAction]. Used by the medicine check-in conversation and the caretaker
     * agent, which need a back-and-forth rather than the single BUTTON | VOICE line.
     *
     * Returns null on any failure so callers can fall back to a scripted flow.
     */
    suspend fun chat(systemPrompt: String, turns: List<Turn>): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "$KEY_NAME is blank - chat unavailable")
            return@withContext null
        }

        try {
            val response = gemini.generateContent(
                model = modelName,
                apiKey = apiKey,
                request = requestBody(systemPrompt, turns)
            )
            response.candidates?.firstOrNull()?.finishReason?.let { reason ->
                // STOP is the normal ending. Anything else means the text is truncated or
                // withheld, and is worth seeing in the log rather than silently falling
                // back to the scripted flow with no explanation.
                if (reason != "STOP") Log.w(TAG, "Gemini finished with $reason")
            }
            response.text
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "Chat HTTP ${e.code()}: ${e.response()?.errorBody()?.string()}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            null
        }
    }

    private fun requestBody(systemPrompt: String, turns: List<Turn>) =
        GeminiWire.requestBody(systemPrompt, turns)

    private fun messageFrom(errorBody: String, code: Int) = GeminiWire.messageFrom(errorBody, code)

    data class Turn(val role: String, val content: String) {
        companion object {
            const val ROLE_USER = "user"
            const val ROLE_ASSISTANT = "assistant"

            fun user(content: String) = Turn(ROLE_USER, content)
            fun assistant(content: String) = Turn(ROLE_ASSISTANT, content)
        }
    }

    interface GeminiService {
        @POST("models/{model}:generateContent")
        suspend fun generateContent(
            @Path("model") model: String,
            /**
             * Sent as a header rather than the `?key=` query parameter the quickstarts
             * use, so the key never lands in a URL, a log line or a crash report.
             */
            @Header("x-goog-api-key") apiKey: String,
            @Body request: JsonObject
        ): GeminiResponse
    }

    /**
     * Every field is nullable because a blocked or truncated response legitimately omits
     * them - `candidates` can come back empty with only `promptFeedback` set.
     */
    data class GeminiResponse(
        val candidates: List<Candidate>?,
        val promptFeedback: PromptFeedback?
    ) {
        /** All parts of the first candidate, joined; null when there is no text at all. */
        val text: String?
            get() = candidates?.firstOrNull()?.content?.parts
                ?.mapNotNull { it.text }
                ?.joinToString("")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }

    data class Candidate(val content: Content?, val finishReason: String?)
    data class Content(val parts: List<Part>?, val role: String?)
    data class Part(val text: String?)
    data class PromptFeedback(val blockReason: String?)

    companion object {
        private const val TAG = "AMI_PLANNER"

        /** Named once so log warnings match what the person has to put in local.properties. */
        const val KEY_NAME = "GEMINI_API_KEY"

        const val MODEL_NAME = "gemini-2.5-flash"
    }
}
