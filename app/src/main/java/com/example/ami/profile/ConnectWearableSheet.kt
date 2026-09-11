package com.example.ami.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.ami.R
import com.example.ami.health.HealthConnectSource
import com.example.ami.health.HealthRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * The "Sync your health" sheet, from the Figma "Profile - Connect Smartwear" frame.
 *
 * The design shows an Apple Watch and a "Connect Apple Health" button. Neither ports:
 * HealthKit does not exist on Android, and shipping Apple product imagery in an Android
 * app is not an option. The Android equivalent is Health Connect - a store any wearable
 * can write into - so the sheet offers that, with a generic wearable illustration.
 *
 * What the button does depends on what is actually installed, checked each time the
 * sheet opens rather than assumed:
 *
 *  - Health Connect missing or too old -> send them to install or update it.
 *  - Present but not permitted -> request the permissions.
 *  - Already permitted -> say so, and offer nothing further.
 */
class ConnectWearableSheet : BottomSheetDialogFragment() {

    private lateinit var health: HealthRepository

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        refresh()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_connect_wearable, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        health = HealthRepository(requireContext())

        view.findViewById<View>(R.id.btnCloseSheet).setOnClickListener { dismiss() }
        refresh()
    }

    private fun refresh() {
        val view = view ?: return
        val body = view.findViewById<TextView>(R.id.sheetBody)
        val action = view.findViewById<MaterialButton>(R.id.btnConnect)

        lifecycleScope.launch {
            when (health.healthConnectAvailability()) {
                HealthConnectSource.Availability.UNAVAILABLE -> {
                    body.setText(R.string.connect_sheet_unavailable)
                    action.setText(R.string.connect_sheet_install)
                    action.setOnClickListener { openHealthConnectListing() }
                }

                HealthConnectSource.Availability.PROVIDER_UPDATE_REQUIRED -> {
                    body.setText(R.string.connect_sheet_body)
                    action.setText(R.string.connect_sheet_install)
                    action.setOnClickListener { openHealthConnectListing() }
                }

                HealthConnectSource.Availability.AVAILABLE -> {
                    if (health.hasHealthConnectPermissions()) {
                        body.setText(R.string.connect_sheet_connected)
                        action.visibility = View.GONE
                    } else {
                        body.setText(R.string.connect_sheet_body)
                        action.visibility = View.VISIBLE
                        action.setText(R.string.connect_sheet_action)
                        action.setOnClickListener {
                            permissionLauncher.launch(HealthRepository.REQUIRED_PERMISSIONS)
                        }
                    }
                }
            }
        }
    }

    /** Falls back to the web listing on a device with no Play Store. */
    private fun openHealthConnectListing() {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$HEALTH_CONNECT_PACKAGE".toUri())
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE".toUri()
                )
            )
        }
    }

    companion object {
        const val TAG = "connect_wearable"

        private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
    }
}
