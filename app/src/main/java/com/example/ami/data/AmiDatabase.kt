package com.example.ami.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Medicine::class,
        MedicineDose::class,
        CheckInRecord::class,
        ActionLogRecord::class,
        WellbeingEntry::class,
        SymptomReport::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AmiDatabase : RoomDatabase() {

    abstract fun medicineDao(): MedicineDao

    abstract fun medicineDoseDao(): MedicineDoseDao

    abstract fun checkInDao(): CheckInDao

    abstract fun actionLogDao(): ActionLogDao

    abstract fun wellbeingDao(): WellbeingDao

    abstract fun symptomReportDao(): SymptomReportDao

    companion object {
        @Volatile
        private var INSTANCE: AmiDatabase? = null

        /**
         * Adds the check-in history table.
         *
         * Deliberately a real migration rather than a destructive fallback: a user's
         * medicine schedule is the one piece of state in this app that would be
         * genuinely harmful to silently lose on upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `check_ins` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `medicineId` INTEGER NOT NULL,
                        `medicineName` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `wellbeingNote` TEXT,
                        `timestampEpochMilli` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Splits each medicine's single time into a `medicine_doses` row, so one
         * medicine can be due more than once a day, and adds the dosage note, the
         * per-dose columns on the check-in history, and the guided-navigation log.
         *
         * Every existing medicine keeps its schedule: its old time and "taken today"
         * flag become its first dose. Tables are recreated rather than ALTERed because
         * the new columns are NOT NULL without an entity-declared default, and a
         * migration that leaves a stray `DEFAULT` in the DDL fails Room's schema
         * validation on the next open.
         *
         * `medicines` is rebuilt before `medicine_doses` is created so the foreign key
         * is written against the final table and never points at one that is about to
         * be dropped.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Park the old schedule; `medicines` is about to lose those columns.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dose_seed` (" +
                        "`medicineId` INTEGER NOT NULL, `time` TEXT NOT NULL, `lastTakenEpochDay` INTEGER)"
                )
                db.execSQL(
                    "INSERT INTO `dose_seed` (`medicineId`, `time`, `lastTakenEpochDay`) " +
                        "SELECT `id`, `time`, `lastTakenEpochDay` FROM `medicines`"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `medicines_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`dosage` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `medicines_new` (`id`, `name`, `dosage`) " +
                        "SELECT `id`, `name`, NULL FROM `medicines`"
                )
                db.execSQL("DROP TABLE `medicines`")
                db.execSQL("ALTER TABLE `medicines_new` RENAME TO `medicines`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `medicine_doses` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`medicineId` INTEGER NOT NULL, " +
                        "`time` TEXT NOT NULL, " +
                        "`lastTakenEpochDay` INTEGER, " +
                        "FOREIGN KEY(`medicineId`) REFERENCES `medicines`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_medicine_doses_medicineId` " +
                        "ON `medicine_doses` (`medicineId`)"
                )
                db.execSQL(
                    "INSERT INTO `medicine_doses` (`medicineId`, `time`, `lastTakenEpochDay`) " +
                        "SELECT `medicineId`, `time`, `lastTakenEpochDay` FROM `dose_seed`"
                )
                db.execSQL("DROP TABLE `dose_seed`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `check_ins_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`medicineId` INTEGER NOT NULL, " +
                        "`doseId` INTEGER NOT NULL, " +
                        "`medicineName` TEXT NOT NULL, " +
                        "`scheduledTime` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`wellbeingNote` TEXT, " +
                        "`timestampEpochMilli` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `check_ins_new` (`id`, `medicineId`, `doseId`, `medicineName`, " +
                        "`scheduledTime`, `outcome`, `wellbeingNote`, `timestampEpochMilli`) " +
                        "SELECT `id`, `medicineId`, 0, `medicineName`, '', `outcome`, " +
                        "`wellbeingNote`, `timestampEpochMilli` FROM `check_ins`"
                )
                db.execSQL("DROP TABLE `check_ins`")
                db.execSQL("ALTER TABLE `check_ins_new` RENAME TO `check_ins`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `action_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`goal` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`spokenText` TEXT, " +
                        "`packageName` TEXT, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`timestampEpochMilli` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Adds the daily wellbeing readings behind the wellbeing screen.
         *
         * Purely additive - no existing table is touched, so there is nothing to copy
         * and nothing to lose. `epochDay` is the primary key rather than an
         * autoincrementing id because there is one reading per calendar day; see
         * [WellbeingEntry].
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wellbeing_entries` (" +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`stress` INTEGER NOT NULL, " +
                        "`sleep` INTEGER NOT NULL, " +
                        "`social` INTEGER NOT NULL, " +
                        "`connections` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`epochDay`))"
                )
            }
        }

        /**
         * Adds the symptom rows the caretaker agent reads.
         *
         * Additive, like [MIGRATION_3_4] - nothing existing is touched. The check-in's
         * free-text `wellbeingNote` is left exactly where it is rather than being parsed
         * into this table on upgrade: back-filling would mean running the extractor over
         * old notes with no way for anyone to check the result, and a history of guesses
         * is worse than a history that starts today.
         *
         * `checkInId` has no foreign key to `check_ins` on purpose. A symptom someone
         * reported is a fact about them, not about the call, and should survive the
         * check-in history being trimmed.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `symptom_reports` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`checkInId` INTEGER NOT NULL, " +
                        "`symptomKey` TEXT NOT NULL, " +
                        "`severity` TEXT NOT NULL, " +
                        "`rawText` TEXT NOT NULL, " +
                        "`timestampEpochMilli` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Every migration, in one place.
         *
         * The builder below and `AmiDatabaseMigrationTest` both read this list rather than
         * each naming migrations of their own. A test that is handed its own hand-written
         * list is a test that keeps passing while the real database is missing a step -
         * or, as happened here, one that silently exercises no migration at all.
         *
         * Declared after the migrations it collects; companion properties initialise in
         * source order.
         */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        fun getInstance(context: Context): AmiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AmiDatabase::class.java,
                    "ami.db"
                ).addMigrations(*ALL_MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
