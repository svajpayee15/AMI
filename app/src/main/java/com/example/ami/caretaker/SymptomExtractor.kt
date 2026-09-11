package com.example.ami.caretaker

/**
 * Reads health complaints out of what someone actually said on a check-in call.
 *
 * No Android dependencies and no model call: this runs on the raw transcript the moment
 * the call ends, so symptoms are captured even when `GEMINI_API_KEY` is blank and the
 * conversation fell back to its scripted path. A caretaker report that only worked when
 * the model was reachable would be empty exactly when it mattered.
 *
 * Two pieces of handling do most of the work, and both exist because of how people
 * actually answer "how are you feeling":
 *
 * **Clause splitting.** "My head is fine but my stomach hurts" has to become two separate
 * statements or the negation in the first half poisons the second. Splitting happens on
 * punctuation and on contrast words - deliberately *not* on "and", because "my head and
 * my stomach hurt" would then lose the verb from the first half.
 *
 * **Negation that the phrase itself may own.** A plain clause-level negation check reads
 * "I can't sleep" as a denial, because the complaint is phrased in the negative. So the
 * matched phrase is removed from the clause before the remainder is checked for negation:
 * "can't sleep" leaves nothing behind and is recorded, while "no headache" leaves "no"
 * behind and is discarded. Getting this backwards would either drop every sleep complaint
 * or log a symptom for every person who said they were fine.
 */
object SymptomExtractor {

    data class Detected(
        val symptom: Symptom,
        val severity: SymptomSeverity,
        /** The catalogue phrase that matched. Useful for seeing *why* this was recorded. */
        val phrase: String,
        /**
         * The clause it was found in - the person's own words, minus punctuation and case.
         *
         * This, not [phrase], is what gets quoted back to a caregiver. "my head has been
         * splitting since morning" tells them something; "headache" only tells them which
         * row of the catalogue fired.
         */
        val heard: String
    )

    /**
     * @param text everything the person said during the call, in any order.
     * @return one entry per distinct symptom, worst severity wins when it came up twice.
     */
    fun extract(text: String?): List<Detected> {
        if (text.isNullOrBlank()) return emptyList()

        val worst = LinkedHashMap<Symptom, Detected>()

        fun record(symptom: Symptom, severity: SymptomSeverity, phrase: String, clause: String) {
            val existing = worst[symptom]
            if (existing == null || severity.ordinal > existing.severity.ordinal) {
                worst[symptom] = Detected(symptom, severity, phrase, clause.trim())
            }
        }

        for (clause in clausesOf(text)) {
            if (clause.isBlank()) continue

            // Pass 1: a known phrase, said more or less the way the catalogue has it.
            for (symptom in Symptom.entries) {
                val phrase = symptom.phrases
                    // Longest first: "stomach ache" should be reported as the match, not
                    // whichever shorter phrase happened to be declared earlier.
                    .sortedByDescending { it.length }
                    .firstOrNull { clause.contains(" ${normalise(it).trim()}") }
                    ?: continue

                if (isDenied(clause, phrase)) continue
                record(symptom, severityIn(clause), phrase, clause)
            }

            // Pass 2: a body part and a complaint word in the same clause.
            //
            // People do not speak in catalogue entries. "My head has been hurting since
            // morning" matches no fixed phrase that is worth writing down, but it is
            // unmistakably a headache, and enumerating every tense of every verb against
            // every body part is not a list anyone can maintain.
            //
            // Only unambiguous parts are listed. "Back" is deliberately absent: "and" is
            // not a clause delimiter, so "I came back and my head hurts" would otherwise
            // record back pain. BACK_PAIN keeps its explicit phrases instead.
            if (!hasNegation(clause)) {
                val complaint = COMPLAINT_WORDS.firstOrNull { clause.contains(" $it") }
                if (complaint != null) {
                    for ((part, symptom) in BODY_PARTS) {
                        if (clause.contains(" $part")) {
                            record(symptom, severityIn(clause), "$part $complaint", clause)
                        }
                    }
                }
            }
        }
        return worst.values.toList()
    }

    /** The red-flag subset, which the caretaker agent refuses to advise on. */
    fun redFlagsIn(detected: List<Detected>): List<Detected> = detected.filter { it.symptom.redFlag }

    fun hasRedFlag(text: String?): Boolean = redFlagsIn(extract(text)).isNotEmpty()

    // --- Internals -------------------------------------------------------------------

    /**
     * Splits on sentence punctuation and on contrast words, then normalises each part.
     *
     * "and" is not a delimiter; see the class comment.
     */
    private fun clausesOf(text: String): List<String> =
        text.lowercase()
            .replace("'", "")
            .replace("’", "")
            .split(CLAUSE_DELIMITERS)
            .map { normalise(it) }

    /**
     * Lowercased, punctuation flattened to spaces, and padded with a space at each end so
     * that a leading-space match is a word-boundary match.
     *
     * Only the *leading* boundary is required, so a phrase matches its plural and simple
     * inflections too - "headache" catches "headaches", "cough" catches "coughing".
     */
    private fun normalise(raw: String): String =
        " " + raw.lowercase()
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim() + " "

    /**
     * Whether the clause denies the complaint rather than reporting it.
     *
     * The matched phrase is cut out first so that a complaint phrased in the negative
     * ("can't sleep", "no appetite") keeps its own negation instead of being read as a
     * denial of itself.
     */
    private fun isDenied(clause: String, phrase: String): Boolean =
        hasNegation(clause.replace(normalise(phrase).trim(), " "))

    private fun hasNegation(text: String): Boolean =
        NEGATIONS.any { text.contains(" $it ") }

    /** Checked worst-first, so "very bad" is severe rather than moderate. */
    private fun severityIn(clause: String): SymptomSeverity = when {
        SEVERE_WORDS.any { clause.contains(" $it") } -> SymptomSeverity.SEVERE
        MODERATE_WORDS.any { clause.contains(" $it") } -> SymptomSeverity.MODERATE
        MILD_WORDS.any { clause.contains(" $it") } -> SymptomSeverity.MILD
        else -> SymptomSeverity.UNKNOWN
    }

    private val CLAUSE_DELIMITERS = Regex("[,.;!?]|\\bbut\\b|\\bhowever\\b|\\bthough\\b|\\balthough\\b")

    /**
     * Whole-word matches only. "know" contains "no" and "note" contains "not", which is
     * why every check here is bounded on both sides.
     */
    private val NEGATIONS = listOf(
        "no", "not", "nope", "dont", "doesnt", "didnt", "havent", "hasnt", "hadnt",
        "isnt", "arent", "wasnt", "wont", "never", "nothing", "none", "without", "nil"
    )

    private val SEVERE_WORDS = listOf(
        "very", "really", "severe", "terrible", "terribly", "unbearable", "awful",
        "horrible", "extreme", "worst", "a lot", "lot of", "too much", "killing me",
        "cannot bear", "cant bear", "all night", "all day"
    )

    private val MODERATE_WORDS = listOf(
        "quite", "fairly", "bad", "badly", "moderate", "pretty", "some", "keeps",
        "again", "still"
    )

    /**
     * Body parts unambiguous enough to pair with a complaint word; see pass 2 in [extract].
     * Ordered map so a part that maps to a red flag is never shadowed.
     */
    private val BODY_PARTS = linkedMapOf(
        "chest" to Symptom.CHEST_PAIN,
        "head" to Symptom.HEADACHE,
        "stomach" to Symptom.STOMACH_ACHE,
        "tummy" to Symptom.STOMACH_ACHE,
        "belly" to Symptom.STOMACH_ACHE,
        "knee" to Symptom.JOINT_PAIN,
        "joint" to Symptom.JOINT_PAIN,
        "hip" to Symptom.JOINT_PAIN,
        "shoulder" to Symptom.JOINT_PAIN,
        "throat" to Symptom.COUGH_COLD
    )

    private val COMPLAINT_WORDS = listOf(
        "hurt", "hurts", "hurting", "pain", "pains", "paining", "painful",
        "ache", "aches", "aching", "sore", "trouble", "killing"
    )

    private val MILD_WORDS = listOf(
        "slight", "slightly", "little", "bit", "mild", "mildly", "minor", "small",
        "manageable", "ok", "okay"
    )
}
