package com.example.ami.call

import android.util.Log
import com.example.ami.AmiPlannerManager
import com.example.ami.caretaker.SymptomExtractor
import com.example.ami.data.CheckInRecord
import java.util.Locale

/**
 * Runs the medicine check-in as an actual conversation rather than a repeated prompt.
 *
 * The previous behaviour asked one fixed sentence up to three times and keyword-matched
 * "yes". This instead greets the person, asks about the dose, asks how they are doing,
 * and responds to what they actually said - the difference between an alarm and someone
 * checking on you.
 *
 * Deliberately free of Android dependencies: speaking and listening are injected, so the
 * flow can be driven by a service, an activity, or a test.
 */
class NurseConversation(
    private val planner: AmiPlannerManager,
    private val speak: suspend (String) -> Unit,
    private val listen: suspend () -> String?
) {

    data class Result(
        val outcome: String,
        val wellbeingNote: String? = null,
        /**
         * What the caretaker agent will later read. Extracted from everything said on the
         * call rather than from the closing note alone: a headache mentioned in passing
         * three turns ago counts just as much as one mentioned at the end.
         */
        val symptoms: List<SymptomExtractor.Detected> = emptyList(),
        /**
         * A red-flag complaint was heard. The caller must escalate on this alone, even
         * when the dose itself was confirmed - someone can take their tablet and still
         * have told AMI they fell this morning.
         */
        val needsEscalation: Boolean = false
    )

    /**
     * @param medicineName the dose being asked about
     * @param callerName how AMI should refer to itself, e.g. "AMI" or a relative's name
     * @param dosage the caregiver's own note, e.g. "one tablet, with food". Spoken back
     *   verbatim and never reworded - restating a dose in the model's own words would
     *   be giving medical instructions.
     */
    suspend fun run(
        medicineName: String,
        callerName: String = "AMI",
        dosage: String? = null
    ): Result {
        val turns = mutableListOf<AmiPlannerManager.Turn>()
        val transcript = mutableListOf<String>()
        var heardAnything = false
        var lastUserReply: String? = null

        repeat(MAX_TURNS) {
            val reply = planner.chat(systemPrompt(medicineName, callerName, dosage), turns)
                ?: return runScripted(medicineName, dosage, heardAnything, lastUserReply, transcript)

            val line = reply.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

            when {
                line.startsWith(END_PREFIX, ignoreCase = true) -> {
                    return parseEnd(line.substringAfter(END_PREFIX), lastUserReply, transcript)
                }

                line.startsWith(SAY_PREFIX, ignoreCase = true) -> {
                    val sentence = line.substringAfter(SAY_PREFIX).trim()
                    if (sentence.isBlank()) {
                        return runScripted(medicineName, dosage, heardAnything, lastUserReply, transcript)
                    }

                    speak(sentence)
                    turns.add(AmiPlannerManager.Turn.assistant(line))

                    val heard = listen()
                    if (heard.isNullOrBlank()) {
                        turns.add(AmiPlannerManager.Turn.user("(no response)"))
                    } else {
                        heardAnything = true
                        lastUserReply = heard
                        transcript.add(heard)
                        turns.add(AmiPlannerManager.Turn.user(heard))

                        if (SymptomExtractor.hasRedFlag(heard)) {
                            return endOnRedFlag(
                                outcome = inferOutcome(true, heard),
                                note = heard,
                                transcript = transcript
                            )
                        }
                    }
                }

                else -> {
                    // Model ignored the protocol; don't speak raw output at someone.
                    Log.w(TAG, "Unrecognised conversation line: $line")
                    return runScripted(medicineName, dosage, heardAnything, lastUserReply, transcript)
                }
            }
        }

        // Ran out of turns without a verdict - fall back to what we heard.
        return finish(inferOutcome(heardAnything, lastUserReply), lastUserReply, transcript)
    }

    /**
     * Ends the call the moment something alarming is said.
     *
     * The system prompt asks the model to do this too, but a check-in that only stops for
     * a reported fall when the model happens to comply is not a safeguard. This one runs
     * on the recogniser's own output and cannot be talked out of it.
     */
    private suspend fun endOnRedFlag(
        outcome: String,
        note: String?,
        transcript: MutableList<String>
    ): Result {
        speak(
            "Thank you for telling me. I'm going to let your family know about that right " +
                "now so someone can check on you. Please sit down and stay comfortable."
        )
        return finish(outcome, note, transcript, forceEscalation = true)
    }

    /**
     * The single place a [Result] is built, so symptom extraction can never be skipped on
     * one of the several paths a call can end on.
     */
    private fun finish(
        outcome: String,
        note: String?,
        transcript: List<String>,
        forceEscalation: Boolean = false
    ): Result {
        val symptoms = SymptomExtractor.extract(transcript.joinToString(". "))
        return Result(
            outcome = outcome,
            wellbeingNote = note,
            symptoms = symptoms,
            needsEscalation = forceEscalation || symptoms.any { it.symptom.redFlag }
        )
    }

    private fun parseEnd(payload: String, lastUserReply: String?, transcript: List<String>): Result {
        val parts = payload.split("|", limit = 2)
        val rawOutcome = parts.getOrNull(0)?.trim()?.uppercase(Locale.US).orEmpty()
        val note = parts.getOrNull(1)?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("no comment", ignoreCase = true)
        } ?: lastUserReply

        val outcome = when {
            rawOutcome.contains("NOT") -> CheckInRecord.OUTCOME_NOT_TAKEN
            rawOutcome.contains("TAKEN") -> CheckInRecord.OUTCOME_TAKEN
            else -> inferOutcome(lastUserReply != null, lastUserReply)
        }
        return finish(outcome, note, transcript)
    }

    /**
     * Deterministic flow used whenever the model is unavailable - which includes the
     * common case of GEMINI_API_KEY not being configured. A check-in that degrades
     * to a fixed script is far better than one that silently does nothing.
     */
    private suspend fun runScripted(
        medicineName: String,
        dosage: String?,
        heardAnythingSoFar: Boolean,
        priorReply: String?,
        transcript: MutableList<String>
    ): Result {
        Log.i(TAG, "Falling back to scripted check-in for $medicineName")
        var heardAnything = heardAnythingSoFar
        var lastReply = priorReply

        repeat(SCRIPTED_ATTEMPTS) { attempt ->
            val dosageClause = dosage?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            val prompt = if (attempt == 0) {
                "Hello! It's time for your $medicineName$dosageClause. Have you taken it yet?"
            } else {
                "Sorry to bother you again - have you taken your $medicineName?"
            }
            speak(prompt)
            val heard = listen()
            if (!heard.isNullOrBlank()) {
                heardAnything = true
                lastReply = heard
                transcript.add(heard)
                if (SymptomExtractor.hasRedFlag(heard)) {
                    return endOnRedFlag(inferOutcome(true, heard), heard, transcript)
                }
                when (classify(heard)) {
                    Answer.YES -> {
                        speak("That's great, thank you.")
                        return askAboutSymptoms(CheckInRecord.OUTCOME_TAKEN, heard, transcript)
                    }
                    Answer.NO -> {
                        speak("That's alright. Please take it now if you can - I'll check again later.")
                        return askAboutSymptoms(CheckInRecord.OUTCOME_NOT_TAKEN, heard, transcript)
                    }
                    Answer.UNCLEAR -> Unit
                }
            }
        }

        // Nobody answered the medicine question, so there is no conversation to extend.
        return finish(inferOutcome(heardAnything, lastReply), lastReply, transcript)
    }

    /**
     * The caretaker half of the call, on the scripted path.
     *
     * Asked *after* the dose question is settled and asked plainly, naming a couple of
     * everyday complaints. "How are you?" gets "fine" from almost everyone; "any headache
     * or stomach trouble?" gets an answer, which is the whole reason the caretaker report
     * has anything to read.
     */
    private suspend fun askAboutSymptoms(
        outcome: String,
        doseReply: String?,
        transcript: MutableList<String>
    ): Result {
        speak(SYMPTOM_QUESTION)
        val heard = listen()

        if (heard.isNullOrBlank()) {
            speak("No trouble. Take care of yourself.")
            return finish(outcome, doseReply, transcript)
        }

        transcript.add(heard)
        if (SymptomExtractor.hasRedFlag(heard)) {
            return endOnRedFlag(outcome, heard, transcript)
        }

        val found = SymptomExtractor.extract(heard)
        if (found.isEmpty()) {
            speak("I'm glad to hear it. Take care of yourself.")
        } else {
            // Named back so they know they were heard, then nothing further - a scripted
            // line must never stray into telling someone what to do about a symptom.
            speak(
                "I'm sorry to hear about your ${found.first().symptom.spoken}. " +
                    "I've made a note of it. Take care of yourself."
            )
        }
        return finish(outcome, heard, transcript)
    }

    private fun inferOutcome(heardAnything: Boolean, reply: String?): String = when {
        reply != null && classify(reply) == Answer.YES -> CheckInRecord.OUTCOME_TAKEN
        heardAnything -> CheckInRecord.OUTCOME_NOT_TAKEN
        else -> CheckInRecord.OUTCOME_NO_ANSWER
    }

    private enum class Answer { YES, NO, UNCLEAR }

    /**
     * Negation is checked first and on whole words only.
     *
     * Both matter: a substring scan reads "I haven't taken it" as "taken", and a
     * yes-first scan reaches the same wrong answer. Either bug records a missed dose as
     * taken and suppresses the escalation that missed dose is supposed to trigger.
     * "I know I took it" is why the match must be word-bounded - "know" contains "no".
     */
    private fun classify(text: String): Answer {
        val normalised = " " + text.lowercase(Locale.US)
            .replace(Regex("""[^a-z']"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim() + " "
        if (NO_WORDS.any { normalised.contains(" $it ") }) return Answer.NO
        if (YES_WORDS.any { normalised.contains(" $it ") }) return Answer.YES
        return Answer.UNCLEAR
    }

    private fun systemPrompt(medicineName: String, callerName: String, dosage: String?) = """
        You are $callerName, checking in on an elderly person by voice, the way a kind
        nurse or a caring relative would. This is a spoken phone call: they hear you, they
        do not read you.

        Your purpose, in order:
        1. Greet them warmly and briefly.
        2. Find out whether they have taken their $medicineName.${dosageLine(dosage)}
        3. Ask plainly whether they have any pain or trouble today, naming one or two
           ordinary examples - a headache, stomach trouble, dizziness, poor sleep. Do not
           ask "how are you?" on its own; almost everyone answers "fine" to that, and the
           point of this question is to find out what is actually bothering them.
        4. If they mention something, say you have noted it, and ask one short follow-up -
           how long it has been going on, or how bad it is. Then move on.
        5. Close warmly.

        RULES:
        - One short, plain sentence per turn. No lists, no markdown, no emoji.
        - Never give medical advice, never suggest a remedy, never tell them what to eat
          or do about a symptom. You are noting what they say, not treating it. Someone
          else writes the advice later, from your notes.
        - Never discuss changing any medication or dose.
        - If they mention chest pain, breathlessness, a fall, or anything alarming,
          tell them calmly you will let their family know, and end the call.
        - "(no response)" means they said nothing - gently try once more, then end.

        Reply with EXACTLY one line, either:
        SAY: <the single sentence to speak aloud>
        END: <TAKEN or NOT_TAKEN> | <one sentence on how they said they feel, or "no comment">

        Begin the call now.
    """.trimIndent()

    /**
     * The caregiver's dosage note, handed to the model as a quote it must not reword.
     * Empty when none was recorded, so the prompt simply doesn't mention a dose.
     */
    private fun dosageLine(dosage: String?): String =
        dosage?.takeIf { it.isNotBlank() }?.let {
            "\n           The caregiver's note for this dose is exactly: \"$it\". " +
                "You may read that note aloud word for word, but never change it or add to it."
        }.orEmpty()

    companion object {
        private const val TAG = "AMI_NURSE"
        private const val MAX_TURNS = 6
        private const val SCRIPTED_ATTEMPTS = 3
        private const val SAY_PREFIX = "SAY:"
        private const val END_PREFIX = "END:"

        /** The scripted counterpart to step 3 of the system prompt; see [askAboutSymptoms]. */
        private const val SYMPTOM_QUESTION =
            "And how have you been feeling? Any headache, stomach trouble, dizziness, " +
                "or anything else bothering you today?"

        // Whole-word matches only; see classify(). Negation list is consulted first.
        private val NO_WORDS = listOf(
            "no", "not", "nope", "haven't", "havent", "hasn't", "didn't", "didnt",
            "don't", "dont", "forgot", "forget", "later", "nothing"
        )
        private val YES_WORDS = listOf(
            "yes", "yeah", "yep", "yup", "took", "taken", "done", "did", "already", "sure"
        )
    }
}
