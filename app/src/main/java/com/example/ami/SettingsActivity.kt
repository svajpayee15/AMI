package com.example.ami

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ami.care.EscalationPlan
import com.example.ami.care.WeeklySummarySender
import com.example.ami.data.AmiPreferences
import com.example.ami.data.CaregiverContact
import com.example.ami.data.CaregiverContacts
import com.example.ami.reminders.WeeklySummaryScheduler
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

/**
 * The caregiver's setup screen: who gets told, on which number, and how often.
 *
 * Replaces an iOS-styled mock whose rows ("Edit profile", "Apple Health", "Log out")
 * led nowhere, and which was the last screen still off the app's own design system.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var preferences: AmiPreferences

    /** Mirror of what is stored, so reordering and removal can edit and re-save. */
    private var contacts: List<CaregiverContact> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        preferences = AmiPreferences(this)

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnAddCaregiver).setOnClickListener { addCaregiver() }
        findViewById<MaterialButton>(R.id.btnSavePhone).setOnClickListener { savePhone() }
        findViewById<MaterialButton>(R.id.btnSendSummaryNow).setOnClickListener { sendSummaryNow() }
        findViewById<MaterialButton>(R.id.btnOpenHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        observeCaregivers()
        loadPhone()
        bindWeeklySummarySwitch()
    }

    // --- Caregiver chain ---------------------------------------------------------

    private fun observeCaregivers() {
        lifecycleScope.launch {
            preferences.caregivers.collect { stored ->
                contacts = stored
                renderCaregivers()
            }
        }
    }

    private fun renderCaregivers() {
        val container = findViewById<LinearLayout>(R.id.caregiversContainer)
        val empty = findViewById<TextView>(R.id.caregiversEmpty)

        container.removeAllViews()
        empty.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.space_sm)

        contacts.forEachIndexed { index, contact ->
            val row = inflater.inflate(R.layout.item_caregiver, container, false)

            row.findViewById<TextView>(R.id.caregiverPosition).text =
                getString(R.string.caregiver_position, index + 1)
            row.findViewById<TextView>(R.id.caregiverName).text = contact.displayName
            row.findViewById<TextView>(R.id.caregiverEmail).text = contact.email
            row.findViewById<TextView>(R.id.caregiverRole).setText(roleFor(index))

            row.findViewById<ImageButton>(R.id.btnMoveUp).apply {
                // The first contact has nowhere to move to; a disabled-looking button
                // that still reacts is worse than one that is not there.
                visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
                setOnClickListener { moveUp(index) }
            }
            row.findViewById<ImageButton>(R.id.btnRemoveCaregiver).apply {
                contentDescription = getString(R.string.settings_caregiver_remove) + " " + contact.displayName
                setOnClickListener { remove(index) }
            }

            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = gap
            }
            container.addView(row)
        }
    }

    /** Spells out what each position in the chain actually means, per [EscalationPlan]. */
    private fun roleFor(index: Int): Int = when (index) {
        0 -> R.string.settings_caregiver_role_primary
        1 -> R.string.settings_caregiver_role_second
        else -> R.string.settings_caregiver_role_later
    }

    private fun addCaregiver() {
        val nameField = findViewById<EditText>(R.id.etCaregiverName)
        val emailField = findViewById<EditText>(R.id.etCaregiverEmail)
        val name = nameField.text.toString().trim()
        val email = emailField.text.toString().trim()

        if (!CaregiverContacts.isValidEmail(email)) {
            toast(R.string.toast_email_invalid)
            return
        }
        if (contacts.any { it.email.equals(email, ignoreCase = true) }) {
            toast(R.string.toast_caregiver_duplicate)
            return
        }

        save(contacts + CaregiverContact(name, email))
        nameField.text.clear()
        emailField.text.clear()
        toast(R.string.toast_caregiver_added)
    }

    private fun moveUp(index: Int) {
        if (index <= 0) return
        val reordered = contacts.toMutableList()
        reordered.add(index - 1, reordered.removeAt(index))
        save(reordered)
    }

    private fun remove(index: Int) {
        save(contacts.filterIndexed { i, _ -> i != index })
    }

    private fun save(updated: List<CaregiverContact>) {
        contacts = updated
        renderCaregivers()
        lifecycleScope.launch { preferences.setCaregivers(updated) }
    }

    // --- Phone number ------------------------------------------------------------

    private fun loadPhone() {
        lifecycleScope.launch {
            preferences.userPhoneNumber.collect { phone ->
                val field = findViewById<EditText>(R.id.etUserPhone)
                if (field.text.isEmpty() && phone.isNotEmpty()) field.setText(phone)
            }
        }
    }

    private fun savePhone() {
        val phone = findViewById<EditText>(R.id.etUserPhone).text.toString().trim()
        // Twilio only dials E.164, so a number without a country code would fail
        // silently at the point it is needed most.
        if (phone.isNotEmpty() && !E164.matches(phone)) {
            toast(R.string.toast_phone_invalid)
            return
        }
        lifecycleScope.launch { preferences.setUserPhoneNumber(phone) }
        toast(R.string.toast_phone_saved)
    }

    // --- Weekly summary ----------------------------------------------------------

    private fun bindWeeklySummarySwitch() {
        val toggle = findViewById<SwitchMaterial>(R.id.switchWeeklySummary)
        lifecycleScope.launch {
            toggle.isChecked = preferences.isWeeklySummaryEnabledOnce()
            toggle.setOnCheckedChangeListener { _, enabled ->
                lifecycleScope.launch {
                    preferences.setWeeklySummaryEnabled(enabled)
                    // The alarm is cancelled outright rather than left to fire and be
                    // ignored, so switching this off also stops waking the device.
                    if (enabled) {
                        WeeklySummaryScheduler.schedule(this@SettingsActivity)
                    } else {
                        WeeklySummaryScheduler.cancel(this@SettingsActivity)
                    }
                }
            }
        }
    }

    private fun sendSummaryNow() {
        val button = findViewById<MaterialButton>(R.id.btnSendSummaryNow)
        button.isEnabled = false
        lifecycleScope.launch {
            val sent = try {
                WeeklySummarySender.sendIfDue(this@SettingsActivity, force = true)
            } catch (e: Exception) {
                false
            }
            button.isEnabled = true
            toast(if (sent) R.string.toast_summary_sent else R.string.toast_summary_failed)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    companion object {
        /** E.164: leading + and 7-15 digits, matching what the backend accepts. */
        private val E164 = Regex("""\+[1-9]\d{6,14}""")
    }
}
