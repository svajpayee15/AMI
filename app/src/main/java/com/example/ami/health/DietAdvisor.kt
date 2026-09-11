package com.example.ami.health

import android.util.Log
import com.example.ami.AmiPlannerManager

/**
 * Turns a vitals snapshot into everyday food suggestions.
 *
 * Two rules shape this class:
 *
 * 1. [VitalsAssessment] runs first, and an URGENT reading short-circuits the whole thing.
 *    Offering someone a recipe while their blood pressure is at crisis level would be
 *    actively harmful, so that path never reaches the model at all.
 * 2. The model is given the *classification* alongside the numbers and is constrained to
 *    ordinary food. It is explicitly barred from diagnosing or discussing medication, and
 *    every result carries a "not medical advice" line.
 */
class DietAdvisor(private val planner: AmiPlannerManager = AmiPlannerManager()) {

    sealed class Advice {
        /** Nothing to work from yet. */
        object NoData : Advice()

        /** A reading needs a human, not a meal plan. */
        data class SeekHelp(val spoken: String, val findings: List<VitalsAssessment.Finding>) : Advice()

        data class Suggestions(val text: String) : Advice()
    }

    suspend fun adviseFor(snapshot: VitalsSnapshot): Advice {
        if (!snapshot.hasAnyVital) return Advice.NoData

        val assessment = VitalsAssessment.assess(snapshot)

        if (assessment.isUrgent) {
            val reasons = assessment.urgentFindings.joinToString(", and ") { it.summary }
            return Advice.SeekHelp(
                spoken = "Your $reasons. Please contact your doctor or a family member now. " +
                    "I'm letting your caregiver know as well.",
                findings = assessment.urgentFindings
            )
        }

        val reply = planner.chat(SYSTEM_PROMPT, listOf(AmiPlannerManager.Turn.user(describe(snapshot, assessment))))
        if (reply.isNullOrBlank()) {
            Log.w(TAG, "Diet model unavailable, falling back to general guidance")
            return Advice.Suggestions(fallbackFor(assessment) + "\n\n" + DISCLAIMER)
        }
        return Advice.Suggestions(reply.trim() + "\n\n" + DISCLAIMER)
    }

    private fun describe(snapshot: VitalsSnapshot, assessment: VitalsAssessment.Assessment): String =
        buildString {
            append("Readings: ")
            append("steps today ${snapshot.steps}")
            snapshot.bmi?.let { append(", BMI ${VitalsSnapshot.formatBmi(it)}") }
            if (snapshot.systolic != null && snapshot.diastolic != null) {
                append(", blood pressure ${snapshot.systolic}/${snapshot.diastolic}")
            }
            snapshot.glucoseMgDl?.let { append(", blood sugar ${it.toInt()} mg/dL") }
            append(".\nAssessment: ${assessment.describe()}.")
            append("\nSuggest food for today.")
        }

    /**
     * Used when the model can't be reached - including the common case of
     * GEMINI_API_KEY not being set. Generic, conservative, and never silent.
     */
    private fun fallbackFor(assessment: VitalsAssessment.Assessment): String {
        val cautions = assessment.findings.filter { it.severity == VitalsAssessment.Severity.CAUTION }
        if (cautions.isEmpty()) {
            return "Your readings look steady today. A normal balanced day of food is fine - " +
                "plenty of vegetables, some protein, and drink water regularly."
        }
        return buildString {
            append("Things to keep an eye on today: ")
            append(cautions.joinToString("; ") { it.summary })
            append(".\n\nGeneral guidance: go easy on salt and fried food, favour vegetables, ")
            append("whole grains and pulses, keep portions moderate, and drink water regularly.")
        }
    }

    companion object {
        private const val TAG = "AMI_DIET"

        const val DISCLAIMER =
            "This is general guidance, not medical advice. Please check with your doctor " +
                "before changing your diet."

        private val SYSTEM_PROMPT = """
            You suggest everyday food for an elderly person, based on health readings you
            are given along with a plain assessment of them.

            RULES:
            - Suggest ordinary, affordable, easily prepared foods. Assume a home kitchen.
            - Give at most 4 short bullet points, then one short encouraging sentence.
            - Write plainly, for someone reading on a phone without their glasses.
            - NEVER diagnose a condition.
            - NEVER mention medication, doses, supplements, or changing any treatment.
            - NEVER tell them to see a doctor urgently; that decision is made before you
              are called, and readings that need it never reach you.
            - Do not restate the numbers back at them.
            - No markdown headers, no bold, no emoji. Plain lines starting with "- ".
        """.trimIndent()
    }
}
