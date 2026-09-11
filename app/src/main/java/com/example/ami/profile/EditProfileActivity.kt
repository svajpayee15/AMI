package com.example.ami.profile

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.data.AmiPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Name and date of birth, stored on this device only.
 *
 * Both fields are optional and neither gates anything. This app has no account, so a
 * profile here is a courtesy - it lets AMI use the person's name - and the screen says
 * so plainly rather than implying a record exists somewhere.
 */
class EditProfileActivity : AppCompatActivity() {

    private lateinit var preferences: AmiPreferences

    private var dateOfBirth: LocalDate? = null

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)
        preferences = AmiPreferences(this)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnChooseDob).setOnClickListener { pickDate() }
        findViewById<MaterialButton>(R.id.btnClearDob).setOnClickListener {
            dateOfBirth = null
            renderDate()
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        lifecycleScope.launch {
            findViewById<EditText>(R.id.profileName).setText(preferences.getDisplayNameOnce())
            dateOfBirth = preferences.getDateOfBirthOnce()?.let { LocalDate.ofEpochDay(it) }
            renderDate()
        }
    }

    private fun renderDate() {
        val value = dateOfBirth
        findViewById<TextView>(R.id.profileDobValue).text =
            value?.format(dateFormat) ?: getString(R.string.profile_dob_placeholder)
        // Nothing to clear until something is set.
        findViewById<View>(R.id.btnClearDob).visibility =
            if (value == null) View.GONE else View.VISIBLE
    }

    private fun pickDate() {
        // Opens on the existing value, or on a plausible year for this app's users rather
        // than on today - scrolling back from today would take eighty taps.
        val start = dateOfBirth ?: LocalDate.now().minusYears(DEFAULT_AGE)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                dateOfBirth = LocalDate.of(year, month + 1, day)
                renderDate()
            },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth
        ).apply {
            // A date of birth cannot be in the future.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun save() {
        val name = findViewById<EditText>(R.id.profileName).text.toString()
        lifecycleScope.launch {
            preferences.setDisplayName(name)
            preferences.setDateOfBirth(dateOfBirth?.toEpochDay())
            Snackbar.make(
                findViewById(R.id.profileName),
                R.string.profile_saved,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        /** Where the date picker opens when nothing is set yet. */
        const val DEFAULT_AGE = 70L
    }
}
