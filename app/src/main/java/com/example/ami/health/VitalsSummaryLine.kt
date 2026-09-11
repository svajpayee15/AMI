package com.example.ami.health

import android.content.Context
import java.util.Locale

/**
 * One plain-English line of the latest readings, for the weekly caregiver email.
 *
 * Deliberately not localised through string resources: this text is emailed, and the
 * phone's locale is the *user's*, not necessarily the reader's. Only readings that
 * actually exist appear - an email that says "Blood pressure: null" is worse than one
 * that says nothing about blood pressure.
 */
object VitalsSummaryLine {

    suspend fun forEmail(context: Context): String? {
        val snapshot = try {
            HealthRepository(context.applicationContext).read()
        } catch (e: Exception) {
            return null
        }
        return format(snapshot)
    }

    fun format(snapshot: VitalsSnapshot): String? {
        val parts = mutableListOf<String>()
        if (snapshot.steps > 0) parts.add("${snapshot.steps} steps today")
        snapshot.bmi?.let { parts.add("BMI ${VitalsSnapshot.formatBmi(it)}") }
        if (snapshot.systolic != null && snapshot.diastolic != null) {
            parts.add("blood pressure ${snapshot.systolic}/${snapshot.diastolic}")
        }
        snapshot.glucoseMgDl?.let {
            parts.add("blood sugar ${String.format(Locale.US, "%.0f", it)} mg/dL")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
