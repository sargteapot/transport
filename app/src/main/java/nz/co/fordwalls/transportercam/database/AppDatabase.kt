package nz.co.fordwalls.transportercam.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Folder::class, MediaAsset::class, Job::class], version = 11, exportSchema = false)
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

        val MIGRATION_10_11 = object : Migration(10, 11) {
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
                        contactInfo TEXT NOT NULL,
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
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
