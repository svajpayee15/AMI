package com.example.ami.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager alarms don't survive a reboot, so every dose reminder and the weekly
 * caregiver summary must be re-scheduled from Room once the device comes back up.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MedicineAlarmScheduler.scheduleAll(appContext)
                WeeklySummaryScheduler.schedule(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
