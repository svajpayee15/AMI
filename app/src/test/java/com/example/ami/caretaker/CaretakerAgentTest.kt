package com.example.ami.caretaker

import com.example.ami.data.SymptomReport
import com.example.ami.health.VitalsSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CaretakerAgentTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 11)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun report(symptom: Symptom, daysAgo: Int = 0) = SymptomReport(
        symptomKey = symptom.key,
        severity = SymptomSeverity.UNKNOWN.key,
        rawText = "it is bothering me",
        timestampEpochMilli = today.minusDays(daysAgo.toLong())
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    )

    private fun digestOf(vararg rows: SymptomReport) =
        SymptomDigest.of(rows.toList(), windowDays = 7, nowEpochMilli = now, zone = zone)

    /** A model that fails the test if it is ever consulted. */
    private val neverAsked: suspend (String) -> String? = {
        throw AssertionError("the model was consulted when it should not have been")
    }

    private val unavailable: suspend (String) -> String? = { null }

    // --- The rule gate ----------------------------------------------------------------

    @Test
    fun `nothing recorded at all is NoData`() = runBlocking {
        val report = CaretakerAgent(neverAsked).reportOn(digestOf(), vitals = null)
        assertTrue(report is CaretakerAgent.Report.NoData)
    }

    @Test
    fun `a red flag returns SeekHelp without ever consulting the model`() = runBlocking {
        // The whole point of the ordering: advice about a reported fall must be
        // impossible to generate, not merely discouraged by a prompt.
        val report = CaretakerAgent(neverAsked).reportOn(digestOf(report(Symptom.FALL)))

        assertTrue(report is CaretakerAgent.Report.SeekHelp)
        report as CaretakerAgent.Report.SeekHelp
        assertTrue(report.spoken.contains("a fall"))
        assertTrue(report.spoken.contains("doctor"))
    }

    @Test
    fun `a red flag suppresses advice even when everyday complaints are present`() = runBlocking {
        val report = CaretakerAgent(neverAsked).reportOn(
            digestOf(
                report(Symptom.HEADACHE, daysAgo = 0),
                report(Symptom.HEADACHE, daysAgo = 1),
                report(Symptom.CHEST_PAIN, daysAgo = 0)
            )
        )
        assertTrue(report is CaretakerAgent.Report.SeekHelp)
    }

    @Test
    fun `an urgent vitals reading returns SeekHelp without consulting the model`() = runBlocking {
        val report = CaretakerAgent(neverAsked).reportOn(
            digest = digestOf(report(Symptom.HEADACHE)),
            vitals = VitalsSnapshot(systolic = 195, diastolic = 125, weightKg = 70.0)
        )
        assertTrue(report is CaretakerAgent.Report.SeekHelp)
        report as CaretakerAgent.Report.SeekHelp
        assertTrue(report.reasons.any { it.contains("blood pressure") })
    }

    // --- The built-in path ------------------------------------------------------------

    @Test
    fun `an unreachable model still produces food and exercise advice`() = runBlocking {
        // Blank GEMINI_API_KEY is the normal case on a fresh build, so this path has
        // to be the useful one, not a stub.
        val report = CaretakerAgent(unavailable).reportOn(digestOf(report(Symptom.CONSTIPATION)))

        assertTrue(report is CaretakerAgent.Report.Guidance)
        report as CaretakerAgent.Report.Guidance
        assertFalse(report.fromModel)
        assertEquals(listOf(Symptom.CONSTIPATION.foodTip), report.food)
        assertEquals(listOf(Symptom.CONSTIPATION.moveTip), report.exercise)
    }

    @Test
    fun `built-in advice covers each complaint and stays a short list`() = runBlocking {
        val report = CaretakerAgent(unavailable).reportOn(
            digestOf(
                report(Symptom.HEADACHE),
                report(Symptom.FATIGUE),
                report(Symptom.JOINT_PAIN),
                report(Symptom.CONSTIPATION),
                report(Symptom.POOR_SLEEP),
                report(Symptom.SWELLING)
            )
        ) as CaretakerAgent.Report.Guidance

        assertTrue(report.food.size <= 4)
        assertTrue(report.exercise.size <= 4)
        assertEquals(report.food.size, report.food.distinct().size)
    }

    @Test
    fun `the noted line calls out something that keeps coming back`() = runBlocking {
        val report = CaretakerAgent(unavailable).reportOn(
            digestOf(
                report(Symptom.HEADACHE, daysAgo = 0),
                report(Symptom.HEADACHE, daysAgo = 2),
                report(Symptom.HEADACHE, daysAgo = 4)
            )
        ) as CaretakerAgent.Report.Guidance

        assertTrue(report.noted.contains("Headache"))
        assertTrue(report.noted.contains("doctor"))
    }

    @Test
    fun `readings alone, with no complaints, still produce general advice`() = runBlocking {
        val report = CaretakerAgent(unavailable).reportOn(
            digest = digestOf(),
            vitals = VitalsSnapshot(systolic = 145, diastolic = 92, weightKg = 70.0)
        )
        assertTrue(report is CaretakerAgent.Report.Guidance)
        report as CaretakerAgent.Report.Guidance
        assertTrue(report.food.isNotEmpty())
        assertTrue(report.exercise.isNotEmpty())
        assertTrue(report.noted.contains("blood pressure"))
    }

    // --- The model path ---------------------------------------------------------------

    @Test
    fun `a well formed model reply is parsed into the three sections`() = runBlocking {
        val reply = """
            NOTED: You have had a headache on three days this week.
            FOOD:
            - Drink water regularly.
            - Do not skip your meals.
            EXERCISE:
            - A short walk in the morning.
        """.trimIndent()

        val report = CaretakerAgent({ reply }).reportOn(digestOf(report(Symptom.HEADACHE)))
            as CaretakerAgent.Report.Guidance

        assertTrue(report.fromModel)
        assertEquals("You have had a headache on three days this week.", report.noted)
        assertEquals(listOf("Drink water regularly.", "Do not skip your meals."), report.food)
        assertEquals(listOf("A short walk in the morning."), report.exercise)
    }

    @Test
    fun `the digest survives the model round trip`() = runBlocking {
        val reply = "NOTED: Something.\nFOOD:\n- Eat.\nEXERCISE:\n- Walk."
        val report = CaretakerAgent({ reply }).reportOn(digestOf(report(Symptom.HEADACHE)))
            as CaretakerAgent.Report.Guidance

        // parse() has no digest of its own; if the caller forgets to reattach it, the
        // screen renders an empty symptom list under a populated report.
        assertEquals(listOf(Symptom.HEADACHE), report.digest.entries.map { it.symptom })
    }

    @Test
    fun `a reply missing a section falls back rather than rendering half a report`() = runBlocking {
        val report = CaretakerAgent({ "NOTED: Something.\nFOOD:\n- Eat." })
            .reportOn(digestOf(report(Symptom.HEADACHE))) as CaretakerAgent.Report.Guidance

        assertFalse(report.fromModel)
        assertEquals(listOf(Symptom.HEADACHE.moveTip), report.exercise)
    }

    @Test
    fun `a reply ignoring the protocol entirely falls back`() = runBlocking {
        val report = CaretakerAgent({ "Sure! Here are some ideas for you to try today." })
            .reportOn(digestOf(report(Symptom.HEADACHE))) as CaretakerAgent.Report.Guidance

        assertFalse(report.fromModel)
    }

    @Test
    fun `bullets written with asterisks or no marker are still collected`() = runBlocking {
        val reply = "NOTED: Something.\nFOOD:\n* Eat well.\nEXERCISE:\nWalk a little."
        val report = CaretakerAgent({ reply }).reportOn(digestOf(report(Symptom.HEADACHE)))
            as CaretakerAgent.Report.Guidance

        assertEquals(listOf("Eat well."), report.food)
        assertEquals(listOf("Walk a little."), report.exercise)
    }
}
