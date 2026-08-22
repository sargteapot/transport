package nz.co.fordwalls.transportercam

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.co.fordwalls.transportercam.database.AppDatabase
import nz.co.fordwalls.transportercam.database.Folder
import nz.co.fordwalls.transportercam.database.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomIsolationTest {
    private lateinit var db: AppDatabase

    @Before fun createDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After fun closeDatabase() = db.close()

    @Test fun companiesCanReuseRemoteJobIdsWithoutLeakingFolders() = runBlocking {
        val dao = db.transporterDao()
        dao.insertJob(Job(companyId = "fordwalls", id = "ABC123", rego = "FORD1"))
        dao.insertJob(Job(companyId = "acme", id = "ABC123", rego = "ACME1"))
        dao.insertFolder(Folder(companyId = "fordwalls", name = "FORD1"))
        dao.insertFolder(Folder(companyId = "acme", name = "ACME1"))

        assertEquals("FORD1", dao.getJobById("fordwalls", "ABC123")?.rego)
        assertEquals("ACME1", dao.getJobById("acme", "ABC123")?.rego)
        assertEquals(listOf("FORD1"), dao.getAllFolders("fordwalls").first().map { it.name })
        assertEquals(listOf("ACME1"), dao.getAllFolders("acme").first().map { it.name })
    }

    @Test fun migration12To13AssignsExistingRowsToFordWalls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-12-13.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, checklistJson TEXT, jobId TEXT)")
                        db.execSQL("CREATE TABLE media_assets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, folderId INTEGER NOT NULL, filePath TEXT NOT NULL, isVideo INTEGER NOT NULL, timestamp INTEGER NOT NULL, notes TEXT, FOREIGN KEY(folderId) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_media_assets_folderId ON media_assets(folderId)")
                        db.execSQL("CREATE TABLE jobs (id TEXT NOT NULL PRIMARY KEY, fleetNumber TEXT NOT NULL, rego TEXT NOT NULL, loadInfo TEXT NOT NULL, pickupAddress TEXT NOT NULL, deliveryAddress TEXT NOT NULL, pickupContact TEXT NOT NULL, deliveryContact TEXT NOT NULL, notes TEXT, status TEXT NOT NULL, driverName TEXT, driverSignatureUrl TEXT, customerName TEXT, customerSignatureUrl TEXT, pickupChecklistJson TEXT, dropoffChecklistJson TEXT, createdAt INTEGER NOT NULL, dispatchedBy TEXT, photoUrls TEXT)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO folders (name,createdAt) VALUES ('LEGACY',1)")
            execSQL("INSERT INTO jobs (id,fleetNumber,rego,loadInfo,pickupAddress,deliveryAddress,pickupContact,deliveryContact,status,createdAt) VALUES ('JOB1','241','ABC','','','','','','NEW',1)")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_12_13).build()
        assertEquals(listOf("LEGACY"), migrated.transporterDao().getAllFolders("fordwalls").first().map { it.name })
        assertEquals("ABC", migrated.transporterDao().getJobById("fordwalls", "JOB1")?.rego)
        migrated.close()
        context.deleteDatabase(name)
        Unit
    }
}
