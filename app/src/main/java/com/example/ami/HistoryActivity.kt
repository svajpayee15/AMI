package com.example.ami

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ami.care.AdherenceReport
import com.example.ami.data.ActionLogRecord
import com.example.ami.data.CheckInRecord
import com.example.ami.data.MedicineRepository
import com.example.ami.navigation.AmiNavBar
import com.example.ami.navigation.AmiTab
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a caregiver opens to answer "is this working?".
 *
 * Two halves, both previously invisible from inside the app: whether doses are
 * actually being confirmed, and what the assistant has been doing on the phone it is
 * driving. The second half matters for trust - an accessibility service that can tap
 * anything should be able to show what it tapped.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var repository: MedicineRepository

    private val dateTime by lazy {
        DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmiNavBar.setContentView(this, R.layout.activity_history, AmiTab.REPORT)
        repository = MedicineRepository.getInstance(this)

        findViewById<MaterialButton>(R.id.btnClearActions).setOnClickListener { confirmClearLog() }

        observeCheckIns()
        observeActions()
    }

    override fun onResume() {
        super.onResume()
        refreshAdherence()
    }

    // --- Adherence ---------------------------------------------------------------

    private fun refreshAdherence() {
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val week = reportFor(today.minusDays(6), today, zone)
            val month = reportFor(today.minusDays(29), today, zone)

            findViewById<TextView>(R.id.reportHeadline).text = headline(week)
            findViewById<TextView>(R.id.reportThirtyDay).text = month.adherencePercent
                ?.let { getString(R.string.report_thirty_day, it, month.taken, month.total) }
                .orEmpty()

            renderBreakdown(week)
        }
    }

    private suspend fun reportFor(from: LocalDate, to: LocalDate, zone: ZoneId): AdherenceReport {
        val records = repository.checkInsBetween(
            fromEpochMilli = from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toEpochMilli = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
        return AdherenceReport.of(records, from, to, zone)
    }

    private fun headline(report: AdherenceReport): String {
        val percent = report.adherencePercent ?: return getString(R.string.report_no_data)
        val streak = report.currentMissedDayStreak
        return when {
            streak >= 2 -> resources.getQuantityString(R.plurals.report_missed_streak, streak, streak)
            report.missed == 0 ->
                resources.getQuantityString(R.plurals.report_all_taken, report.taken, report.taken)
            else -> resources.getQuantityString(
                R.plurals.report_summary, report.total, percent, report.taken, report.total
            )
        }
    }

    private fun renderBreakdown(report: AdherenceReport) {
        val container = findViewById<LinearLayout>(R.id.reportBreakdown)
        container.removeAllViews()
        // A single medicine needs no breakdown - the headline already said it.
        if (report.perMedicine.size < 2) return

        report.perMedicine.forEach { medicine ->
            container.addView(
                TextView(this).apply {
                    setTextAppearance(R.style.AmiText_Body_Secondary)
                    text = getString(
                        R.string.report_per_medicine,
                        medicine.medicineName,
                        medicine.taken,
                        medicine.total
                    )
                }
            )
        }
    }

    // --- Check-ins ---------------------------------------------------------------

    private fun observeCheckIns() {
        lifecycleScope.launch {
            repository.observeRecentCheckIns().collect { records ->
                val container = findViewById<LinearLayout>(R.id.checkInsContainer)
                val empty = findViewById<TextView>(R.id.checkInsEmpty)
                container.removeAllViews()
                empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

                records.forEachIndexed { index, record -> container.addRow(index) { row ->
                    row.findViewById<TextView>(R.id.historyTitle).text = record.medicineName
                    row.findViewById<TextView>(R.id.historyWhen).text =
                        dateTime.format(Instant.ofEpochMilli(record.timestampEpochMilli))
                    row.findViewById<TextView>(R.id.historyNote).apply {
                        text = record.wellbeingNote.orEmpty()
                        visibility = if (record.wellbeingNote.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                    row.findViewById<TextView>(R.id.historyOutcome).applyOutcome(
                        label = getString(outcomeLabel(record.outcome)),
                        good = record.wasTaken
                    )
                } }
            }
        }
    }

    private fun outcomeLabel(outcome: String): Int = when (outcome) {
        CheckInRecord.OUTCOME_TAKEN -> R.string.outcome_taken
        CheckInRecord.OUTCOME_NOT_TAKEN -> R.string.outcome_not_taken
        CheckInRecord.OUTCOME_DECLINED -> R.string.outcome_declined
        else -> R.string.outcome_no_answer
    }

    // --- Guided-navigation log ---------------------------------------------------

    private fun observeActions() {
        lifecycleScope.launch {
            repository.observeActionLog().collect { records ->
                val container = findViewById<LinearLayout>(R.id.actionsContainer)
                val empty = findViewById<TextView>(R.id.actionsEmpty)
                container.removeAllViews()
                empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                findViewById<MaterialButton>(R.id.btnClearActions).visibility =
                    if (records.isEmpty()) View.GONE else View.VISIBLE

                records.forEachIndexed { index, record -> container.addRow(index) { row ->
                    row.findViewById<TextView>(R.id.historyTitle).text = record.goal
                    row.findViewById<TextView>(R.id.historyWhen).text = getString(
                        R.string.report_action_when,
                        dateTime.format(Instant.ofEpochMilli(record.timestampEpochMilli)),
                        record.packageName ?: getString(R.string.report_action_unknown_app)
                    )
                    row.findViewById<TextView>(R.id.historyNote).apply {
                        text = record.spokenText ?: record.action
                        visibility = View.VISIBLE
                    }
                    row.findViewById<TextView>(R.id.historyOutcome).applyOutcome(
                        label = getString(actionOutcomeLabel(record.outcome)),
                        good = record.outcome == ActionLogRecord.OUTCOME_OK ||
                            record.outcome == ActionLogRecord.OUTCOME_GOAL_REACHED
                    )
                } }
            }
        }
    }

    private fun actionOutcomeLabel(outcome: String): Int = when (outcome) {
        ActionLogRecord.OUTCOME_GOAL_REACHED -> R.string.outcome_done
        ActionLogRecord.OUTCOME_NOT_FOUND -> R.string.outcome_not_found
        ActionLogRecord.OUTCOME_BLOCKED -> R.string.outcome_blocked
        ActionLogRecord.OUTCOME_STOPPED -> R.string.outcome_stopped
        else -> R.string.outcome_step
    }

    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.report_actions_clear)
            .setMessage(R.string.report_actions_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.report_actions_clear) { _, _ ->
                lifecycleScope.launch { repository.clearActionLog() }
            }
            .show()
    }

    // --- Shared row plumbing -----------------------------------------------------

    private inline fun LinearLayout.addRow(index: Int, bind: (View) -> Unit) {
        val row = LayoutInflater.from(context).inflate(R.layout.item_history_row, this, false)
        bind(row)
        if (index > 0) {
            (row.layoutParams as LinearLayout.LayoutParams).topMargin =
                resources.getDimensionPixelSize(R.dimen.space_sm)
        }
        addView(row)
    }

    /** Green for the outcome anyone wants to see, amber for everything else. */
    private fun TextView.applyOutcome(label: String, good: Boolean) {
        text = label
        setBackgroundResource(if (good) R.drawable.bg_pill_success else R.drawable.bg_pill_warning)
        setTextColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (good) R.color.ami_on_success_container else R.color.ami_on_warning_container
            )
        )
    }
}
