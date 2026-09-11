package com.example.ami.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.ami.care.WeeklySummarySender
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Fires the caregiver digest on Sunday evening, once the week it reports on has closed.
 *
 * Inexact on purpose. A weekly newsletter does not need to arrive at 18:00:00, and
 * `setExactAndAllowWhileIdle` on a non-urgent alarm spends the app's exact-alarm budget
 * and wakes the device for nothing. [WeeklySummarySender] re-checks whether a week has
 * actually passed, so a late or coalesced delivery still sends exactly one summary.
 */
object WeeklySummaryScheduler {

    /** Well clear of the dose-id range that medicine alarms use as request codes. */
    private const val REQUEST_CODE = 970_001

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextSendMillis(),
            pendingIntent(context)
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WeeklySummaryReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun nextSendMillis(): Long {
        val now = LocalDateTime.now()
        var next = now.with(DayOfWeek.SUNDAY).with(WeeklySummarySender.SEND_TIME)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
