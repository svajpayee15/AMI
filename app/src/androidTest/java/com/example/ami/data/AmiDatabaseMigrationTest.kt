package com.example.ami.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves each migration keeps the user's data intact.
 *
 * Migrations are the one change in the app that can destroy user data. 2 -> 3 splits a
 * medicine's single time into a `medicine_doses` row and rebuilds three tables; if it
 * drops a row or leaves the schema one column away from what Room expects, the user
 * either loses their reminders or the app crashes on first open after the update.
 * Neither is something to find out in the field. 3 -> 4 and 4 -> 5 only add a table, and
 * are tested for the opposite property: that everything already there is still there
 * afterwards.
 *
 * Instrumented rather than local: it runs real SQLite against the schemas KSP exported,
 * so it needs a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class AmiDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AmiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate2To3_movesEachMedicineTimeIntoItsOwnDose() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO medicines (id, name, time, lastTakenEpochDay) " +
                    "VALUES (1, 'Metformin', '08:00', 20000)"
            )
            db.execSQL(
                "INSERT INTO medicines (id, name, time, lastTakenEpochDay) " +
                    "VALUES (2, 'Warfarin', '20:00', NULL)"
            )
            db.execSQL(
                "INSERT INTO check_ins (medicineId, medicineName, outcome, wellbeingNote, timestampEpochMilli) " +
                    "VALUES (1, 'Metformin', 'TAKEN', 'a bit tired', 1700000000000)"
            )
        }

        // runMigrationsAndValidate compares the result against the exported v3 schema,
        // so a column or index that does not match fails here rather than on a user's phone.
        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, *AmiDatabase.ALL_MIGRATIONS)

        db.query("SELECT medicineId, time, lastTakenEpochDay FROM medicine_doses ORDER BY time").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("08:00", cursor.getString(1))
            assertEquals(20000L, cursor.getLong(2))

            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals("20:00", cursor.getString(1))
            assertTrue(cursor.isNull(2))
        }

        db.query("SELECT name, dosage FROM medicines ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Metformin", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }

        // Existing history survives, with the new columns filled in as "not known".
        db.query("SELECT medicineName, outcome, wellbeingNote, doseId, scheduledTime FROM check_ins").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Metformin", cursor.getString(0))
            assertEquals("TAKEN", cursor.getString(1))
            assertEquals("a bit tired", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))
            assertEquals("", cursor.getString(4))
        }

        db.query("SELECT COUNT(*) FROM action_log").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    /** Deleting a medicine must take its doses with it, or alarms fire for nothing. */
    @Test
    fun migrate2To3_leavesDosesCascadingFromTheirMedicine() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO medicines (id, name, time, lastTakenEpochDay) " +
                    "VALUES (1, 'Metformin', '08:00', NULL)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 3, true, *AmiDatabase.ALL_MIGRATIONS)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM medicines WHERE id = 1")

        db.query("SELECT COUNT(*) FROM medicine_doses").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    /**
     * 3 -> 4 only adds a table, so the thing worth proving is the opposite of the 2 -> 3
     * case: that it touches nothing. A purely additive migration that quietly rebuilt an
     * existing table would still pass schema validation.
     */
    @Test
    fun migrate3To4_addsWellbeingTableAndLeavesExistingRowsAlone() {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL("INSERT INTO medicines (id, name, dosage) VALUES (1, 'Metformin', '500mg')")
            db.execSQL(
                "INSERT INTO medicine_doses (medicineId, time, lastTakenEpochDay) " +
                    "VALUES (1, '08:00', 20000)"
            )
            db.execSQL(
                "INSERT INTO check_ins (medicineId, doseId, medicineName, scheduledTime, " +
                    "outcome, wellbeingNote, timestampEpochMilli) " +
                    "VALUES (1, 1, 'Metformin', '08:00', 'TAKEN', 'slept badly', 1700000000000)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 4, true, *AmiDatabase.ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM wellbeing_entries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // One reading per day: a second write for the same epochDay replaces the first.
        db.execSQL(
            "INSERT OR REPLACE INTO wellbeing_entries (epochDay, stress, sleep, social, connections) " +
                "VALUES (20000, 40, 60, 50, 2)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO wellbeing_entries (epochDay, stress, sleep, social, connections) " +
                "VALUES (20000, 55, 60, 50, 3)"
        )
        db.query("SELECT COUNT(*), MAX(stress) FROM wellbeing_entries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(55, cursor.getInt(1))
        }

        db.query("SELECT name, dosage FROM medicines").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Metformin", cursor.getString(0))
            assertEquals("500mg", cursor.getString(1))
        }

        db.query("SELECT medicineName, wellbeingNote FROM check_ins").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Metformin", cursor.getString(0))
            assertEquals("slept badly", cursor.getString(1))
        }

        db.query("SELECT COUNT(*) FROM medicine_doses").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun migrate4To5_addsSymptomTableAndLeavesCheckInNotesAlone() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL("INSERT INTO medicines (id, name, dosage) VALUES (1, 'Metformin', '500mg')")
            db.execSQL(
                "INSERT INTO check_ins (medicineId, doseId, medicineName, scheduledTime, " +
                    "outcome, wellbeingNote, timestampEpochMilli) " +
                    "VALUES (1, 1, 'Metformin', '08:00', 'TAKEN', 'my head hurts', 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO wellbeing_entries (epochDay, stress, sleep, social, connections) " +
                    "VALUES (20000, 40, 60, 50, 2)"
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 5, true, *AmiDatabase.ALL_MIGRATIONS)

        db.query("SELECT COUNT(*) FROM symptom_reports").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        // The old free-text note is deliberately NOT back-filled into the new table; see
        // MIGRATION_4_5. It has to still be readable where it always was.
        db.query("SELECT wellbeingNote FROM check_ins").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("my head hurts", cursor.getString(0))
        }

        db.execSQL(
            "INSERT INTO symptom_reports (checkInId, symptomKey, severity, rawText, " +
                "timestampEpochMilli) VALUES (1, 'headache', 'SEVERE', 'my head hurts', 1700000000000)"
        )
        db.query("SELECT symptomKey, severity FROM symptom_reports").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("headache", cursor.getString(0))
            assertEquals("SEVERE", cursor.getString(1))
        }

        db.query("SELECT COUNT(*) FROM wellbeing_entries").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        db.query("SELECT name FROM medicines").use { cursor ->
            assertEquals(1, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals("Metformin", cursor.getString(0))
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
