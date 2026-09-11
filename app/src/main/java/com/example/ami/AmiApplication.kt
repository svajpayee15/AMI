package com.example.ami

import android.app.Application
import android.util.Log
import com.example.ami.reminders.MedicineAlarmScheduler
import com.example.ami.reminders.WeeklySummaryScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AmiApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        warnIfBlank(AmiPlannerManager.KEY_NAME, BuildConfig.GEMINI_API_KEY)
        warnIfBlank("AMI_BACKEND_BASE_URL", BuildConfig.AMI_BACKEND_BASE_URL)
        warnIfBlank("AMI_ESCALATION_SHARED_SECRET", BuildConfig.AMI_ESCALATION_SHARED_SECRET)
        rearmAlarms()
    }

    /**
     * Re-arms every alarm on launch.
     *
     * BootReceiver covers reboots, but not the two cases that actually lose alarms in
     * the field: the app being force-stopped (which cancels them all, and which no
     * broadcast reports), and this upgrade, where reminders moved from one alarm per
     * medicine to one per dose. Re-arming is idempotent - FLAG_UPDATE_CURRENT replaces
     * each alarm rather than stacking a duplicate.
     */
    private fun rearmAlarms() {
        appScope.launch {
            try {
                MedicineAlarmScheduler.scheduleAll(this@AmiApplication)
                WeeklySummaryScheduler.schedule(this@AmiApplication)
            } catch (e: Exception) {
                Log.e("AMI_CONFIG", "Could not re-arm alarms on startup", e)
            }
        }
    }

    private fun warnIfBlank(fieldName: String, value: String) {
        if (value.isBlank()) {
            Log.w("AMI_CONFIG", "$fieldName is not set in local.properties - related features will not work.")
        }
    }
}
