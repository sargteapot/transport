package nz.co.fordwalls.transportercam.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Folder::class, MediaAsset::class, Job::class, PendingSync::class], version = 15, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transporterDao(): TransporterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN checklistJson TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_5_6 = object : Migration(5, 6) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_6_7 = object : Migration(6, 7) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_7_8 = object : Migration(7, 8) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_8_9 = object : Migration(8, 9) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_9_10 = object : Migration(9, 10) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_10_11 = object : Migration(10, 11) { override fun migrate(db: SupportSQLiteDatabase) {} }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS jobs")
                db.execSQL("""
                    CREATE TABLE jobs (
                        id TEXT NOT NULL PRIMARY KEY,
                        fleetNumber TEXT NOT NULL,
                        rego TEXT NOT NULL,
                        loadInfo TEXT NOT NULL,
                        pickupAddress TEXT NOT NULL DEFAULT '',
                        deliveryAddress TEXT NOT NULL DEFAULT '',
                        pickupContact TEXT NOT NULL DEFAULT '',
                        deliveryContact TEXT NOT NULL DEFAULT '',
                        notes TEXT,
                        status TEXT NOT NULL,
                        driverName TEXT,
                        driverSignatureUrl TEXT,
                        customerName TEXT,
                        customerSignatureUrl TEXT,
                        pickupChecklistJson TEXT,
                        dropoffChecklistJson TEXT,
                        createdAt INTEGER NOT NULL,
                        dispatchedBy TEXT,
                        photoUrls TEXT
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN companyId TEXT NOT NULL DEFAULT 'fordwalls'")
                db.execSQL("ALTER TABLE jobs RENAME TO jobs_old")
                db.execSQL("""
                    CREATE TABLE jobs (
                        companyId TEXT NOT NULL, id TEXT NOT NULL, fleetNumber TEXT NOT NULL,
                        rego TEXT NOT NULL, loadInfo TEXT NOT NULL, pickupAddress TEXT NOT NULL,
                        deliveryAddress TEXT NOT NULL, pickupContact TEXT NOT NULL,
                        deliveryContact TEXT NOT NULL, notes TEXT, status TEXT NOT NULL,
                        driverName TEXT, driverSignatureUrl TEXT, customerName TEXT,
                        customerSignatureUrl TEXT, pickupChecklistJson TEXT,
                        dropoffChecklistJson TEXT, createdAt INTEGER NOT NULL,
                        dispatchedBy TEXT, photoUrls TEXT, PRIMARY KEY(companyId, id)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO jobs SELECT 'fordwalls', id, fleetNumber, rego, loadInfo,
                        pickupAddress, deliveryAddress, pickupContact, deliveryContact, notes,
                        status, driverName, driverSignatureUrl, customerName, customerSignatureUrl,
                        pickupChecklistJson, dropoffChecklistJson, createdAt, dispatchedBy, photoUrls
                    FROM jobs_old
                """.trimIndent())
                db.execSQL("DROP TABLE jobs_old")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_assets ADD COLUMN jobId TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN evidenceId TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN evidencePhase TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN cloudState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN storagePath TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN downloadUrl TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN cloudError TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS pending_sync (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, companyId TEXT NOT NULL, type TEXT NOT NULL, targetId TEXT NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError TEXT)""")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transporter_database"
                )
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, 
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, 
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
