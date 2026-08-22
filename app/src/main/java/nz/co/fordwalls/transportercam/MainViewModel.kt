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
import com.google.firebase.firestore.FieldValue
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

private data class StoredSession(
    val companyId: String?,
    val driverId: String?,
    val fleetNumber: String?,
    val startedAt: Long?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao: TransporterDao = AppDatabase.getDatabase(application).transporterDao()
    private val settingsDataStore = SettingsDataStore(application)
    
    private val firestoreResult = runCatching {
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "transport")
    }
    private val firestore: FirebaseFirestore get() = firestoreResult.getOrThrow()
    private val paths by lazy { TenantFirestorePaths(firestore) }
    
    val fleetNumber: Flow<String?> = settingsDataStore.fleetNumber
    val selectedCompanyId: Flow<String?> = settingsDataStore.selectedCompanyId
    val selectedCompanyName: Flow<String?> = settingsDataStore.selectedCompanyName
    val driverDocumentId: Flow<String?> = settingsDataStore.driverDocumentId
    val sessionStartedAt: Flow<Long?> = settingsDataStore.sessionStartedAt
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn
    private val _companies = MutableStateFlow<List<CompanySummary>>(emptyList())
    val companies: StateFlow<List<CompanySummary>> = _companies
    private val _companiesLoading = MutableStateFlow(true)
    val companiesLoading: StateFlow<Boolean> = _companiesLoading
    private val _companyLoadError = MutableStateFlow<String?>(null)
    val companyLoadError: StateFlow<String?> = _companyLoadError
    private val _companyListOffline = MutableStateFlow(false)
    val companyListOffline: StateFlow<Boolean> = _companyListOffline
    private val _activeCompanyId = MutableStateFlow<String?>(null)
    private val _prestartRequired = MutableStateFlow<Boolean?>(null)
    val prestartRequired: StateFlow<Boolean?> = _prestartRequired
    private val _prestartCheckError = MutableStateFlow<String?>(null)
    val prestartCheckError: StateFlow<String?> = _prestartCheckError

    private var jobsListener: ListenerRegistration? = null
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allFolders: Flow<List<Folder>> = selectedCompanyId.flatMapLatest { companyId ->
        if (companyId == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.getAllFolders(companyId)
    }
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val foldersWithTimestamps: Flow<List<FolderWithTimestamps>> = allFolders.flatMapLatest { folders ->
        if (folders.isEmpty()) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
        
        val flows = folders.map { folder ->
            dao.getMediaForFolder(folder.companyId, folder.id).map { media ->
                FolderWithTimestamps(
                    folder = folder,
                    earliestMedia = media.minByOrNull { it.timestamp }?.timestamp,
                    latestMedia = media.maxByOrNull { it.timestamp }?.timestamp
                )
            }
        }
        combine(flows) { it.toList() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allMediaAssets: Flow<List<MediaAsset>> = selectedCompanyId.flatMapLatest { companyId ->
        if (companyId == null) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.getAllMediaAssets(companyId)
    }

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
    private var sessionExpiryJob: CoroutineJob? = null
    private var activeSessionKey: String? = null

    init {
        createNotificationChannel()
        if (firestoreResult.isFailure) {
            _companiesLoading.value = false
            _companyLoadError.value = "Cannot connect to the required Firestore database 'transport'."
            Log.e("FleetDebug", "Named Firestore database initialization failed", firestoreResult.exceptionOrNull())
        } else {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            firestore.firestoreSettings = settings

            viewModelScope.launch { settingsDataStore.migrateExistingSessionToFordWalls() }
            viewModelScope.launch {
                combine(selectedCompanyId, driverDocumentId, fleetNumber, sessionStartedAt) { company, driver, fleet, startedAt ->
                    StoredSession(company, driver, fleet, startedAt)
                }.collect { session ->
                    val company = session.companyId
                    val driver = session.driverId
                    val fleet = session.fleetNumber
                    val startedAt = session.startedAt
                    _activeCompanyId.value = company
                    val complete = !company.isNullOrBlank() && !driver.isNullOrBlank() && !fleet.isNullOrBlank() && startedAt != null
                    val valid = complete && !SessionPolicy.isExpired(startedAt!!)
                    _isLoggedIn.value = valid
                    if (valid) {
                        val sessionKey = "$company::$driver::$fleet::$startedAt"
                        if (activeSessionKey != sessionKey) {
                            activeSessionKey = sessionKey
                            startJobsListener(company!!, fleet!!)
                            scheduleSessionExpiry(sessionKey, startedAt!!)
                            checkDailyPrestart(company, fleet)
                        }
                    } else {
                        if (complete && SessionPolicy.isExpired(startedAt!!)) {
                            settingsDataStore.clearDriverSession()
                        }
                        activeSessionKey = null
                        sessionExpiryJob?.cancel()
                        stopJobsListener()
                        _jobs.value = emptyList()
                        _prestartRequired.value = null
                    }
                }
            }
            loadCompanies()
        }
    }

    private fun scheduleSessionExpiry(sessionKey: String, startedAt: Long) {
        sessionExpiryJob?.cancel()
        sessionExpiryJob = viewModelScope.launch {
            delay(SessionPolicy.remainingMillis(startedAt))
            if (activeSessionKey == sessionKey) logout()
        }
    }

    private fun checkDailyPrestart(companyId: String, fleet: String) {
        val sessionKey = activeSessionKey
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _prestartRequired.value = null
        _prestartCheckError.value = null
        paths.prestarts(companyId, fleet).whereEqualTo("date", today).limit(1).get()
            .addOnSuccessListener { snapshot ->
                if (activeSessionKey != sessionKey) return@addOnSuccessListener
                _prestartRequired.value = snapshot.isEmpty
                Log.d(
                    "FleetDebug",
                    "Daily pre-start check $companyId/$fleet date=$today found=${snapshot.size()} ids=${snapshot.documents.joinToString { it.id }}"
                )
            }
            .addOnFailureListener { error ->
                if (activeSessionKey != sessionKey) return@addOnFailureListener
                _prestartCheckError.value = "Could not verify today's pre-start: ${error.localizedMessage}"
                _prestartRequired.value = true
                Log.e("FleetDebug", "Daily pre-start check failed for $companyId/$fleet", error)
            }
    }

    fun loadCompanies() {
        if (firestoreResult.isFailure) {
            _companiesLoading.value = false
            _companyLoadError.value = "Cannot connect to the required Firestore database 'transport'."
            return
        }
        _companiesLoading.value = true
        _companyLoadError.value = null
        paths.companies().whereEqualTo("active", true).get()
            .addOnSuccessListener { snapshot ->
                _companies.value = snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    CompanySummary(doc.id.lowercase(), name, doc.getString("shortName"), doc.getString("accentColor"))
                }.sortedBy { it.name }
                _companyListOffline.value = snapshot.metadata.isFromCache
                _companiesLoading.value = false
                if (_companies.value.isEmpty()) {
                    _companyLoadError.value = "No active companies are configured in the company directory."
                }
                Log.d("FleetDebug", "Company directory returned ${_companies.value.size} active companies; cache=${snapshot.metadata.isFromCache}")
            }
            .addOnFailureListener { error ->
                _companiesLoading.value = false
                _companyListOffline.value = _companies.value.isNotEmpty()
                _companyLoadError.value = if (_companies.value.isEmpty())
                    "Unable to load companies: ${error.localizedMessage}" else null
                Log.e("FleetDebug", "Company directory read failed", error)
            }
    }

    fun selectCompany(company: CompanySummary) {
        viewModelScope.launch { settingsDataStore.selectCompany(company) }
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

    private fun startJobsListener(companyId: String, fleetNumber: String) {
        stopJobsListener()
        Log.d("FleetDebug", "Starting jobs listener for fleet: $fleetNumber")
        
        isFirstSync = true
        jobsListener = paths.jobs(companyId)
            .whereEqualTo("fleetNumber", fleetNumber)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (_activeCompanyId.value != companyId) return@addSnapshotListener
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

                        val clearedFromFleets = data["clearedFromFleets"] as? List<*>
                        if (clearedFromFleets?.any { it?.toString() == fleetNumber } == true) {
                            return@forEach
                        }
                        
                        val createdAtRaw = data["createdAt"]
                        val createdAtMs = when (createdAtRaw) {
                            is Number -> createdAtRaw.toLong()
                            is String -> try { isoFormat.parse(createdAtRaw)?.time ?: System.currentTimeMillis() } catch(e: Exception) { System.currentTimeMillis() }
                            else -> System.currentTimeMillis()
                        }

                        val job = Job(
                            companyId = companyId,
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
            putExtra("companyId", _activeCompanyId.value)
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
        notificationJob?.cancel()
        notificationJob = null
        pendingNotificationCount = 0
    }

    fun unifiedLogin(companyId: String, username: String, pass: String, fleet: String, onResult: (Boolean, String?) -> Unit) {
        val upperFleet = fleet.trim().uppercase()
        val user = username.trim()
        
        Log.d("FleetDebug", "Attempting login for: $user, Fleet: $upperFleet")
        
        if (_activeCompanyId.value != companyId) {
            onResult(false, "Company selection changed. Please try again.")
            return
        }
        paths.fleets(companyId).document(upperFleet).get()
            .addOnSuccessListener { fleetDoc ->
                if (fleetDoc.exists()) {
                    paths.drivers(companyId)
                        .whereEqualTo("username", user)
                        .whereEqualTo("password", pass)
                        .get()
                        .addOnSuccessListener { driverSnapshot ->
                            if (!driverSnapshot.isEmpty) {
                                viewModelScope.launch {
                                    val driverDocId = driverSnapshot.documents[0].id
                                    if (_activeCompanyId.value != companyId) {
                                        withContext(Dispatchers.Main) { onResult(false, "Company selection changed. Please try again.") }
                                        return@launch
                                    }
                                    paths.drivers(companyId).document(driverDocId)
                                        .update("currentFleetNumber", upperFleet)
                                    settingsDataStore.commitDriverSession(driverDocId, upperFleet)
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
                    onResult(false, "Invalid username or password.")
                }
            }
            .addOnFailureListener { e ->
                onResult(false, "Connection error: ${e.localizedMessage}")
            }
    }

    fun logout() {
        activeSessionKey = null
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        stopJobsListener()
        _jobs.value = emptyList()
        viewModelScope.launch {
            settingsDataStore.clearDriverSession()
        }
    }

    fun changeCompany() {
        activeSessionKey = null
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        stopJobsListener()
        _jobs.value = emptyList()
        viewModelScope.launch { settingsDataStore.clearCompanyAndDriverSession() }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotificationsEnabled(enabled)
        }
    }

    fun savePrestart(fleet: String, items: List<PrestartItem>, notes: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch onComplete(false)
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

            paths.prestarts(companyId, fleet).document(docId)
                .set(payload)
                .addOnSuccessListener {
                    Log.d("FleetDebug", "Prestart saved successfully: $docId")
                    if (_activeCompanyId.value == companyId) {
                        _prestartRequired.value = false
                        _prestartCheckError.value = null
                    }
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
        val companyId = _activeCompanyId.value ?: return onResult(false, "Driver session error.")
        Log.d("FleetDebug", "Wharf Scan: Searching for $upperRego")
        
        paths.jobs(companyId)
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

                        if (_activeCompanyId.value != companyId) {
                            withContext(Dispatchers.Main) { onResult(false, "Company selection changed.") }
                            return@launch
                        }
                        paths.jobs(companyId).document(jobId)
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
        val companyId = _activeCompanyId.value ?: return
        Log.d("FleetDebug", "Updating job $jobId to status: ${status.name}")
        paths.jobs(companyId).document(jobId)
            .update("status", status.name)
            .addOnSuccessListener {
                Log.d("FleetDebug", "Firestore status update success for $jobId")
                viewModelScope.launch {
                    try {
                        dao.updateJobStatus(companyId, jobId, status)
                    } catch (e: Exception) {
                        Log.e("FleetDebug", "Local DB update failed for $jobId: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("FleetDebug", "Firestore status update failed for $jobId: ${e.message}")
            }
    }

    fun clearDoneJobs(onComplete: (Boolean) -> Unit) {
        val companyId = _activeCompanyId.value ?: return onComplete(false)
        val fleet = _jobs.value.firstOrNull()?.fleetNumber
            ?: return onComplete(true)
        val doneJobs = _jobs.value.filter { it.status == JobStatus.DONE }
        if (doneJobs.isEmpty()) return onComplete(true)

        val batches = doneJobs.chunked(450)

        fun commitBatch(index: Int) {
            if (index >= batches.size) {
                onComplete(true)
                return
            }

            val batch = firestore.batch()
            batches[index].forEach { job ->
                batch.update(
                    paths.jobs(companyId).document(job.id),
                    "clearedFromFleets",
                    FieldValue.arrayUnion(fleet)
                )
            }
            batch.commit()
                .addOnSuccessListener { commitBatch(index + 1) }
                .addOnFailureListener { error ->
                    Log.e("FleetDebug", "Failed to clear completed jobs", error)
                    onComplete(false)
                }
        }

        commitBatch(0)
    }

    fun getOrCreateFolderForJob(jobId: String, rego: String, onResult: (Long, String?) -> Unit) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch withContext(Dispatchers.Main) { onResult(0, "Driver session error.") }
            Log.d("FleetDebug", "Creating/Getting folder for Job: $jobId, Rego: $rego")
            try {
                val foldersById = dao.getFoldersByJobId(companyId, jobId)
                if (foldersById.isNotEmpty()) {
                    val folderId = foldersById[0].id
                    Log.d("FleetDebug", "Found existing folder by ID: $folderId")
                    withContext(Dispatchers.Main) { onResult(folderId, null) }
                    return@launch
                }

                val foldersByName = dao.getFoldersByName(companyId, rego)
                if (foldersByName.isNotEmpty()) {
                    val folderId = foldersByName[0].id
                    Log.d("FleetDebug", "Found existing folder by Rego: $folderId. Linking to Job.")
                    dao.linkFolderToJob(companyId, folderId, jobId)
                    withContext(Dispatchers.Main) { onResult(folderId, null) }
                    return@launch
                }

                val newFolder = Folder(companyId = companyId, name = rego, jobId = jobId)
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
            val companyId = _activeCompanyId.value ?: return@launch withContext(Dispatchers.Main) { onComplete(false) }
            val nextStatus = if (isDriver) JobStatus.PICKED_UP else JobStatus.DONE
            Log.d("FleetDebug", "Starting signoff (Local) for $jobId. isDriver=$isDriver -> Next Status: $nextStatus")
            
            try {
                // 1. Save locally
                val filename = if (isDriver) "driver_sig.png" else "customer_sig.png"
                val file = File(getApplication<Application>().filesDir, "jobs/$companyId/$jobId/$filename")
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

                paths.jobs(companyId).document(jobId)
                    .update(update)
                    .addOnSuccessListener {
                        Log.d("FleetDebug", "Firestore signoff name/status update success: $nextStatus")
                        viewModelScope.launch {
                            try {
                                dao.updateJobStatus(companyId, jobId, nextStatus)
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
            val companyId = _activeCompanyId.value ?: return@launch
            Log.d("FleetDebug", "Updating folder checklist for folder: $folderId")
            dao.updateFolderChecklist(companyId, folderId, checklistJson)
            
            val folder = dao.getFolderByIdOnce(companyId, folderId)
            folder?.jobId?.let { jobId ->
                if (jobId.isNotEmpty() && checklistJson != null) {
                    val job = dao.getJobById(companyId, jobId)
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
                        
                        paths.jobs(companyId).document(jobId)
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
                                        dao.updateJobPickupChecklist(companyId, jobId, checklistJson)
                                    } else {
                                        dao.updateJobDeliveryChecklist(companyId, jobId, checklistJson)
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
            val companyId = _activeCompanyId.value ?: return@launch
            val folderId = dao.insertFolder(Folder(companyId = companyId, name = name))
            withContext(Dispatchers.Main) { onFolderCreated(folderId) }
        }
    }

    fun getFolderById(folderId: Long): Flow<Folder?> {
        val companyId = _activeCompanyId.value ?: return kotlinx.coroutines.flow.flowOf(null)
        return dao.getFolderById(companyId, folderId)
    }

    suspend fun getFoldersByName(name: String): List<Folder> {
        val companyId = _activeCompanyId.value ?: return emptyList()
        return dao.getFoldersByName(companyId, name)
    }

    fun getMediaForFolder(folderId: Long): Flow<List<MediaAsset>> {
        val companyId = _activeCompanyId.value ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.getMediaForFolder(companyId, folderId)
    }

    fun addMediaAsset(folderId: Long, filePath: String, isVideo: Boolean) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch
            if (dao.getFolderByIdOnce(companyId, folderId) == null) return@launch
            dao.insertMediaAsset(MediaAsset(folderId = folderId, filePath = filePath, isVideo = isVideo))
        }
    }

    fun deleteMediaAsset(mediaId: Long) {
        viewModelScope.launch {
            _activeCompanyId.value?.let { dao.deleteMediaAsset(it, mediaId) }
        }
    }

    fun updateMediaNotes(mediaId: Long, notes: String?) {
        viewModelScope.launch {
            _activeCompanyId.value?.let { dao.updateMediaNotes(it, mediaId, notes) }
        }
    }

    fun updateLastMediaNote(folderId: Long, notes: String?) {
        viewModelScope.launch {
            _activeCompanyId.value?.let { dao.updateLastMediaNoteForFolder(it, folderId, notes) }
        }
    }

    fun getMediaById(mediaId: Long): Flow<MediaAsset?> {
        val companyId = _activeCompanyId.value ?: return kotlinx.coroutines.flow.flowOf(null)
        return dao.getMediaById(companyId, mediaId)
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
            val companyId = _activeCompanyId.value ?: return@launch
            val outputDirectory = File(getApplication<Application>().filesDir, "folders/$folderId")
            if (outputDirectory.exists()) {
                outputDirectory.deleteRecursively()
            }
            dao.deleteFolder(companyId, folderId)
        }
    }

    fun exportVehicleZip(folderId: Long, onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch withContext(Dispatchers.Main) { onResult(null) }
            val folder = dao.getFolderByIdOnce(companyId, folderId)
            val assets = dao.getMediaForFolderOnce(companyId, folderId)
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
            val companyId = _activeCompanyId.value ?: return@launch withContext(Dispatchers.Main) { onResult(null) }
            val folders = dao.getAllFoldersOnce(companyId)
            val allAssets = dao.getAllMediaAssetsOnce(companyId)
            val exportManager = ExportManager(getApplication())
            val zipFile = exportManager.exportFullBackup(folders, allAssets)
            withContext(Dispatchers.Main) { onResult(zipFile) }
        }
    }

    fun importFullBackup(zipFile: File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch onComplete(false)
            val exportManager = ExportManager(getApplication())
            exportManager.unzipAndImport(
                zipFile,
                onImportMetadata = { metadata, baseDir ->
                    viewModelScope.launch {
                        try {
                            metadata.folders.forEach { folder ->
                                val existing = dao.getFoldersByName(companyId, folder.name)
                                val folderToInsert = if (existing.any { it.createdAt == folder.createdAt }) {
                                    null 
                                } else {
                                    folder.copy(id = 0, companyId = companyId)
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
