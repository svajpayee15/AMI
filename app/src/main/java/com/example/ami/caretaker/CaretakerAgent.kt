package com.example.ami.caretaker

import android.util.Log
import com.example.ami.AmiPlannerManager
import com.example.ami.health.VitalsAssessment
import com.example.ami.health.VitalsSnapshot

/**
 * The caretaker agent: reads back the health complaints AMI has collected and turns them
 * into what to eat and how to move today.
 *
 * This is the second of AMI's two agents. The navigation agent
 * ([com.example.ami.AmiPlannerManager] driving the accessibility service) acts on the
 * phone; this one acts on nothing at all - it only ever produces words for the person and
 * their caregiver to read. That difference is why it can be given a much longer leash on
 * what it says and none whatsoever on what it decides.
 *
 * Everything that matters is decided before the model is reached:
 *
 * 1. [SymptomDigest] has already counted what was mentioned and on how many days.
 * 2. A red-flag symptom or an URGENT vitals reading returns [Report.SeekHelp] without any
 *    model call at all. Suggesting a walk to someone who reported chest pain is the exact
 *    failure this ordering exists to make impossible.
 * 3. Only then is the model asked, and only to phrase advice about ordinary complaints -
 *    barred from diagnosing, from naming conditions, and from mentioning medication.
 *
 * When the model is unreachable - including the common case of `GEMINI_API_KEY` being
 * blank - [Report.Guidance] is still produced, built from the per-symptom tips on
 * [Symptom] itself. A caretaker report that goes blank without an API key would be useless
 * on exactly the phones most likely to be running this app.
 */
class CaretakerAgent(
    /**
     * Takes the finished brief and returns the model's reply, or null when it could not be
     * reached. Injected rather than reaching for [AmiPlannerManager] directly so the rule
     * gate and the fallback can be tested without a network - the same reason
     * [com.example.ami.call.NurseConversation] takes its speaking and listening as
     * parameters.
     */
    private val ask: suspend (String) -> String? = defaultAsk()
) {

    sealed class Report {

        /** Nothing has been recorded yet - no complaints, no readings. */
        data object NoData : Report()

        /** Something here needs a person. No advice is offered alongside it, by design. */
        data class SeekHelp(
            val spoken: String,
            val reasons: List<String>,
            val digest: SymptomDigest.Digest
        ) : Report()

        data class Guidance(
            /** What AMI noticed, in one or two sentences. */
            val noted: String,
            val food: List<String>,
            val exercise: List<String>,
            val digest: SymptomDigest.Digest,
            /** False when this came from the built-in tips because the model was unreachable. */
            val fromModel: Boolean
        ) : Report()
    }

    suspend fun reportOn(
        digest: SymptomDigest.Digest,
        vitals: VitalsSnapshot? = null,
        /** e.g. "took 12 of 14 doses this week", or null when there is no schedule yet. */
        adherenceLine: String? = null
    ): Report {
        val assessment = vitals?.takeIf { it.hasAnyVital }?.let { VitalsAssessment.assess(it) }

        if (digest.isEmpty && assessment == null) return Report.NoData

        // --- Rule gate, ahead of any model call ---
        // The metric is named alongside its summary. VitalsAssessment phrases findings as
        // bare numbers ("195 over 125 is very high"), which is fine on a screen headed
        // "blood pressure" and useless in a sentence read aloud on its own.
        val urgentReasons = buildList {
            digest.redFlags.forEach { add("you mentioned ${it.symptom.spoken}") }
            assessment?.urgentFindings?.forEach { add("your ${it.metric} reading: ${it.summary}") }
        }
        if (urgentReasons.isNotEmpty()) {
            return Report.SeekHelp(
                spoken = "${urgentReasons.joinToString(", and ").replaceFirstChar { it.uppercase() }}. " +
                    "That is not something I can give advice about. Please contact your doctor " +
                    "or a family member now. I'm letting your caregiver know as well.",
                reasons = urgentReasons,
                digest = digest
            )
        }

        val fallback = builtInGuidance(digest, assessment)

        val reply = ask(brief(digest, assessment, adherenceLine))
        val parsed = reply?.let(::parse)
        if (parsed == null) {
            Log.w(TAG, "Caretaker model unavailable, using built-in tips")
            return fallback
        }
        return parsed.copy(digest = digest, fromModel = true)
    }

    // --- Built-in path ---------------------------------------------------------------

    /**
     * Assembled from the tips declared on each [Symptom], worst-first and de-duplicated.
     *
     * Capped at [MAX_TIPS] because a list of nine things to do is a list nobody does.
     */
    private fun builtInGuidance(
        digest: SymptomDigest.Digest,
        assessment: VitalsAssessment.Assessment?
    ): Report.Guidance {
        val advisable = digest.advisable

        val food = advisable.mapNotNull { it.symptom.foodTip }.distinct().take(MAX_TIPS)
        val exercise = advisable.mapNotNull { it.symptom.moveTip }.distinct().take(MAX_TIPS)

        val cautions = assessment?.findings
            ?.filter { it.severity == VitalsAssessment.Severity.CAUTION }
            .orEmpty()

        return Report.Guidance(
            noted = notedLine(digest, cautions),
            food = food.ifEmpty { listOf(GENERAL_FOOD) },
            exercise = exercise.ifEmpty { listOf(GENERAL_EXERCISE) },
            digest = digest,
            fromModel = false
        )
    }

    private fun notedLine(
        digest: SymptomDigest.Digest,
        cautions: List<VitalsAssessment.Finding>
    ): String {
        val parts = mutableListOf<String>()

        val advisable = digest.advisable
        if (advisable.isNotEmpty()) {
            val listed = advisable.take(MAX_TIPS).joinToString(", ") { entry ->
                val days = if (entry.daysAffected == 1) "one day" else "${entry.daysAffected} days"
                "${entry.symptom.spoken} on $days"
            }
            parts += "Over the last ${digest.windowDays} days you mentioned $listed."
        }

        val recurring = digest.recurring
        if (recurring.isNotEmpty()) {
            parts += "${recurring.joinToString(" and ") { it.symptom.label }} " +
                "${if (recurring.size == 1) "has" else "have"} come up often enough that " +
                "it is worth mentioning to your doctor at the next visit."
        }

        if (cautions.isNotEmpty()) {
            parts += "Your readings also show " +
                cautions.joinToString("; ") { "${it.metric} - ${it.summary}" } + "."
        }

        if (parts.isEmpty()) {
            parts += "Nothing has come up in the last ${digest.windowDays} days - that's good news."
        }
        return parts.joinToString(" ")
    }

    // --- Model path ------------------------------------------------------------------

    private fun brief(
        digest: SymptomDigest.Digest,
        assessment: VitalsAssessment.Assessment?,
        adherenceLine: String?
    ): String = buildString {
        append("Symptoms over the last ${digest.windowDays} days: ")
        append(digest.describe())
        append(".")
        assessment?.let { append("\nReadings: ${it.describe()}.") }
        adherenceLine?.takeIf { it.isNotBlank() }?.let { append("\nMedicines: $it.") }
        append("\nWrite today's caretaker note.")
    }

    /**
     * Returns null when the reply doesn't carry all three sections, which sends the caller
     * to the built-in tips. A half-parsed report with an empty food list reads like AMI
     * had nothing to say, which is worse than the generic advice it would have given.
     */
    private fun parse(reply: String): Report.Guidance? {
        var section: String? = null
        val noted = StringBuilder()
        val food = mutableListOf<String>()
        val exercise = mutableListOf<String>()

        for (raw in reply.lines()) {
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
            if (line.isBlank()) continue
            when {
                line.startsWith(NOTED_PREFIX, ignoreCase = true) -> {
                    section = NOTED_PREFIX
                    noted.append(line.substringAfter(":", "").trim())
                }
                line.startsWith(FOOD_PREFIX, ignoreCase = true) -> {
                    section = FOOD_PREFIX
                    line.substringAfter(":", "").trim().takeIf { it.isNotBlank() }?.let(food::add)
                }
                line.startsWith(EXERCISE_PREFIX, ignoreCase = true) -> {
                    section = EXERCISE_PREFIX
                    line.substringAfter(":", "").trim().takeIf { it.isNotBlank() }?.let(exercise::add)
                }
                else -> when (section) {
                    NOTED_PREFIX -> noted.append(" ").append(line)
                    FOOD_PREFIX -> food.add(line)
                    EXERCISE_PREFIX -> exercise.add(line)
                    else -> Unit // preamble before the first header; ignore
                }
            }
        }

        if (noted.isBlank() || food.isEmpty() || exercise.isEmpty()) return null
        return Report.Guidance(
            noted = noted.toString().trim(),
            food = food.take(MAX_TIPS),
            exercise = exercise.take(MAX_TIPS),
            // Replaced by the caller; parse() has no digest of its own.
            digest = SymptomDigest.Digest(0, emptyList()),
            fromModel = true
        )
    }

    companion object {
        private const val TAG = "AMI_CARETAKER"
        private const val MAX_TIPS = 4

        /**
         * One planner for the life of the agent, closed over so the default path doesn't
         * build a fresh client per report.
         */
        private fun defaultAsk(): suspend (String) -> String? {
            val planner = AmiPlannerManager()
            return { brief -> planner.chat(SYSTEM_PROMPT, listOf(AmiPlannerManager.Turn.user(brief))) }
        }

        private const val NOTED_PREFIX = "NOTED"
        private const val FOOD_PREFIX = "FOOD"
        private const val EXERCISE_PREFIX = "EXERCISE"

        const val DISCLAIMER =
            "This is general guidance based on what you've told AMI, not medical advice. " +
                "Please check with your doctor before changing your food, your exercise, " +
                "or any treatment."

        private const val GENERAL_FOOD =
            "A normal balanced day of food is fine - vegetables, some protein like dal or " +
                "curd, whole grains, and water through the day."

        private const val GENERAL_EXERCISE =
            "A short walk, some gentle stretching, and getting up from the chair every " +
                "hour or so is plenty."

        private val SYSTEM_PROMPT = """
            You are a caretaker writing a short daily note for an elderly person, based on
            the health complaints they mentioned on their medicine check-in calls and any
            readings you are given.

            RULES:
            - NEVER diagnose. Never name a disease or a condition.
            - NEVER mention medication, doses, supplements, or changing any treatment.
            - NEVER tell them to seek urgent care; anything urgent is handled before you
              are called and never reaches you.
            - Suggest ordinary, affordable, easily prepared food. Assume a home kitchen.
            - Suggest gentle movement suited to an older person living at home.
            - Write plainly, for someone reading on a phone without their glasses.
            - Do not use markdown, bold, headers or emoji.

            Reply in EXACTLY this shape and nothing else:
            NOTED: <one or two sentences on what you noticed in their week>
            FOOD:
            - <short suggestion>
            - <short suggestion>
            EXERCISE:
            - <short suggestion>
            - <short suggestion>

            At most 4 bullets under each heading.
        """.trimIndent()
    }
}
