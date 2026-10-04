package nz.co.fordwalls.transportercam

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.firstOrNull
import nz.co.fordwalls.transportercam.database.AppDatabase
import org.json.JSONObject
import java.io.File

/** Drains durable local changes only while Android has a network connection. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (FirebaseAuth.getInstance().currentUser == null) return Result.retry()
        val dao = AppDatabase.getDatabase(applicationContext).transporterDao()
        val db = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "transport")
        val paths = TenantFirestorePaths(db)

        for (item in dao.getPendingSync()) {
            try {
                val value = JSONObject(item.payload)
                when (item.type) {
                    "JOB_STATUS" -> paths.jobs(item.companyId).document(item.targetId)
                        .update("status", value.getString("status")).await()
                    "CHECKLIST" -> paths.jobs(item.companyId).document(item.targetId).update(
                        mapOf(value.getString("field") to value.getJSONArray("items").toMaps(),
                            value.getString("field") + "Json" to value.getString("json"))
                    ).await()
                    "PRESTART" -> paths.prestarts(item.companyId, value.getString("fleet"))
                        .document(item.targetId).set(value.getJSONObject("data").toMap()).await()
                    "SIGNATURE" -> uploadSignature(paths, item.companyId, item.targetId, value)
                    "CLEAR_JOB" -> paths.jobs(item.companyId).document(item.targetId)
                        .update("clearedFromFleets", FieldValue.arrayUnion(value.getString("fleet"))).await()
                }
                dao.deletePendingSync(item.id)
            } catch (error: Exception) {
                dao.markPendingSyncFailed(item.id, (error.localizedMessage ?: "Sync failed").take(300))
                return Result.retry()
            }
        }

        // Media rows are themselves a durable upload queue.
        for (asset in dao.getMediaAwaitingUpload()) {
            val jobId = asset.jobId ?: continue
            val folder = dao.getFolderByIdOnceForWorker(asset.folderId) ?: continue
            val job = dao.getJobById(folder.companyId, jobId) ?: continue
            try {
                val user = FirebaseAuth.getInstance().currentUser ?: return Result.retry()
                val file = File(asset.filePath)
                if (!file.exists()) continue
                val evidenceId = asset.evidenceId ?: "media-${asset.id}"
                val phase = asset.evidencePhase ?: if (job.status.name in listOf("NEW", "ACCEPTED", "ONSCAN")) "pickup" else "delivery"
                val ext = file.extension.ifBlank { if (asset.isVideo) "mp4" else "jpg" }
                val storagePath = asset.storagePath ?: "companies/${folder.companyId}/drivers/${user.uid}/jobs/$jobId/evidence/$phase/$evidenceId.$ext"
                val evidence = paths.evidence(folder.companyId, jobId).document(evidenceId)
                val settings = SettingsDataStore(applicationContext)
                val driverId = settings.driverDocumentId.firstOrNull().orEmpty()
                val fleet = settings.fleetNumber.firstOrNull().orEmpty()
                evidence.set(mapOf("type" to if (asset.isVideo) "video" else "photo", "phase" to phase,
                    "companyId" to folder.companyId, "jobId" to jobId, "driverId" to driverId,
                    "driverUid" to user.uid, "fleetNumber" to fleet,
                    "status" to "uploading", "storagePath" to storagePath,
                    "capturedAt" to asset.timestamp, "notes" to (asset.notes ?: "")), SetOptions.merge()).await()
                val metadata = StorageMetadata.Builder()
                    .setContentType(if (asset.isVideo) "video/mp4" else "image/jpeg")
                    .setCustomMetadata("companyId", folder.companyId).setCustomMetadata("jobId", jobId)
                    .setCustomMetadata("evidenceId", evidenceId).setCustomMetadata("phase", phase)
                    .setCustomMetadata("driverId", driverId).setCustomMetadata("driverUid", user.uid).build()
                FirebaseStorage.getInstance().reference.child(storagePath).putFile(Uri.fromFile(file), metadata).await()
                evidence.set(mapOf("status" to "ready", "uploadedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
                dao.updateMediaCloudState(folder.companyId, asset.id, evidenceId, phase, "READY", storagePath, null, null)
            } catch (error: Exception) {
                dao.updateMediaCloudState(folder.companyId, asset.id, asset.evidenceId, asset.evidencePhase,
                    "FAILED", asset.storagePath, null, (error.localizedMessage ?: "Upload failed").take(300))
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun uploadSignature(paths: TenantFirestorePaths, companyId: String, jobId: String, value: JSONObject) {
        val file = File(value.getString("file"))
        val storagePath = value.getString("storagePath")
        val evidenceId = value.getString("evidenceId")
        require(file.isFile) { "The saved signature file is missing" }
        val user = FirebaseAuth.getInstance().currentUser ?: error("The driver session has expired")
        val settings = SettingsDataStore(applicationContext)
        val driverId = settings.driverDocumentId.firstOrNull().orEmpty()
        val fleet = settings.fleetNumber.firstOrNull().orEmpty()
        val evidence = paths.evidence(companyId, jobId).document(evidenceId)
        evidence.set(mapOf(
            "type" to "signature", "phase" to if (evidenceId.startsWith("driver")) "pickup" else "delivery",
            "signerRole" to if (evidenceId.startsWith("driver")) "driver" else "customer",
            "signerName" to value.getString("name"), "companyId" to companyId, "jobId" to jobId,
            "driverId" to driverId, "driverUid" to user.uid, "fleetNumber" to fleet,
            "status" to "uploading", "storagePath" to storagePath, "contentType" to "image/png",
            "updatedAt" to FieldValue.serverTimestamp()
        ), SetOptions.merge()).await()
        val metadata = StorageMetadata.Builder().setContentType("image/png")
            .setCustomMetadata("companyId", companyId).setCustomMetadata("jobId", jobId)
            .setCustomMetadata("evidenceId", evidenceId).setCustomMetadata("driverId", driverId)
            .setCustomMetadata("driverUid", user.uid).build()
        FirebaseStorage.getInstance().reference.child(storagePath).putFile(Uri.fromFile(file), metadata).await()
        val update = mutableMapOf<String, Any>("status" to value.getString("status"), "evidenceUpdatedAt" to FieldValue.serverTimestamp())
        update[value.getString("nameField")] = value.getString("name")
        update[value.getString("pathField")] = storagePath
        paths.jobs(companyId).document(jobId).update(update).await()
        evidence.set(mapOf("type" to "signature", "status" to "ready",
            "storagePath" to storagePath, "uploadedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
    }
}

private fun org.json.JSONArray.toMaps(): List<Map<String, Any>> = (0 until length()).map { getJSONObject(it).toMap() }
private fun JSONObject.toMap(): Map<String, Any> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        is JSONObject -> value.toMap()
        is org.json.JSONArray -> value.toMaps()
        else -> value
    }
}
