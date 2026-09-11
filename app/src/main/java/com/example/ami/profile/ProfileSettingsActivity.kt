package com.example.ami.profile

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.SettingsActivity
import com.example.ami.data.AmiDatabase
import com.example.ami.data.AmiPreferences
import com.example.ami.health.HealthConnectSource
import com.example.ami.health.HealthRepository
import com.example.ami.navigation.AmiNavBar
import com.example.ami.navigation.AmiTab
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, built from the Figma "Profile - Settings" frame.
 *
 * The source frame is iOS, and two of its assumptions do not survive the move:
 *
 *  - "Apple Health" is HealthKit, which does not exist on Android. The equivalent is
 *    Health Connect, which this app already reads vitals through, so that is what the
 *    row connects to and what the wearable sheet offers.
 *  - There is no account. "Log out" is therefore absent rather than dead, and "Delete
 *    account" is "Delete all my data" - which is the honest description anyway, since
 *    the data is all local and there is no server copy to leave behind.
 *
 * The two switch rows report state this screen does not own. Health Connect permissions
 * and the notification permission are both the system's to grant, so tapping either
 * hands off to the right system surface rather than pretending to flip it here.
 */
class ProfileSettingsActivity : AppCompatActivity() {

    private lateinit var preferences: AmiPreferences
    private lateinit var health: HealthRepository

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        // Partial grants are fine - whatever was allowed still reads. Rebuild either way
        // so the switch matches what was actually granted, not what was asked for.
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmiNavBar.setContentView(this, R.layout.activity_profile_settings, AmiTab.SETTINGS)
        preferences = AmiPreferences(this)
        health = HealthRepository(this)
    }

    override fun onResume() {
        super.onResume()
        // Both switches can change while the user is away in system settings.
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val healthGranted = health.hasHealthConnectPermissions()
            val healthAvailable =
                health.healthConnectAvailability() == HealthConnectSource.Availability.AVAILABLE
            buildAccountGroup(healthGranted, healthAvailable)
            buildAboutGroup()
            buildDangerGroup()
        }
    }

    private fun buildAccountGroup(healthGranted: Boolean, healthAvailable: Boolean) {
        val rows = SettingsRows(findViewById<LinearLayout>(R.id.groupAccount).also { it.removeAllViews() })

        rows.navigation(R.string.profile_edit_profile) {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
        rows.navigation(R.string.profile_account_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        rows.navigation(R.string.profile_link_wearables) {
            ConnectWearableSheet().show(supportFragmentManager, ConnectWearableSheet.TAG)
        }
        rows.navigation(
            R.string.profile_your_activity,
            value = getString(R.string.profile_your_activity_value)
        ) {
            // Report has its own tab, so go through the bar rather than stacking a
            // second copy of it on top of whichever one may already be open.
            AmiNavBar.open(this, AmiTab.REPORT)
        }
        rows.toggle(R.string.profile_health_connect, checked = healthGranted) {
            if (!healthAvailable) {
                ConnectWearableSheet().show(supportFragmentManager, ConnectWearableSheet.TAG)
            } else if (healthGranted) {
                // Granted permissions can only be taken back by the user, inside Health
                // Connect itself - there is no API to revoke one's own, and a switch that
                // silently did nothing when turned off would be a lie.
                startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } else {
                healthPermissionLauncher.launch(HealthRepository.REQUIRED_PERMISSIONS)
            }
        }
        rows.toggle(R.string.profile_notifications, checked = notificationsEnabled()) {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun buildAboutGroup() {
        val rows = SettingsRows(findViewById<LinearLayout>(R.id.groupAbout).also { it.removeAllViews() })

        InfoActivity.Page.entries.forEach { page ->
            rows.navigation(page.titleRes) { startActivity(InfoActivity.intent(this, page)) }
        }
    }

    private fun buildDangerGroup() {
        val rows = SettingsRows(findViewById<LinearLayout>(R.id.groupDanger).also { it.removeAllViews() })

        rows.action(R.string.profile_delete_data, titleColorRes = R.color.ami_danger) {
            confirmDelete()
        }
    }

    /**
     * Two-step, and the confirming button is the one that has to be reached for.
     *
     * This wipes a medicine schedule someone may depend on, so the dialog names what
     * goes and the cancel option is phrased as a choice to keep rather than as "no".
     */
    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_delete_title)
            .setMessage(R.string.profile_delete_message)
            .setNegativeButton(R.string.profile_delete_cancel, null)
            .setPositiveButton(R.string.profile_delete_confirm) { _, _ -> deleteEverything() }
            .show()
    }

    private fun deleteEverything() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AmiDatabase.getInstance(this@ProfileSettingsActivity).clearAllTables()
                preferences.clearAll()
            }
            Snackbar.make(
                findViewById(R.id.groupDanger),
                R.string.profile_delete_done,
                Snackbar.LENGTH_LONG
            ).show()
            refresh()
        }
    }
}
