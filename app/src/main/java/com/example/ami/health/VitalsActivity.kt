package com.example.ami.health

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.caretaker.CaretakerActivity
import com.example.ami.data.AmiPreferences
import com.example.ami.escalation.EscalationClient
import com.example.ami.navigation.AmiNavBar
import com.example.ami.navigation.AmiTab
import com.example.ami.wellbeing.WellbeingActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class VitalsActivity : AppCompatActivity() {

    private lateinit var repository: HealthRepository
    private val steppers = mutableMapOf<Field, Stepper>()

    private enum class Field(
        val labelRes: Int,
        val step: Double,
        val min: Double,
        val max: Double,
        val default: Double,
        val decimals: Int,
        val unit: String
    ) {
        WEIGHT(R.string.vitals_weight, 0.5, 25.0, 250.0, 70.0, 1, "kg"),
        HEIGHT(R.string.vitals_height, 1.0, 100.0, 220.0, 165.0, 0, "cm"),
        SYSTOLIC(R.string.vitals_systolic, 1.0, 70.0, 250.0, 120.0, 0, "mmHg"),
        DIASTOLIC(R.string.vitals_diastolic, 1.0, 40.0, 150.0, 80.0, 0, "mmHg"),
        GLUCOSE(R.string.vitals_glucose, 1.0, 30.0, 500.0, 100.0, 0, "mg/dL")
    }

    private class Stepper(val field: Field, val valueView: TextView) {
        var value: Double = field.default
    }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthRepository.REQUIRED_PERMISSIONS)) {
            refresh()
        } else {
            // Partial grants are normal and fine - whatever was allowed still reads.
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmiNavBar.setContentView(this, R.layout.activity_vitals, AmiTab.HEALTH)
        repository = HealthRepository(this)

        buildSteppers()

        findViewById<MaterialButton>(R.id.btnConnectHealth).setOnClickListener {
            healthPermissionLauncher.launch(HealthRepository.REQUIRED_PERMISSIONS)
        }
        findViewById<MaterialButton>(R.id.btnSaveVitals).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnGetAdvice).setOnClickListener { advise() }
        findViewById<MaterialButton>(R.id.btnOpenCaretaker).setOnClickListener {
            startActivity(Intent(this, CaretakerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnOpenWellbeing).setOnClickListener {
            startActivity(Intent(this, WellbeingActivity::class.java))
        }

        refresh()
    }

    private fun buildSteppers() {
        val container = findViewById<LinearLayout>(R.id.stepperContainer)
        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.space_lg)

        Field.entries.forEachIndexed { index, field ->
            val row = inflater.inflate(R.layout.item_stepper, container, false)
            row.findViewById<TextView>(R.id.stepperLabel).setText(field.labelRes)

            val valueView = row.findViewById<TextView>(R.id.stepperValue)
            val stepper = Stepper(field, valueView)
            steppers[field] = stepper

            row.findViewById<MaterialButton>(R.id.stepperMinus).apply {
                contentDescription = getString(R.string.vitals_decrease, getString(field.labelRes))
                setOnClickListener { nudge(stepper, -field.step) }
            }
            row.findViewById<MaterialButton>(R.id.stepperPlus).apply {
                contentDescription = getString(R.string.vitals_increase, getString(field.labelRes))
                setOnClickListener { nudge(stepper, field.step) }
            }

            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            }
            container.addView(row)
            render(stepper)
        }
    }

    private fun nudge(stepper: Stepper, delta: Double) {
        stepper.value = (stepper.value + delta).coerceIn(stepper.field.min, stepper.field.max)
        render(stepper)
    }

    private fun render(stepper: Stepper) {
        val format = "%.${stepper.field.decimals}f"
        val value = String.format(Locale.getDefault(), format, stepper.value)
        stepper.valueView.text = getString(R.string.vitals_value_with_unit, value, stepper.field.unit)
    }

    private fun refresh() {
        lifecycleScope.launch {
            val availability = repository.healthConnectAvailability()
            val hasPermissions = repository.hasHealthConnectPermissions()
            val snapshot = repository.read()

            findViewById<TextView>(R.id.vitalsSummary).text = summarise(snapshot)

            val statusView = findViewById<TextView>(R.id.healthConnectStatus)
            val connectButton = findViewById<View>(R.id.btnConnectHealth)

            when {
                availability != HealthConnectSource.Availability.AVAILABLE -> {
                    statusView.setText(R.string.vitals_hc_unavailable)
                    connectButton.visibility = View.GONE
                }
                !hasPermissions -> {
                    statusView.setText(R.string.vitals_hc_needs_permission)
                    connectButton.visibility = View.VISIBLE
                }
                else -> {
                    statusView.setText(R.string.vitals_hc_connected)
                    connectButton.visibility = View.GONE
                }
            }

            // Seed the steppers from whatever is already known, so the user adjusts
            // rather than dialling in from a default every time.
            snapshot.weightKg?.let { steppers[Field.WEIGHT]?.apply { value = it; render(this) } }
            snapshot.heightCm?.let { steppers[Field.HEIGHT]?.apply { value = it; render(this) } }
            snapshot.systolic?.let { steppers[Field.SYSTOLIC]?.apply { value = it.toDouble(); render(this) } }
            snapshot.diastolic?.let { steppers[Field.DIASTOLIC]?.apply { value = it.toDouble(); render(this) } }
            snapshot.glucoseMgDl?.let { steppers[Field.GLUCOSE]?.apply { value = it; render(this) } }
        }
    }

    private fun summarise(snapshot: VitalsSnapshot): String = buildString {
        append(getString(R.string.vitals_steps, snapshot.steps))
        snapshot.bmi?.let { append("\n").append(getString(R.string.vitals_bmi, VitalsSnapshot.formatBmi(it))) }
        if (snapshot.systolic != null && snapshot.diastolic != null) {
            append("\n").append(getString(R.string.vitals_bp, snapshot.systolic, snapshot.diastolic))
        }
        snapshot.glucoseMgDl?.let { append("\n").append(getString(R.string.vitals_sugar, it.toInt())) }
        if (!snapshot.hasAnyVital) {
            append("\n").append(getString(R.string.vitals_none_yet))
        }
    }

    private fun save() {
        lifecycleScope.launch {
            steppers[Field.WEIGHT]?.let { repository.recordWeight(it.value) }
            steppers[Field.HEIGHT]?.let { repository.recordHeight(it.value) }
            val systolic = steppers[Field.SYSTOLIC]?.value?.toInt()
            val diastolic = steppers[Field.DIASTOLIC]?.value?.toInt()
            if (systolic != null && diastolic != null) {
                repository.recordBloodPressure(systolic, diastolic)
            }
            steppers[Field.GLUCOSE]?.let { repository.recordBloodGlucose(it.value) }

            Toast.makeText(this@VitalsActivity, R.string.vitals_saved, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun advise() {
        val adviceView = findViewById<TextView>(R.id.adviceText)
        adviceView.setText(R.string.vitals_food_thinking)

        lifecycleScope.launch {
            val snapshot = repository.read()
            when (val advice = DietAdvisor().adviseFor(snapshot)) {
                is DietAdvisor.Advice.NoData ->
                    adviceView.setText(R.string.vitals_food_no_data)

                is DietAdvisor.Advice.Suggestions ->
                    adviceView.text = advice.text

                is DietAdvisor.Advice.SeekHelp -> {
                    adviceView.text = advice.spoken
                    notifyCaregiverOfUrgentReading(advice)
                }
            }
        }
    }

    /**
     * An urgent reading is exactly the case the caregiver escalation exists for, so it
     * reuses the same path as a missed medicine check-in.
     *
     * Unlike a missed dose this goes to the *whole* chain at once and is flagged
     * urgent. A blood pressure of 190/125 is not a situation to work down a list for:
     * whoever reads their email first should be the one who acts.
     */
    private suspend fun notifyCaregiverOfUrgentReading(advice: DietAdvisor.Advice.SeekHelp) {
        val contacts = AmiPreferences(applicationContext).getCaregiversOnce()
        EscalationClient().escalate(
            caregiverEmails = contacts.map { it.email },
            medicineName = getString(R.string.vitals_urgent_subject),
            message = advice.findings.joinToString("; ") { "${it.metric}: ${it.summary}" },
            urgent = true
        )
    }
}
