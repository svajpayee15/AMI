package com.example.ami.caretaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymptomExtractorTest {

    private fun keys(text: String) = SymptomExtractor.extract(text).map { it.symptom.key }.toSet()

    private fun severityOf(text: String, symptom: Symptom) =
        SymptomExtractor.extract(text).first { it.symptom == symptom }.severity

    // --- The basics ------------------------------------------------------------------

    @Test
    fun `picks up a plainly stated complaint`() {
        assertEquals(setOf("headache"), keys("I have a headache today"))
    }

    @Test
    fun `matches a phrase in its plural and inflected forms`() {
        assertEquals(setOf("headache"), keys("these headaches keep coming"))
        assertEquals(setOf("cough_cold"), keys("I have been coughing all morning"))
    }

    @Test
    fun `finds several complaints in one sentence`() {
        assertEquals(
            setOf("headache", "stomach_ache"),
            keys("my head hurts and my stomach hurts too")
        )
    }

    @Test
    fun `empty and blank input yield nothing`() {
        assertTrue(SymptomExtractor.extract(null).isEmpty())
        assertTrue(SymptomExtractor.extract("   ").isEmpty())
        assertTrue(SymptomExtractor.extract("I am absolutely fine today").isEmpty())
    }

    // --- Negation --------------------------------------------------------------------
    //
    // The single most damaging failure mode: recording a symptom for someone who just
    // said they did not have it, which would put a fictional complaint in front of a
    // caregiver and into the diet advice.

    @Test
    fun `a denied complaint is not recorded`() {
        assertTrue(keys("no headache today").isEmpty())
        assertTrue(keys("I dont have a headache").isEmpty())
        assertTrue(keys("I havent had any fever").isEmpty())
    }

    @Test
    fun `negation does not leak across a contrast word`() {
        assertEquals(
            setOf("stomach_ache"),
            keys("my head is fine but my stomach hurts")
        )
    }

    @Test
    fun `negation does not leak across punctuation`() {
        assertEquals(setOf("fever"), keys("no headache, but I do have a fever"))
    }

    @Test
    fun `a complaint phrased as a negative is still a complaint`() {
        // "can't sleep" owns its own negation; a naive clause-level check reads this as a
        // denial and silently drops every sleep complaint the app will ever hear.
        assertEquals(setOf("poor_sleep"), keys("I cant sleep at night"))
        assertEquals(setOf("poor_sleep"), keys("I couldnt sleep at all"))
        assertEquals(setOf("appetite_loss"), keys("I have no appetite"))
    }

    @Test
    fun `a word merely containing a negation is not a negation`() {
        // "know" contains "no"; "note" contains "not".
        assertEquals(setOf("headache"), keys("you know I have a headache"))
    }

    // --- Severity --------------------------------------------------------------------

    @Test
    fun `intensity is read from their own words`() {
        assertEquals(SymptomSeverity.SEVERE, severityOf("a really bad headache", Symptom.HEADACHE))
        assertEquals(SymptomSeverity.MILD, severityOf("a slight headache", Symptom.HEADACHE))
        assertEquals(SymptomSeverity.MODERATE, severityOf("quite a headache", Symptom.HEADACHE))
    }

    @Test
    fun `no stated intensity stays unknown rather than becoming mild`() {
        assertEquals(SymptomSeverity.UNKNOWN, severityOf("I have a headache", Symptom.HEADACHE))
    }

    @Test
    fun `the worst mention of a symptom wins`() {
        val detected = SymptomExtractor.extract(
            "I had a slight headache this morning. Now it is a terrible headache"
        )
        assertEquals(1, detected.size)
        assertEquals(SymptomSeverity.SEVERE, detected.first().severity)
    }

    // --- Red flags -------------------------------------------------------------------

    @Test
    fun `red flags are recognised and marked`() {
        assertTrue(SymptomExtractor.hasRedFlag("I have chest pain"))
        assertTrue(SymptomExtractor.hasRedFlag("I fell in the bathroom this morning"))
        assertTrue(SymptomExtractor.hasRedFlag("I am short of breath"))
    }

    @Test
    fun `an everyday complaint is not a red flag`() {
        assertFalse(SymptomExtractor.hasRedFlag("my knees hurt today"))
        assertFalse(SymptomExtractor.hasRedFlag("I have a headache"))
    }

    @Test
    fun `a denied red flag does not trigger escalation`() {
        assertFalse(SymptomExtractor.hasRedFlag("no chest pain at all"))
        assertFalse(SymptomExtractor.hasRedFlag("I havent fallen"))
    }

    @Test
    fun `red flags are separable from the rest`() {
        val detected = SymptomExtractor.extract("I have a headache and some chest pain")
        assertEquals(setOf("headache", "chest_pain"), detected.map { it.symptom.key }.toSet())
        assertEquals(listOf("chest_pain"), SymptomExtractor.redFlagsIn(detected).map { it.symptom.key })
    }

    // --- What gets stored ------------------------------------------------------------

    // --- Body part plus a complaint word ---------------------------------------------
    //
    // People don't speak in catalogue entries, so a phrase list alone has poor recall.

    @Test
    fun `a body part and a complaint word are enough`() {
        assertEquals(setOf("headache"), keys("my head has been hurting since morning"))
        assertEquals(setOf("joint_pain"), keys("my knees have been giving me trouble"))
        assertEquals(setOf("stomach_ache"), keys("the stomach is paining a lot"))
    }

    @Test
    fun `a denied body part complaint is still denied`() {
        assertTrue(keys("my head is not hurting at all").isEmpty())
    }

    @Test
    fun `a body part complaint can be a red flag`() {
        assertTrue(SymptomExtractor.hasRedFlag("my chest has been hurting since last night"))
    }

    @Test
    fun `two body parts in one clause are both recorded`() {
        assertEquals(setOf("headache", "joint_pain"), keys("my head and my knees are aching"))
    }

    // --- What gets stored ------------------------------------------------------------

    @Test
    fun `the persons own words are kept alongside the matched phrase`() {
        val detected = SymptomExtractor.extract("My head has been hurting since morning").first()
        assertEquals(Symptom.HEADACHE, detected.symptom)
        // `heard` is what a caregiver reads; `phrase` only says why the row fired.
        assertTrue(detected.heard.contains("since morning"))
        assertTrue(detected.phrase.contains("head"))
    }

    @Test
    fun `whole transcripts are handled, not just single answers`() {
        val transcript = listOf(
            "yes I took it",
            "my knees have been paining",
            "and I didnt sleep well last night"
        ).joinToString(". ")
        assertEquals(setOf("joint_pain", "poor_sleep"), keys(transcript))
    }
}
