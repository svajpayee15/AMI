package com.example.ami.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ami.care.WeeklySummarySender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WeeklySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WeeklySummarySender.sendIfDue(appContext)
            } catch (e: Exception) {
                Log.e("AMI_SUMMARY", "Weekly summary failed", e)
            } finally {
                // Re-armed whatever happened: a one-shot alarm that threw would
                // otherwise end the weekly digest permanently.
                WeeklySummaryScheduler.schedule(appContext)
                pendingResult.finish()
            }
        }
    }
}
