package nz.co.fordwalls.transportercam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nz.co.fordwalls.transportercam.database.*
import nz.co.fordwalls.transportercam.ui.ChecklistItem
import nz.co.fordwalls.transportercam.ui.PrestartItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao: TransporterDao = AppDatabase.getDatabase(application).transporterDao()
    private val settingsDataStore = SettingsDataStore(application)
    
    private val firestore: FirebaseFirestore by lazy {
        try {
            FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "transport")
        } catch (e: Exception) {
            Log.e("FleetDebug", "Failed to get 'transport' database, falling back to default", e)
            FirebaseFirestore.getInstance()
        }
    }
    
    val fleetNumber: Flow<String?> = settingsDataStore.fleetNumber
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private var jobsListener: ListenerRegistration? = null
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    val allFolders: Flow<List<Folder>> = dao.getAllFolders()
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val foldersWithTimestamps: Flow<List<FolderWithTimestamps>> = dao.getAllFolders().flatMapLatest { folders ->
        if (folders.isEmpty()) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
        
        val flows = folders.map { folder ->
            dao.getMediaForFolder(folder.id).map { media ->
                FolderWithTimestamps(
                    folder = folder,
                    earliestMedia = media.minByOrNull { it.timestamp }?.timestamp,
                    latestMedia = media.maxByOrNull { it.timestamp }?.timestamp
                )
            }
        }
        combine(flows) { it.toList() }
    }

    val allMediaAssets: Flow<List<MediaAsset>> = dao.getAllMediaAssets()

    val notificationsEnabled: Flow<Boolean> = settingsDataStore.notificationsEnabled
    val timestampEnabled: Flow<Boolean> = settingsDataStore.timestampEnabled
    val dateFormat: Flow<String> = settingsDataStore.dateFormat
    val gpsEnabled: Flow<Boolean> = settingsDataStore.gpsEnabled
    val darkMode: Flow<String> = settingsDataStore.darkMode
    val imageQuality: Flow<Int> = settingsDataStore.imageQuality
    val shareSummaryEnabled: Flow<Boolean> = settingsDataStore.shareSummaryEnabled
    val captureFeedbackEnabled: Flow<Boolean> = settingsDataStore.captureFeedbackEnabled

    private var isFirstSync = true
    private val NOTIFICATION_CHANNEL_ID = "new_jobs_channel"
    
    // Batching logic
    private var pendingNotificationCount = 0
    private var notificationJob: CoroutineJob? = null

    init {
        createNotificationChannel()
        
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
        firestore.firestoreSettings = settings

        viewModelScope.launch {
            fleetNumber.collect { number ->
                if (number != null) {
                    _isLoggedIn.value = true
                    startJobsListener(number)
                } else {
                    _isLoggedIn.value = false
                    stopJobsListener()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Job Notifications"
            val descriptionText = "Alerts for new assigned jobs"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager: NotificationManager =
                getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startJobsListener(fleetNumber: String) {
        stopJobsListener()
        Log.d("FleetDebug", "Starting jobs listener for fleet: $fleetNumber")
        
        isFirstSync = true
        jobsListener = firestore.collection("jobs")
            .whereEqualTo("fleetNumber", fleetNumber)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FleetDebug", "Firestore Error: ${error.message}", error)
                    return@addSnapshotListener
                }
                
                if (!isFirstSync) {
                    val newJobsInBatch = snapshot?.documentChanges?.count { it.type == DocumentChange.Type.ADDED } ?: 0
                    if (newJobsInBatch > 0) {
                        Log.d("FleetDebug", "$newJobsInBatch new jobs detected in snapshot. Batching...")
                        triggerBatchedNotification(newJobsInBatch)
                    }
                }
                isFirstSync = false
                
                val jobList = mutableListOf<Job>()
                val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                snapshot?.documents?.forEach { doc ->
                    try {
                        val data = doc.data ?: return@forEach
                        
                        val createdAtRaw = data["createdAt"]
                        val createdAtMs = when (createdAtRaw) {
                            is Number -> createdAtRaw.toLong()
                            is String -> try { isoFormat.parse(createdAtRaw)?.time ?: System.currentTimeMillis() } catch(e: Exception) { System.currentTimeMillis() }
                            else -> System.currentTimeMillis()
                        }

                        val job = Job(
                            id = doc.id,
                            fleetNumber = data["fleetNumber"]?.toString() ?: "",
                            rego = data["rego"]?.toString() ?: "UNKNOWN",
                            loadInfo = data["loadInfo"]?.toString() ?: "",
                            pickupAddress = data["pickupAddress"]?.toString() ?: "",
                            deliveryAddress = data["deliveryAddress"]?.toString() ?: "",
                            pickupContact = data["pickupContact"]?.toString() ?: data["contactInfo"]?.toString() ?: "",
                            deliveryContact = data["deliveryContact"]?.toString() ?: "",
                            notes = data["notes"]?.toString(),
                            status = try { 
                                JobStatus.valueOf(data["status"]?.toString() ?: "NEW") 
                            } catch (e: Exception) { 
                                JobStatus.NEW 
                            },
                            driverName = data["driverName"]?.toString(),
                            driverSignatureUrl = data["driverSignatureUrl"]?.toString(),
                            customerName = data["customerName"]?.toString(),
                            customerSignatureUrl = data["customerSignatureUrl"]?.toString(),
                            pickupChecklistJson = data["pickupChecklistJson"]?.toString(),
                            dropoffChecklistJson = data["dropoffChecklistJson"]?.toString(),
                            createdAt = createdAtMs,
                            dispatchedBy = data["dispatchedBy"]?.toString(),
                            photoUrls = data["photoUrls"]?.toString()
                        )
                        jobList.add(job)
                    } catch (e: Exception) {
                        Log.e("FleetDebug", "Failed to parse job document ${doc.id}: ${e.message}")
                    }
                }
                
                _jobs.value = jobList
                
                viewModelScope.launch {
                    try {
                        jobList.forEach { dao.insertJob(it) }
                    } catch (e: Exception) {
                        Log.e("FleetDebug", "Failed to sync jobs to local DB: ${e.message}")
                    }
                }
            }
    }

    private fun triggerBatchedNotification(count: Int) {
        pendingNotificationCount += count
        
        // Cancel previous timer if it's still waiting
        notificationJob?.cancel()
        
        // Start a new timer
        notificationJob = viewModelScope.launch {
            delay(3000) // Wait 3 seconds for more jobs to arrive
            
            val enabled = settingsDataStore.notificationsEnabled.map { it }.firstOrNull() ?: true
            if (enabled) {
                showJobNotification(pendingNotificationCount)
            }
            
            // Reset for next batch
            pendingNotificationCount = 0
        }
    }

    private fun showJobNotification(count: Int) {
        val context = getApplication<Application>()
        Log.d("FleetDebug", "Posting batched notification for $count jobs")
        
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val title = if (count == 1) "New Job Received" else "New Jobs Received"
        val message = if (count == 1) "A new job has been dispatched to your truck." else "$count new jobs have been dispatched to your truck."

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(500, 500, 500))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        try {
            // Use fixed ID to replace previous notification in this batch
            notificationManager.notify(1001, builder.build())
        } catch (e: Exception) {
            Log.e("FleetDebug", "Failed to post notification", e)
        }
    }

    private fun stopJobsListener() {
        jobsListener?.remove()
        jobsListener = null
    }

    fun unifiedLogin(username: String, pass: String, fleet: String, onResult: (Boolean, String?) -> Unit) {
        val upperFleet = fleet.trim().uppercase()
        val user = username.trim()
        
        Log.d("FleetDebug", "Attempting login for: $user, Fleet: $upperFleet")
        
        firestore.collection("fleets").document(upperFleet).get()
            .addOnSuccessListener { fleetDoc ->
                if (fleetDoc.exists()) {
                    firestore.collection("drivers")
                        .whereEqualTo("username", user)
                        .whereEqualTo("password", pass)
                        .get()
                        .addOnSuccessListener { driverSnapshot ->
                            if (!driverSnapshot.isEmpty) {
                                viewModelScope.launch {
                                    settingsDataStore.setFleetNumber(upperFleet)
                                    val driverDocId = driverSnapshot.documents[0].id
                                    firestore.collection("drivers").document(driverDocId)
                                        .update("currentFleetNumber", upperFleet)
                                    withContext(Dispatchers.Main) { onResult(true, null) }
                                }
                            } else {
                                onResult(false, "Invalid username or password.")
                            }
                        }
                        .addOnFailureListener { e ->
                            onResult(false, "Auth check failed: ${e.localizedMessage}")
                        }
                } else {
                    onResult(false, "Truck '$upperFleet' is not registered.")
                }
            }
            .addOnFailureListener { e ->
                onResult(false, "Connection error: ${e.localizedMessage}")
            }
    }

    fun logout() {
        viewModelScope.launch {
            settingsDataStore.setFleetNumber(null)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotificationsEnabled(enabled)
        }
    }

    fun savePrestart(fleet: String, items: List<PrestartItem>, notes: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val now = Date()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
            val timeStr = SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(now)
            val docId = "${dateStr}_$timeStr"
            
            val checklistMap = items.associate { 
                it.part to if (it.isNumberInput) it.value else it.status 
            }
            
            val payload = mapOf(
                "items" to checklistMap,
                "notes" to notes,
                "timestamp" to System.currentTimeMillis(),
                "date" to dateStr,
                "time" to timeStr
            )

            firestore.collection("fleets").document(fleet)
                .collection("prestarts").document(docId)
                .set(payload)
                .addOnSuccessListener {
                    Log.d("FleetDebug", "Prestart saved successfully: $docId")
                    viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(true) } }
                }
                .addOnFailureListener { e ->
                    Log.e("FleetDebug", "Failed to save prestart: ${e.message}")
                    viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                }
        }
    }

    fun searchAndClaimWharfJob(rego: String, onResult: (Boolean, String?) -> Unit) {
        val upperRego = rego.trim().uppercase()
        Log.d("FleetDebug", "Wharf Scan: Searching for $upperRego")
        
        firestore.collection("jobs")
            .whereEqualTo("rego", upperRego)
            .whereEqualTo("status", JobStatus.ONSCAN.name)
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val jobId = doc.id
                    
                    viewModelScope.launch {
                        val currentFleet = settingsDataStore.fleetNumber.firstOrNull() ?: ""
                        if (currentFleet.isEmpty()) {
                            withContext(Dispatchers.Main) { onResult(false, "Driver session error.") }
                            return@launch
                        }

                        firestore.collection("jobs").document(jobId)
                            .update(mapOf(
                                "status" to JobStatus.ACCEPTED.name,
                                "fleetNumber" to currentFleet
                            ))
                            .addOnSuccessListener {
                                Log.d("FleetDebug", "Wharf Scan: Job $jobId claimed by $currentFleet")
                                viewModelScope.launch { withContext(Dispatchers.Main) { onResult(true, null) } }
                            }
                            .addOnFailureListener { e ->
                                Log.e("FleetDebug", "Wharf Scan: Claim failed: ${e.message}")
                                viewModelScope.launch { withContext(Dispatchers.Main) { onResult(false, "Failed to claim.") } }
                            }
                    }
                } else {
                    onResult(false, "No 'ONSCAN' job found for '$upperRego'")
                }
            }
            .addOnFailureListener { e ->
                onResult(false, "Search failed: ${e.localizedMessage}")
            }
    }

    fun updateJobStatus(jobId: String, status: JobStatus) {
        Log.d("FleetDebug", "Updating job $jobId to status: ${status.name}")
        firestore.collection("jobs").document(jobId)
            .update("status", status.name)
            .addOnSuccessListener {
                Log.d("FleetDebug", "Firestore status update success for $jobId")
                viewModelScope.launch {
                    try {
                        dao.updateJobStatus(jobId, status)
                    } catch (e: Exception) {
                        Log.e("FleetDebug", "Local DB update failed for $jobId: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FleetDebug", "Firestore status update failed for $jobId: ${e.message}")
            }
    }

    fun getOrCreateFolderForJob(jobId: String, rego: String, onResult: (Long, String?) -> Unit) {
        viewModelScope.launch {
            Log.d("FleetDebug", "Creating/Getting folder for Job: $jobId, Rego: $rego")
            try {
                val foldersById = dao.getFoldersByJobId(jobId)
                if (foldersById.isNotEmpty()) {
                    val folderId = foldersById[0].id
                    Log.d("FleetDebug", "Found existing folder by ID: $folderId")
                    withContext(Dispatchers.Main) { onResult(folderId, null) }
                    return@launch
                }

                val foldersByName = dao.getFoldersByName(rego)
                if (foldersByName.isNotEmpty()) {
                    val folderId = foldersByName[0].id
                    Log.d("FleetDebug", "Found existing folder by Rego: $folderId. Linking to Job.")
                    dao.linkFolderToJob(folderId, jobId)
                    withContext(Dispatchers.Main) { onResult(folderId, null) }
                    return@launch
                }

                val newFolder = Folder(name = rego, jobId = jobId)
                val folderId = dao.insertFolder(newFolder)
                
                if (folderId > 0) {
                    Log.d("FleetDebug", "Created new folder: $folderId")
                    withContext(Dispatchers.Main) { onResult(folderId, null) }
                } else {
                    Log.e("FleetDebug", "Database insert returned invalid ID (0)")
                    withContext(Dispatchers.Main) { onResult(0L, "Database failed to create record.") }
                }
            } catch (e: Exception) {
                Log.e("FleetDebug", "CRITICAL ERROR in getOrCreateFolderForJob: ${e.message}", e)
                withContext(Dispatchers.Main) { onResult(0L, e.localizedMessage) }
            }
        }
    }

    fun submitSignoff(jobId: String, isDriver: Boolean, name: String, bitmap: android.graphics.Bitmap, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val nextStatus = if (isDriver) JobStatus.PICKED_UP else JobStatus.DONE
            Log.d("FleetDebug", "Starting signoff (Local) for $jobId. isDriver=$isDriver -> Next Status: $nextStatus")
            
            try {
                // 1. Save locally
                val filename = if (isDriver) "driver_sig.png" else "customer_sig.png"
                val file = File(getApplication<Application>().filesDir, "jobs/$jobId/$filename")
                file.parentFile?.mkdirs()
                
                file.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.d("FleetDebug", "Signature saved locally to: ${file.absolutePath}")

                // 2. Update Firestore
                val update = if (isDriver) {
                    mapOf("driverName" to name, "status" to nextStatus.name)
                } else {
                    mapOf("customerName" to name, "status" to nextStatus.name)
                }

                firestore.collection("jobs").document(jobId)
                    .update(update)
                    .addOnSuccessListener {
                        Log.d("FleetDebug", "Firestore signoff name/status update success: $nextStatus")
                        viewModelScope.launch {
                            try {
                                dao.updateJobStatus(jobId, nextStatus)
                            } catch (e: Exception) {}
                            withContext(Dispatchers.Main) { onComplete(true) }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("FleetDebug", "Firestore signoff update failed: ${e.message}", e)
                        viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                    }
            } catch (e: Exception) {
                Log.e("FleetDebug", "CRITICAL ERROR in local submitSignoff: ${e.message}", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    fun updateFolderChecklist(folderId: Long, checklistJson: String?) {
        viewModelScope.launch {
            Log.d("FleetDebug", "Updating folder checklist for folder: $folderId")
            dao.updateFolderChecklist(folderId, checklistJson)
            
            val folder = dao.getFolderByIdOnce(folderId)
            folder?.jobId?.let { jobId ->
                if (jobId.isNotEmpty() && checklistJson != null) {
                    val job = dao.getJobById(jobId)
                    if (job != null) {
                        Log.d("FleetDebug", "Updating job $jobId checklist. Current job status: ${job.status}")
                        val checklistList = try {
                            val items = Json.decodeFromString<List<ChecklistItem>>(checklistJson)
                            items.map { item ->
                                val map = mutableMapOf<String, Any>(
                                    "part" to item.part,
                                    "status" to item.status
                                )
                                if (!item.otherDetails.isNullOrBlank()) {
                                    map["status"] = "${item.status}${if (item.isTextFieldOnly) "" else " (${item.otherDetails})"}"
                                }
                                if (item.isTextFieldOnly) {
                                    map["isTextFieldOnly"] = true
                                    map["status"] = item.otherDetails ?: "OK"
                                }
                                map
                            }
                        } catch (e: Exception) {
                            emptyList<Map<String, Any>>()
                        }

                        val isPickup = job.status == JobStatus.ACCEPTED || job.status == JobStatus.NEW
                        val fieldName = if (isPickup) "pickupChecklist" else "dropoffChecklist"
                        
                        firestore.collection("jobs").document(jobId)
                            .update(
                                mapOf(
                                    fieldName to checklistList,
                                    "${fieldName}Json" to checklistJson
                                )
                            )
                            .addOnSuccessListener {
                                Log.d("FleetDebug", "Firestore checklist update success for field $fieldName")
                                viewModelScope.launch {
                                    if (isPickup) {
                                        dao.updateJobPickupChecklist(jobId, checklistJson)
                                    } else {
                                        dao.updateJobDeliveryChecklist(jobId, checklistJson)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    fun createFolder(name: String, onFolderCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val folderId = dao.insertFolder(Folder(name = name))
            withContext(Dispatchers.Main) { onFolderCreated(folderId) }
        }
    }

    fun getFolderById(folderId: Long): Flow<Folder?> {
        return dao.getFolderById(folderId)
    }

    suspend fun getFoldersByName(name: String): List<Folder> {
        return dao.getFoldersByName(name)
    }

    fun getMediaForFolder(folderId: Long): Flow<List<MediaAsset>> {
        return dao.getMediaForFolder(folderId)
    }

    fun addMediaAsset(folderId: Long, filePath: String, isVideo: Boolean) {
        viewModelScope.launch {
            dao.insertMediaAsset(MediaAsset(folderId = folderId, filePath = filePath, isVideo = isVideo))
        }
    }

    fun deleteMediaAsset(mediaId: Long) {
        viewModelScope.launch {
            dao.deleteMediaAsset(mediaId)
        }
    }

    fun updateMediaNotes(mediaId: Long, notes: String?) {
        viewModelScope.launch {
            dao.updateMediaNotes(mediaId, notes)
        }
    }

    fun updateLastMediaNote(folderId: Long, notes: String?) {
        viewModelScope.launch {
            dao.updateLastMediaNoteForFolder(folderId, notes)
        }
    }

    fun getMediaById(mediaId: Long): Flow<MediaAsset?> {
        return dao.getMediaById(mediaId)
    }

    fun setTimestampEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setTimestampEnabled(enabled)
        }
    }

    fun setDateFormat(format: String) {
        viewModelScope.launch {
            settingsDataStore.setDateFormat(format)
        }
    }

    fun setGpsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setGpsEnabled(enabled)
        }
    }

    fun setDarkMode(mode: String) {
        viewModelScope.launch {
            settingsDataStore.setDarkMode(mode)
        }
    }

    fun setImageQuality(quality: Int) {
        viewModelScope.launch {
            settingsDataStore.setImageQuality(quality)
        }
    }

    fun setShareSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setShareSummaryEnabled(enabled)
        }
    }

    fun setCaptureFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setCaptureFeedbackEnabled(enabled)
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            val outputDirectory = File(getApplication<Application>().filesDir, "folders/$folderId")
            if (outputDirectory.exists()) {
                outputDirectory.deleteRecursively()
            }
            dao.deleteFolder(folderId)
        }
    }

    fun exportVehicleZip(folderId: Long, onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val folder = dao.getFolderByIdOnce(folderId)
            val assets = dao.getMediaForFolderOnce(folderId)
            if (folder != null) {
                val exportManager = ExportManager(getApplication())
                val zipFile = exportManager.exportVehicle(folder, assets)
                withContext(Dispatchers.Main) { onResult(zipFile) }
            } else {
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun exportFullBackup(onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val folders = dao.getAllFoldersOnce()
            val allAssets = dao.getAllMediaAssetsOnce()
            val exportManager = ExportManager(getApplication())
            val zipFile = exportManager.exportFullBackup(folders, allAssets)
            withContext(Dispatchers.Main) { onResult(zipFile) }
        }
    }

    fun importFullBackup(zipFile: File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val exportManager = ExportManager(getApplication())
            exportManager.unzipAndImport(
                zipFile,
                onImportMetadata = { metadata, baseDir ->
                    viewModelScope.launch {
                        try {
                            metadata.folders.forEach { folder ->
                                val existing = dao.getFoldersByName(folder.name)
                                val folderToInsert = if (existing.any { it.createdAt == folder.createdAt }) {
                                    null 
                                } else {
                                    folder.copy(id = 0)
                                }

                                if (folderToInsert != null) {
                                    val newFolderId = dao.insertFolder(folderToInsert)
                                    val relatedAssets = metadata.mediaAssets.filter { it.folderId == folder.id }
                                    
                                    val destFolder = File(getApplication<Application>().filesDir, "folders/$newFolderId")
                                    destFolder.mkdirs()

                                    relatedAssets.forEach { asset ->
                                        val fileName = File(asset.filePath).name
                                        val sourceFile = exportManager.findFileInDir(baseDir, fileName)
                                        
                                        if (sourceFile != null) {
                                            val destFile = File(destFolder, fileName)
                                            sourceFile.copyTo(destFile, overwrite = true)
                                            dao.insertMediaAsset(asset.copy(id = 0, folderId = newFolderId, filePath = destFile.absolutePath))
                                        }
                                    }
                                }
                            }
                            onComplete(true)
                        } catch (e: Exception) {
                            onComplete(false)
                        } finally {
                            baseDir.deleteRecursively()
                        }
                    }
                },
                onFail = {
                    viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                }
            )
        }
    }
}
