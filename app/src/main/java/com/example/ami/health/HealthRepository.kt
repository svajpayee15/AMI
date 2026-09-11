package com.example.ami.health

import android.content.Context

/**
 * Single entry point for vitals.
 *
 * Reads prefer Health Connect and fall back per-field to whatever the user typed in, so
 * a household with a step-counting phone but no BP cuff still gets a complete picture.
 * Writes go to both, so entering a value manually here also publishes it to any other
 * health app the user has.
 */
class HealthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val healthConnect = HealthConnectSource(appContext)
    private val local = LocalVitalsStore(appContext)

    fun healthConnectAvailability(): HealthConnectSource.Availability = healthConnect.availability()

    suspend fun hasHealthConnectPermissions(): Boolean = healthConnect.hasAllPermissions()

    suspend fun read(): VitalsSnapshot {
        val connect = healthConnect.read()
        val manual = local.read()
        return VitalsSnapshot(
            steps = connect.steps,
            weightKg = connect.weightKg ?: manual.weightKg,
            heightCm = connect.heightCm ?: manual.heightCm,
            systolic = connect.systolic ?: manual.systolic,
            diastolic = connect.diastolic ?: manual.diastolic,
            glucoseMgDl = connect.glucoseMgDl ?: manual.glucoseMgDl
        )
    }

    suspend fun recordWeight(kg: Double) {
        local.setWeight(kg)
        healthConnect.writeWeight(kg)
    }

    suspend fun recordHeight(cm: Double) {
        local.setHeight(cm)
        healthConnect.writeHeight(cm)
    }

    suspend fun recordBloodPressure(systolic: Int, diastolic: Int) {
        local.setBloodPressure(systolic, diastolic)
        healthConnect.writeBloodPressure(systolic, diastolic)
    }

    suspend fun recordBloodGlucose(mgDl: Double) {
        local.setBloodGlucose(mgDl)
        healthConnect.writeBloodGlucose(mgDl)
    }

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = HealthConnectSource.REQUIRED_PERMISSIONS
    }
}
