package com.example.ami.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ami.data.MedicineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MedicineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val doseId = intent.getLongExtra(MedicineAlarmScheduler.EXTRA_DOSE_ID, -1L)
        if (doseId == -1L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val scheduled = MedicineRepository.getInstance(context).getScheduledDose(doseId)
                if (scheduled != null) {
                    startWellnessCheck(context, doseId)
                    // A fired one-shot exact alarm doesn't repeat - schedule tomorrow's now.
                    MedicineAlarmScheduler.scheduleFor(context, scheduled.dose)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startWellnessCheck(context: Context, doseId: Long) {
        val serviceIntent = Intent(context, AmiWellnessService::class.java).apply {
            putExtra(MedicineAlarmScheduler.EXTRA_DOSE_ID, doseId)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("AMI_ALARM", "Could not start wellness service, falling back to notification", e)
            AmiWellnessService.postFallbackNotification(context, doseId)
        }
    }
}
