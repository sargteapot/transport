package nz.co.fordwalls.transportercam.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransporterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingSync(item: PendingSync): Long

    @Query("SELECT * FROM pending_sync ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingSync(limit: Int = 50): List<PendingSync>

    @Query("SELECT COUNT(*) FROM pending_sync")
    fun observePendingSyncCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_assets WHERE cloudState IN ('PENDING','FAILED')")
    fun observePendingMediaCount(): Flow<Int>

    @Query("DELETE FROM pending_sync WHERE id = :id")
    suspend fun deletePendingSync(id: Long)

    @Query("UPDATE pending_sync SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markPendingSyncFailed(id: Long, error: String)

    @Query("SELECT DISTINCT targetId FROM pending_sync WHERE companyId = :companyId AND type IN ('JOB_STATUS','CHECKLIST','SIGNATURE')")
    suspend fun getPendingJobIds(companyId: String): List<String>

    @Query("SELECT * FROM media_assets WHERE cloudState IN ('PENDING','FAILED') ORDER BY timestamp ASC")
    suspend fun getMediaAwaitingUpload(): List<MediaAsset>

    @Query("SELECT * FROM folders WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun getAllFolders(companyId: String): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE companyId = :companyId ORDER BY createdAt DESC")
    suspend fun getAllFoldersOnce(companyId: String): List<Folder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: Folder): Long

    @Query("SELECT * FROM folders WHERE id = :folderId AND companyId = :companyId")
    fun getFolderById(companyId: String, folderId: Long): Flow<Folder?>

    @Query("SELECT * FROM folders WHERE id = :folderId AND companyId = :companyId")
    suspend fun getFolderByIdOnce(companyId: String, folderId: Long): Folder?

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderByIdOnceForWorker(folderId: Long): Folder?

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE media_assets.folderId = :folderId AND folders.companyId = :companyId ORDER BY timestamp ASC")
    fun getMediaForFolder(companyId: String, folderId: Long): Flow<List<MediaAsset>>

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE media_assets.folderId = :folderId AND folders.companyId = :companyId ORDER BY timestamp ASC")
    suspend fun getMediaForFolderOnce(companyId: String, folderId: Long): List<MediaAsset>

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE media_assets.folderId = :folderId AND folders.companyId = :companyId ORDER BY media_assets.id DESC LIMIT 1")
    suspend fun getLatestMediaForFolderOnce(companyId: String, folderId: Long): MediaAsset?

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE media_assets.id = :mediaId AND folders.companyId = :companyId")
    fun getMediaById(companyId: String, mediaId: Long): Flow<MediaAsset?>

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE media_assets.id = :mediaId AND folders.companyId = :companyId")
    suspend fun getMediaByIdOnce(companyId: String, mediaId: Long): MediaAsset?

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE folders.companyId = :companyId")
    fun getAllMediaAssets(companyId: String): Flow<List<MediaAsset>>

    @Query("SELECT media_assets.* FROM media_assets INNER JOIN folders ON folders.id = media_assets.folderId WHERE folders.companyId = :companyId")
    suspend fun getAllMediaAssetsOnce(companyId: String): List<MediaAsset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaAsset(mediaAsset: MediaAsset): Long

    @Query("DELETE FROM media_assets WHERE id = :mediaId AND folderId IN (SELECT id FROM folders WHERE companyId = :companyId)")
    suspend fun deleteMediaAsset(companyId: String, mediaId: Long)

    @Query("DELETE FROM folders WHERE id = :folderId AND companyId = :companyId")
    suspend fun deleteFolder(companyId: String, folderId: Long)

    @Query("SELECT MIN(timestamp) FROM media_assets WHERE folderId = :folderId")
    suspend fun getEarliestTimestampForFolder(folderId: Long): Long?

    @Query("SELECT MAX(timestamp) FROM media_assets WHERE folderId = :folderId")
    suspend fun getLatestTimestampForFolder(folderId: Long): Long?

    @Query("UPDATE media_assets SET notes = :notes WHERE id = :mediaId AND folderId IN (SELECT id FROM folders WHERE companyId = :companyId)")
    suspend fun updateMediaNotes(companyId: String, mediaId: Long, notes: String?)

    @Query("UPDATE media_assets SET notes = :notes WHERE folderId = :folderId AND folderId IN (SELECT id FROM folders WHERE companyId = :companyId) AND id = (SELECT MAX(id) FROM media_assets WHERE folderId = :folderId)")
    suspend fun updateLastMediaNoteForFolder(companyId: String, folderId: Long, notes: String?)

    @Query("""
        UPDATE media_assets
        SET evidenceId = :evidenceId,
            evidencePhase = :phase,
            cloudState = :state,
            storagePath = :storagePath,
            downloadUrl = :downloadUrl,
            cloudError = :error
        WHERE id = :mediaId
          AND folderId IN (SELECT id FROM folders WHERE companyId = :companyId)
    """)
    suspend fun updateMediaCloudState(
        companyId: String,
        mediaId: Long,
        evidenceId: String?,
        phase: String?,
        state: String,
        storagePath: String?,
        downloadUrl: String?,
        error: String?
    )

    @Query("UPDATE folders SET checklistJson = :checklistJson WHERE id = :folderId AND companyId = :companyId")
    suspend fun updateFolderChecklist(companyId: String, folderId: Long, checklistJson: String?)

    @Query("SELECT MIN(timestamp) FROM media_assets")
    suspend fun getEarliestTimestamp(): Long?

    @Query("SELECT MAX(timestamp) FROM media_assets")
    suspend fun getLatestTimestamp(): Long?

    @Query("SELECT * FROM folders WHERE companyId = :companyId AND name LIKE '%' || :search || '%'")
    fun searchFoldersByName(companyId: String, search: String): Flow<List<Folder>>

    @Query("SELECT * FROM folders WHERE companyId = :companyId AND name = :name")
    suspend fun getFoldersByName(companyId: String, name: String): List<Folder>

    @Query("SELECT * FROM folders WHERE companyId = :companyId AND jobId = :jobId")
    suspend fun getFoldersByJobId(companyId: String, jobId: String): List<Folder>

    // Job Methods
    @Query("SELECT * FROM jobs WHERE companyId = :companyId ORDER BY createdAt DESC")
    fun getAllJobs(companyId: String): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE companyId = :companyId AND fleetNumber = :fleetNumber AND status IN (:statuses) ORDER BY createdAt DESC")
    fun getJobsByStatus(companyId: String, fleetNumber: String, statuses: List<JobStatus>): Flow<List<Job>>

    @Query("SELECT * FROM jobs WHERE companyId = :companyId AND id = :jobId")
    suspend fun getJobById(companyId: String, jobId: String): Job?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: Job)

    @Query("UPDATE jobs SET status = :status WHERE companyId = :companyId AND id = :jobId")
    suspend fun updateJobStatus(companyId: String, jobId: String, status: JobStatus)

    @Query("UPDATE jobs SET pickupChecklistJson = :checklistJson WHERE companyId = :companyId AND id = :jobId")
    suspend fun updateJobPickupChecklist(companyId: String, jobId: String, checklistJson: String)

    @Query("UPDATE jobs SET dropoffChecklistJson = :checklistJson WHERE companyId = :companyId AND id = :jobId")
    suspend fun updateJobDeliveryChecklist(companyId: String, jobId: String, checklistJson: String)

    @Query("UPDATE jobs SET driverName = :driverName, driverSignatureUrl = :driverSignatureUrl, status = 'PICKED_UP' WHERE companyId = :companyId AND id = :jobId")
    suspend fun markJobAsPickedUp(companyId: String, jobId: String, driverName: String, driverSignatureUrl: String)

    @Query("UPDATE jobs SET customerName = :customerName, customerSignatureUrl = :customerSignatureUrl, status = 'DONE' WHERE companyId = :companyId AND id = :jobId")
    suspend fun markJobAsDone(companyId: String, jobId: String, customerName: String, customerSignatureUrl: String)
    @Query("UPDATE folders SET jobId = :jobId WHERE id = :folderId AND companyId = :companyId")
    suspend fun linkFolderToJob(companyId: String, folderId: Long, jobId: String)
}
