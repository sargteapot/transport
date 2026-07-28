package nz.co.fordwalls.transportercam.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransporterDao {
    @Query("SELECT * FROM folders ORDER BY createdAt DESC")
    fun getAllFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders ORDER BY createdAt DESC")
    suspend fun getAllFoldersOnce(): List<Folder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Query("SELECT * FROM folders WHERE id = :folderId")
    fun getFolderById(folderId: Long): Flow<Folder?>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderByIdOnce(folderId: Long): Folder?

    @Query("SELECT * FROM media_assets WHERE folderId = :folderId ORDER BY timestamp ASC")
    fun getMediaForFolder(folderId: Long): Flow<List<MediaAsset>>

    @Query("SELECT * FROM media_assets WHERE folderId = :folderId ORDER BY timestamp ASC")
    suspend fun getMediaForFolderOnce(folderId: Long): List<MediaAsset>

    @Query("SELECT * FROM media_assets WHERE id = :mediaId")
    fun getMediaById(mediaId: Long): Flow<MediaAsset?>

    @Query("SELECT * FROM media_assets")
    fun getAllMediaAssets(): Flow<List<MediaAsset>>

    @Query("SELECT * FROM media_assets")
    suspend fun getAllMediaAssetsOnce(): List<MediaAsset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaAsset(mediaAsset: MediaAsset): Long

    @Query("DELETE FROM media_assets WHERE id = :mediaId")
    suspend fun deleteMediaAsset(mediaId: Long)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Long)

    @Query("SELECT MIN(timestamp) FROM media_assets WHERE folderId = :folderId")
    suspend fun getEarliestTimestampForFolder(folderId: Long): Long?

    @Query("SELECT MAX(timestamp) FROM media_assets WHERE folderId = :folderId")
    suspend fun getLatestTimestampForFolder(folderId: Long): Long?

    @Query("UPDATE media_assets SET notes = :notes WHERE id = :mediaId")
    suspend fun updateMediaNotes(mediaId: Long, notes: String?)

    @Query("UPDATE media_assets SET notes = :notes WHERE folderId = :folderId AND id = (SELECT MAX(id) FROM media_assets WHERE folderId = :folderId)")
    suspend fun updateLastMediaNoteForFolder(folderId: Long, notes: String?)

    @Query("UPDATE folders SET checklistJson = :checklistJson WHERE id = :folderId")
    suspend fun updateFolderChecklist(folderId: Long, checklistJson: String?)

    @Query("SELECT MIN(timestamp) FROM media_assets")
    suspend fun getEarliestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM media_assets")
    suspend fun getLatestTimestamp(): Long?

    @Query("SELECT * FROM folders WHERE name LIKE '%' || :search || '%'")
    fun searchFoldersByName(search: String): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE name = :name")
    suspend fun getFoldersByName(name: String): List<Folder>

    @Query("SELECT * FROM folders WHERE jobId = :jobId")
    suspend fun getFoldersByJobId(jobId: String): List<Folder>

    // Job Methods
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
    fun getAllJobs(): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE fleetNumber = :fleetNumber AND status IN (:statuses) ORDER BY createdAt DESC")
    fun getJobsByStatus(fleetNumber: String, statuses: List<JobStatus>): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE id = :jobId")
    suspend fun getJobById(jobId: String): Job?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: Job)

    @Query("UPDATE jobs SET status = :status WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: JobStatus)

    @Query("UPDATE jobs SET pickupChecklistJson = :checklistJson WHERE id = :jobId")
    suspend fun updateJobPickupChecklist(jobId: String, checklistJson: String)

    @Query("UPDATE jobs SET dropoffChecklistJson = :checklistJson WHERE id = :jobId")
    suspend fun updateJobDeliveryChecklist(jobId: String, checklistJson: String)

    @Query("UPDATE jobs SET driverName = :driverName, driverSignatureUrl = :driverSignatureUrl, status = 'PICKED_UP' WHERE id = :jobId")
    suspend fun markJobAsPickedUp(jobId: String, driverName: String, driverSignatureUrl: String)

    @Query("UPDATE jobs SET customerName = :customerName, customerSignatureUrl = :customerSignatureUrl, status = 'DONE' WHERE id = :jobId")
    suspend fun markJobAsDone(jobId: String, customerName: String, customerSignatureUrl: String)
    @Query("UPDATE folders SET jobId = :jobId WHERE id = :folderId")
    suspend fun linkFolderToJob(folderId: Long, jobId: String)
}
