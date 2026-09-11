package com.example.ami.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.vitalsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ami_vitals")

/**
 * On-device fallback for manually entered vitals.
 *
 * Health Connect is the preferred home for these, but it isn't always usable - the
 * platform may be too old, the provider may be missing, or the user may simply have
 * declined the permissions. Manual entry has to keep working in all of those cases,
 * otherwise the diet advice has nothing to run on for exactly the users least likely
 * to own a smart scale or a Bluetooth cuff.
 */
class LocalVitalsStore(private val context: Context) {

    suspend fun read(): VitalsSnapshot {
        val prefs = context.vitalsDataStore.data.first()
        return VitalsSnapshot(
            steps = 0,
            weightKg = prefs[WEIGHT_KG],
            heightCm = prefs[HEIGHT_CM],
            systolic = prefs[SYSTOLIC],
            diastolic = prefs[DIASTOLIC],
            glucoseMgDl = prefs[GLUCOSE]
        )
    }

    suspend fun setWeight(kg: Double) = context.vitalsDataStore.edit { it[WEIGHT_KG] = kg }

    suspend fun setHeight(cm: Double) = context.vitalsDataStore.edit { it[HEIGHT_CM] = cm }

    suspend fun setBloodPressure(systolic: Int, diastolic: Int) =
        context.vitalsDataStore.edit {
            it[SYSTOLIC] = systolic
            it[DIASTOLIC] = diastolic
        }

    suspend fun setBloodGlucose(mgDl: Double) = context.vitalsDataStore.edit { it[GLUCOSE] = mgDl }

    private companion object {
        val WEIGHT_KG = doublePreferencesKey("weight_kg")
        val HEIGHT_CM = doublePreferencesKey("height_cm")
        val SYSTOLIC = intPreferencesKey("systolic")
        val DIASTOLIC = intPreferencesKey("diastolic")
        val GLUCOSE = doublePreferencesKey("glucose_mgdl")
    }
}
