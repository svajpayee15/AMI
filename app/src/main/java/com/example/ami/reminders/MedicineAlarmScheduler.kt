package com.example.ami.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.ami.data.MedicineDose
import com.example.ami.data.MedicineRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One exact alarm per *dose*, not per medicine.
 *
 * A medicine due morning and evening is two alarms; keying them on the medicine would
 * mean the second one overwrote the first, because a PendingIntent is identified by its
 * request code.
 */
object MedicineAlarmScheduler {

    private const val TAG = "AMI_ALARM"

    /** Kept for the extras key so upgrades read the same name in logs. */
    const val EXTRA_DOSE_ID = "extra_dose_id"

    fun scheduleFor(context: Context, dose: MedicineDose) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerMillis = nextTriggerMillis(dose.time) ?: run {
            Log.w(TAG, "Dose ${dose.id} has an unparseable time '${dose.time}', not scheduling")
            return
        }
        val pendingIntent = buildPendingIntent(context, dose.id)

        try {
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                Log.w(TAG, "Exact alarms not permitted, scheduling inexact alarm for dose ${dose.id}")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException scheduling exact alarm, falling back to inexact", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        }
    }

    fun cancelFor(context: Context, doseId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context, doseId))
    }

    /**
     * Re-arms every dose. Safe to call repeatedly - FLAG_UPDATE_CURRENT replaces the
     * existing alarm rather than stacking a second one. Run on boot and on app start,
     * which is also what repairs alarms left over from the single-dose-per-medicine
     * scheme after an upgrade.
     */
    suspend fun scheduleAll(context: Context) {
        MedicineRepository.getInstance(context).getAllDoses().forEach { scheduleFor(context, it) }
    }

    private fun buildPendingIntent(context: Context, doseId: Long): PendingIntent {
        val intent = Intent(context, MedicineAlarmReceiver::class.java).apply {
            putExtra(EXTRA_DOSE_ID, doseId)
        }
        return PendingIntent.getBroadcast(
            context,
            doseId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Null rather than a throw: one malformed row must not stop every other alarm. */
    private fun nextTriggerMillis(time: String): Long? {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val now = LocalDateTime.now()
        var trigger = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        if (!trigger.isAfter(now)) {
            trigger = trigger.plusDays(1)
        }
        return trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
