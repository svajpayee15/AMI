package com.example.ami.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Health Connect reads and writes.
 *
 * Health Connect replaces Google Fit, whose APIs shut down at the end of 2026 and which
 * has been closed to new developer signups since May 2024. It is on-device and needs no
 * account linking.
 *
 * Note that it is a *store*, not a sensor: blood pressure and glucose only appear here if
 * a paired cuff, a glucometer, or the user's own manual entry put them there.
 */
class HealthConnectSource(private val context: Context) {

    enum class Availability { AVAILABLE, PROVIDER_UPDATE_REQUIRED, UNAVAILABLE }

    fun availability(): Availability {
        // if/else rather than a when on the raw Int: the status constants carry an @IntDef
        // that lint expects every branch of a switch to name, and an exhaustive list here
        // would have to be rewritten each time the library adds a status we treat as
        // unavailable anyway.
        val status = HealthConnectClient.getSdkStatus(context)
        return if (status == HealthConnectClient.SDK_AVAILABLE) {
            Availability.AVAILABLE
        } else if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            Availability.PROVIDER_UPDATE_REQUIRED
        } else {
            Availability.UNAVAILABLE
        }
    }

    private val client: HealthConnectClient?
        get() = try {
            if (availability() == Availability.AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect unavailable", e)
            null
        }

    /**
     * Permissions are checked before every read rather than cached: users can revoke
     * them at any time from the Health Connect settings, without the app being told.
     */
    suspend fun grantedPermissions(): Set<String> = try {
        client?.permissionController?.getGrantedPermissions() ?: emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read granted permissions", e)
        emptySet()
    }

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    suspend fun read(): VitalsSnapshot {
        val granted = grantedPermissions()
        if (granted.isEmpty()) return VitalsSnapshot()

        val weight = latest(WeightRecord::class, granted)?.weight?.inKilograms
        val height = latest(HeightRecord::class, granted)?.height?.inMeters?.times(100)
        val pressure = latest(BloodPressureRecord::class, granted)
        val glucose = latest(BloodGlucoseRecord::class, granted)?.level?.inMilligramsPerDeciliter

        return VitalsSnapshot(
            steps = todaysSteps(granted),
            weightKg = weight,
            heightCm = height,
            systolic = pressure?.systolic?.inMillimetersOfMercury?.toInt(),
            diastolic = pressure?.diastolic?.inMillimetersOfMercury?.toInt(),
            glucoseMgDl = glucose
        )
    }

    private suspend fun todaysSteps(granted: Set<String>): Long {
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) return 0
        val activeClient = client ?: return 0
        return try {
            val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            activeClient.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, Instant.now())
                )
            ).records.sumOf { it.count }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read steps", e)
            0
        }
    }

    /** Most recent record of [type] within the lookback window, or null. */
    private suspend fun <T : Record> latest(type: KClass<T>, granted: Set<String>): T? {
        if (HealthPermission.getReadPermission(type) !in granted) return null
        val activeClient = client ?: return null
        return try {
            activeClient.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS),
                        Instant.now()
                    ),
                    ascendingOrder = false,
                    pageSize = 1
                )
            ).records.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${type.simpleName}", e)
            null
        }
    }

    /** Writes manual entries back so other health apps see them too. */
    suspend fun writeWeight(kg: Double): Boolean = write(
        WeightRecord(
            metadata = Metadata.manualEntry(),
            time = Instant.now(),
            zoneOffset = currentOffset(),
            weight = Mass.kilograms(kg)
        )
    )

    suspend fun writeHeight(cm: Double): Boolean = write(
        HeightRecord(
            metadata = Metadata.manualEntry(),
            time = Instant.now(),
            zoneOffset = currentOffset(),
            height = Length.meters(cm / 100.0)
        )
    )

    suspend fun writeBloodPressure(systolic: Int, diastolic: Int): Boolean = write(
        BloodPressureRecord(
            metadata = Metadata.manualEntry(),
            time = Instant.now(),
            zoneOffset = currentOffset(),
            systolic = Pressure.millimetersOfMercury(systolic.toDouble()),
            diastolic = Pressure.millimetersOfMercury(diastolic.toDouble())
        )
    )

    suspend fun writeBloodGlucose(mgDl: Double): Boolean = write(
        BloodGlucoseRecord(
            metadata = Metadata.manualEntry(),
            time = Instant.now(),
            zoneOffset = currentOffset(),
            level = BloodGlucose.milligramsPerDeciliter(mgDl)
        )
    )

    private suspend fun write(record: Record): Boolean {
        val activeClient = client ?: return false
        return try {
            activeClient.insertRecords(listOf(record))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not write ${record::class.simpleName}", e)
            false
        }
    }

    private fun currentOffset(): ZoneOffset =
        ZoneId.systemDefault().rules.getOffset(Instant.now())

    companion object {
        private const val TAG = "AMI_HEALTH"
        private const val LOOKBACK_DAYS = 90L

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getWritePermission(HeightRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getWritePermission(BloodGlucoseRecord::class)
        )
    }
}
