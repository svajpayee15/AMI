package com.example.ami.caretaker

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.care.AdherenceReport
import com.example.ami.data.AmiPreferences
import com.example.ami.data.MedicineRepository
import com.example.ami.escalation.EscalationClient
import com.example.ami.health.HealthRepository
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The caretaker agent's report.
 *
 * A secondary screen reached from Health rather than a fifth tab: the bottom bar is four
 * tabs on purpose (see [com.example.ami.navigation.AmiNavBar]), and this is something a
 * person opens when they want it, not a place they live.
 *
 * The screen speaks as well as prints. Someone who needs a medicine check-in call is
 * often someone who would rather be read to than squint at four paragraphs.
 */
class CaretakerActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var medicines: MedicineRepository
    private lateinit var health: HealthRepository

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Built as the report renders, so "read this to me" says what is on the screen. */
    private var spokenReport: String = ""

    /**
     * Guards the urgent email against the refresh button and against rotation.
     *
     * The digest looks back seven days, so a red flag mentioned on Tuesday is still in
     * Friday's report. Without this, every open of this screen - and every tap of Refresh -
     * would send the caregivers another urgent email about the same Tuesday.
     */
    private var caregiversNotified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_caretaker)

        medicines = MedicineRepository.getInstance(this)
        health = HealthRepository(this)
        tts = TextToSpeech(this, this)

        findViewById<MaterialButton>(R.id.btnCaretakerRefresh).setOnClickListener { load() }
        findViewById<MaterialButton>(R.id.btnCaretakerSpeak).setOnClickListener { speakReport() }

        load()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsReady = true
        }
    }

    private fun load() {
        findViewById<TextView>(R.id.caretakerNoted).setText(R.string.caretaker_thinking)
        findViewById<LinearLayout>(R.id.caretakerSymptomList).removeAllViews()
        setVisible(R.id.caretakerUrgentCard, false)
        setVisible(R.id.caretakerEmptyCard, false)
        setVisible(R.id.caretakerFoodCard, false)
        setVisible(R.id.caretakerExerciseCard, false)
        setVisible(R.id.caretakerOfflineNote, false)

        lifecycleScope.launch {
            val digest = SymptomDigest.of(
                medicines.symptomsInLastDays(SymptomDigest.DEFAULT_WINDOW_DAYS),
                windowDays = SymptomDigest.DEFAULT_WINDOW_DAYS
            )
            // A failed or unavailable Health Connect read must not take the symptom report
            // down with it - the complaints are the part this screen exists for.
            val vitals = runCatching { health.read() }.getOrNull()

            when (val report = CaretakerAgent().reportOn(digest, vitals, adherenceLine())) {
                is CaretakerAgent.Report.NoData -> renderEmpty()
                is CaretakerAgent.Report.SeekHelp -> renderUrgent(report)
                is CaretakerAgent.Report.Guidance -> renderGuidance(report)
            }
        }
    }

    /** Null when there is no schedule yet, so the agent simply isn't told about doses. */
    private suspend fun adherenceLine(): String? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays((SymptomDigest.DEFAULT_WINDOW_DAYS - 1).toLong())
        val records = medicines.checkInsBetween(
            fromEpochMilli = from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toEpochMilli = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val report = AdherenceReport.of(records, from, today, zone)
        if (!report.hasAnyActivity) return null
        return getString(R.string.caretaker_adherence, report.taken, report.total)
    }

    // --- Rendering ---------------------------------------------------------------

    private fun renderEmpty() {
        setVisible(R.id.caretakerEmptyCard, true)
        setVisible(R.id.caretakerNoticedCard, false)
        findViewById<TextView>(R.id.caretakerDisclaimer).text = ""
        spokenReport = getString(R.string.caretaker_empty_body)
    }

    /**
     * Urgent replaces the advice rather than joining it, and notifies the caregivers.
     *
     * The spoken text tells the person their caregiver is being told, so the email has to
     * actually go - this reuses the same escalation path as a missed check-in, as an
     * urgent message to the whole chain at once.
     */
    private fun renderUrgent(report: CaretakerAgent.Report.SeekHelp) {
        setVisible(R.id.caretakerUrgentCard, true)
        setVisible(R.id.caretakerNoticedCard, true)
        findViewById<TextView>(R.id.caretakerUrgentText).text = report.spoken
        findViewById<TextView>(R.id.caretakerNoted).text = report.digest.describe()
            .replaceFirstChar { it.uppercase() }
        renderSymptoms(report.digest)
        findViewById<TextView>(R.id.caretakerDisclaimer).text = CaretakerAgent.DISCLAIMER

        spokenReport = report.spoken
        notifyCaregivers(report)
    }

    private fun renderGuidance(report: CaretakerAgent.Report.Guidance) {
        setVisible(R.id.caretakerNoticedCard, true)
        findViewById<TextView>(R.id.caretakerNoted).text = report.noted
        renderSymptoms(report.digest)

        fillLines(R.id.caretakerFoodCard, R.id.caretakerFoodList, report.food)
        fillLines(R.id.caretakerExerciseCard, R.id.caretakerExerciseList, report.exercise)

        setVisible(R.id.caretakerOfflineNote, !report.fromModel)
        findViewById<TextView>(R.id.caretakerDisclaimer).text = CaretakerAgent.DISCLAIMER

        spokenReport = buildString {
            append(report.noted)
            if (report.food.isNotEmpty()) {
                append(" ${getString(R.string.caretaker_food_title)}. ")
                append(report.food.joinToString(" "))
            }
            if (report.exercise.isNotEmpty()) {
                append(" ${getString(R.string.caretaker_exercise_title)}. ")
                append(report.exercise.joinToString(" "))
            }
        }
    }

    private fun renderSymptoms(digest: SymptomDigest.Digest) {
        val container = findViewById<LinearLayout>(R.id.caretakerSymptomList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (entry in digest.entries) {
            val row = inflater.inflate(R.layout.item_caretaker_symptom, container, false)
            row.findViewById<TextView>(R.id.symptomHeadline).text = getString(
                R.string.caretaker_symptom_days,
                entry.symptom.label,
                entry.daysAffected,
                entry.worstSeverity.label
            )

            val detail = row.findViewById<TextView>(R.id.symptomDetail)
            val detailText = listOfNotNull(
                entry.lastWords.takeIf { it.isNotBlank() }?.let { "“$it”" },
                getString(R.string.caretaker_symptom_recurring).takeIf { entry.isRecurring }
            ).joinToString(" ")
            if (detailText.isBlank()) {
                detail.visibility = View.GONE
            } else {
                detail.text = detailText
                detail.visibility = View.VISIBLE
            }
            container.addView(row)
        }
    }

    private fun fillLines(cardId: Int, listId: Int, lines: List<String>) {
        val container = findViewById<LinearLayout>(listId)
        container.removeAllViews()
        if (lines.isEmpty()) {
            setVisible(cardId, false)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (line in lines) {
            val view = inflater.inflate(R.layout.item_caretaker_line, container, false) as TextView
            view.text = "•  $line"
            container.addView(view)
        }
        setVisible(cardId, true)
    }

    private fun notifyCaregivers(report: CaretakerAgent.Report.SeekHelp) {
        if (caregiversNotified) return
        caregiversNotified = true
        lifecycleScope.launch {
            val contacts = AmiPreferences(applicationContext).getCaregiversOnce()
            EscalationClient().escalate(
                caregiverEmails = contacts.map { it.email },
                medicineName = getString(R.string.caretaker_urgent_subject),
                message = report.reasons.joinToString("; "),
                urgent = true
            )
        }
    }

    private fun speakReport() {
        if (!ttsReady || spokenReport.isBlank()) return
        tts?.speak(spokenReport, TextToSpeech.QUEUE_FLUSH, null, "AMI_CARETAKER")
    }

    private fun setVisible(id: Int, visible: Boolean) {
        findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }
}
