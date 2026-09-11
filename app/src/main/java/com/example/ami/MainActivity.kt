package com.example.ami

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.ami.care.AdherenceReport
import com.example.ami.data.AmiPreferences
import com.example.ami.data.MedicineDose
import com.example.ami.data.MedicineRepository
import com.example.ami.data.MedicineWithDoses
import com.example.ami.games.GamesActivity
import com.example.ami.health.HealthRepository
import com.example.ami.navigation.AmiNavBar
import com.example.ami.navigation.AmiTab
import com.example.ami.wellbeing.WellbeingActivity
import com.example.ami.health.VitalsSummaryLine
import com.example.ami.reminders.MedicineAlarmScheduler
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var careManager: AmiCareManager
    private lateinit var preferences: AmiPreferences

    /** Chosen in the time picker; null until the user picks one. */
    private var pickedHour: Int? = null
    private var pickedMinute: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmiNavBar.setContentView(this, R.layout.activity_main, AmiTab.HOME)

        careManager = AmiCareManager.getInstance(this)
        preferences = AmiPreferences(this)

        setupButtons()
        observeMedList()
        observeCaregivers()
    }

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btnEnableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialButton>(R.id.btnPickTime).setOnClickListener { showTimePicker() }

        findViewById<MaterialButton>(R.id.btnAddMed).setOnClickListener { addDose() }

        // The cards that lead to another tab go through the bar's own navigation so
        // they land exactly where tapping that tab would, rather than opening a second
        // copy of the screen on top of the one the bar would have reused.
        findViewById<MaterialButton>(R.id.btnOpenVitals).setOnClickListener {
            AmiNavBar.open(this, AmiTab.HEALTH)
        }

        // The wellbeing story is not a tab of its own; it sits under Health.
        findViewById<MaterialButton>(R.id.btnOpenWellbeing).setOnClickListener {
            startActivity(Intent(this, WellbeingActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnOpenHistory).setOnClickListener {
            AmiNavBar.open(this, AmiTab.REPORT)
        }

        findViewById<MaterialButton>(R.id.btnOpenGames).setOnClickListener {
            startActivity(Intent(this, GamesActivity::class.java))
        }

        val openSettings = View.OnClickListener {
            // The grouped Settings screen is now the entry point; the older
            // caregiver/phone/summary screen hangs off it as "Care settings".
            AmiNavBar.open(this, AmiTab.SETTINGS)
        }
        findViewById<ImageButton>(R.id.btnOpenSettings).setOnClickListener(openSettings)
        findViewById<MaterialButton>(R.id.btnManageCaregivers).setOnClickListener(openSettings)
    }

    private fun showTimePicker() {
        val now = LocalTime.now()
        TimePickerDialog(
            this,
            { _, hour, minute ->
                pickedHour = hour
                pickedMinute = minute
                findViewById<MaterialButton>(R.id.btnPickTime).text = displayTime(hour, minute)
            },
            pickedHour ?: now.hour,
            pickedMinute ?: 0,
            android.text.format.DateFormat.is24HourFormat(this)
        ).show()
    }

    /**
     * Adds one time to a medicine, creating the medicine if the name is new.
     *
     * Adding the same name twice with different times is the supported way to schedule
     * a twice-daily dose - the name field is deliberately left filled in afterwards so
     * the second time is one tap and one picker away.
     */
    private fun addDose() {
        val nameField = findViewById<EditText>(R.id.etMedName)
        val dosageField = findViewById<EditText>(R.id.etMedDosage)
        val name = nameField.text.toString().trim()
        val dosage = dosageField.text.toString().trim().takeIf { it.isNotEmpty() }
        val hour = pickedHour
        val minute = pickedMinute

        if (name.isBlank()) {
            toast(R.string.toast_medicine_invalid_name)
            return
        }
        if (hour == null || minute == null) {
            toast(R.string.toast_medicine_invalid_time)
            return
        }

        // Stored zero-padded 24h because MedicineAlarmScheduler parses it as HH:MM.
        val storedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
        lifecycleScope.launch {
            val dose = careManager.addDose(name, storedTime, dosage)
            if (dose == null) {
                toast(R.string.toast_medicine_duplicate)
                return@launch
            }
            MedicineAlarmScheduler.scheduleFor(this@MainActivity, dose)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_medicine_added, name, displayTime(hour, minute)),
                Toast.LENGTH_SHORT
            ).show()

            // Only the time is cleared: the common next action is a second dose of the
            // same medicine, so retyping the name and dosage would be busywork.
            pickedHour = null
            pickedMinute = null
            findViewById<MaterialButton>(R.id.btnPickTime).setText(R.string.home_medicine_time_placeholder)
        }
    }

    private fun confirmRemoveDose(medicineName: String, dose: MedicineDose) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.home_medicine_remove))
            .setMessage(getString(R.string.home_medicine_remove_message, medicineName, displayTime(dose.time)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.home_medicine_remove) { _, _ ->
                lifecycleScope.launch {
                    MedicineAlarmScheduler.cancelFor(this@MainActivity, dose.id)
                    careManager.deleteDose(dose)
                }
                Toast.makeText(
                    this,
                    getString(R.string.toast_medicine_removed, medicineName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun observeCaregivers() {
        lifecycleScope.launch {
            preferences.caregivers.collect { contacts ->
                val summary = findViewById<TextView>(R.id.homeCaregiverSummary)
                summary.text = when {
                    contacts.isEmpty() -> getString(R.string.home_caregiver_none)
                    contacts.size == 1 -> getString(R.string.home_caregiver_one, contacts.first().displayName)
                    else -> resources.getQuantityString(
                        R.plurals.home_caregiver_many,
                        contacts.size - 1,
                        contacts.first().displayName,
                        contacts.size - 1
                    )
                }
            }
        }
    }

    private fun observeMedList() {
        lifecycleScope.launch {
            careManager.observeMedicines().collect { medicines -> updateMedList(medicines) }
        }
    }

    private fun updateMedList(medicines: List<MedicineWithDoses>) {
        val container = findViewById<LinearLayout>(R.id.medicineListContainer)
        val empty = findViewById<TextView>(R.id.tvMedListEmpty)

        container.removeAllViews()

        // One row per dose, in clock order across all medicines, because that is the
        // order the day actually happens in.
        val rows = medicines
            .flatMap { entry -> entry.orderedDoses.map { entry.medicine to it } }
            .sortedBy { (_, dose) -> dose.time }

        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.space_sm)

        rows.forEachIndexed { index, (medicine, dose) ->
            val row = inflater.inflate(R.layout.item_medicine, container, false)

            row.findViewById<TextView>(R.id.medicineName).text = medicine.name
            row.findViewById<TextView>(R.id.medicineDosage).apply {
                text = medicine.dosage.orEmpty()
                visibility = if (medicine.dosage.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.medicineTime).text = displayTime(dose.time)
            row.findViewById<TextView>(R.id.medicineTakenBadge).visibility =
                if (dose.isTakenToday) View.VISIBLE else View.GONE

            row.findViewById<ImageButton>(R.id.btnRemoveMedicine).apply {
                contentDescription = getString(
                    R.string.home_medicine_remove_description, medicine.name, displayTime(dose.time)
                )
                setOnClickListener { confirmRemoveDose(medicine.name, dose) }
            }

            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            }
            container.addView(row)
        }
    }

    /** "08:00" -> "8:00 AM" (or "08:00" where the locale uses 24h time). */
    private fun displayTime(stored: String): String {
        return try {
            val parts = stored.split(":")
            displayTime(parts[0].toInt(), parts[1].toInt())
        } catch (e: Exception) {
            stored
        }
    }

    private fun displayTime(hour: Int, minute: Int): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        refreshVitalsSummary()
        refreshAdherenceSummary()
        requestNextMissingPermission()
    }

    private fun refreshVitalsSummary() {
        lifecycleScope.launch {
            val snapshot = HealthRepository(this@MainActivity).read()
            findViewById<TextView>(R.id.homeVitalsSummary).text =
                VitalsSummaryLine.format(snapshot) ?: getString(R.string.vitals_none_yet)
        }
    }

    /** The last seven days including today, which is what a glance actually wants. */
    private fun refreshAdherenceSummary() {
        lifecycleScope.launch {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(6)
            val records = MedicineRepository.getInstance(this@MainActivity).checkInsBetween(
                fromEpochMilli = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                toEpochMilli = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
            val report = AdherenceReport.of(records, from, today, zone)
            val percent = report.adherencePercent

            findViewById<TextView>(R.id.homeAdherenceSummary).text = when {
                percent == null -> getString(R.string.report_no_data)
                report.missed == 0 ->
                    resources.getQuantityString(R.plurals.report_all_taken, report.taken, report.taken)
                else -> resources.getQuantityString(
                    R.plurals.report_summary, report.total, percent, report.taken, report.total
                )
            }
        }
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        val pill = findViewById<TextView>(R.id.serviceStatusPill)
        val body = findViewById<TextView>(R.id.serviceStatusBody)
        val button = findViewById<MaterialButton>(R.id.btnEnableService)

        if (enabled) {
            pill.setText(R.string.home_service_active)
            pill.setBackgroundResource(R.drawable.bg_pill_success)
            pill.setTextColor(ContextCompat.getColor(this, R.color.ami_on_success_container))
            body.setText(R.string.home_service_body_active)
            button.visibility = View.GONE
        } else {
            pill.setText(R.string.home_service_inactive)
            pill.setBackgroundResource(R.drawable.bg_pill_warning)
            pill.setTextColor(ContextCompat.getColor(this, R.color.ami_on_warning_container))
            body.setText(R.string.home_service_body_inactive)
            button.visibility = View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = android.content.ComponentName(this, AmiAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expectedComponentName.flattenToString()) == true
    }

    /**
     * Asks for one thing at a time.
     *
     * Each of these last three is a trip out to a different system settings screen.
     * Firing them together on first launch bounces the user through three unfamiliar
     * screens in a row, which is exactly the confusion this app exists to prevent - so
     * only the first outstanding one is requested per visit, and onResume picks up the
     * next once they come back.
     */
    private fun requestNextMissingPermission() {
        val runtimePermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runtimePermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // These are in-app dialogs rather than screen changes, so they can be batched.
        if (runtimePermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, runtimePermissions.toTypedArray(), RUNTIME_PERMISSION_REQ_CODE
            )
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        "package:$packageName".toUri()
                    )
                )
                return
            }
        }

        // Without this the check-in cannot ring over the lock screen and falls back to
        // an ordinary notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager != null && !notificationManager.canUseFullScreenIntent()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        "package:$packageName".toUri()
                    )
                )
                return
            }
        }
    }

    companion object {
        private const val RUNTIME_PERMISSION_REQ_CODE = 123
    }
}
