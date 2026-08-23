package nz.co.fordwalls.transportercam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
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
    val startedAt: Long?,
    val firebaseAuthenticated: Boolean
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appUpdater = AppUpdater(application, viewModelScope)
    val updateState: StateFlow<UpdateState> = appUpdater.state
    private val dao: TransporterDao = AppDatabase.getDatabase(application).transporterDao()
    private val settingsDataStore = SettingsDataStore(application)
    
    private val firestoreResult = runCatching {
        FirebaseFirestore.getInstance(FirebaseApp.getInstance(), "transport")
    }
    private val firestore: FirebaseFirestore get() = firestoreResult.getOrThrow()
    private val paths by lazy { TenantFirestorePaths(firestore) }
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-east1")
    private val firebaseAuthenticated = MutableStateFlow(auth.currentUser != null)
    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        firebaseAuthenticated.value = firebaseAuth.currentUser != null
    }
    private val storageResult = runCatching { FirebaseStorage.getInstance(FirebaseApp.getInstance()) }
    private val storage: FirebaseStorage get() = storageResult.getOrThrow()
    
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
    private var unauthenticatedSessionCleanupJob: CoroutineJob? = null
    private var activeSessionKey: String? = null

    init {
        createNotificationChannel()
        appUpdater.check()
        auth.addAuthStateListener(authStateListener)
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
                combine(selectedCompanyId, driverDocumentId, fleetNumber, sessionStartedAt, firebaseAuthenticated) { company, driver, fleet, startedAt, authenticated ->
                    StoredSession(company, driver, fleet, startedAt, authenticated)
                }.collect { session ->
                    val company = session.companyId
                    val driver = session.driverId
                    val fleet = session.fleetNumber
                    val startedAt = session.startedAt
                    _activeCompanyId.value = company
                    val complete = !company.isNullOrBlank() && !driver.isNullOrBlank() && !fleet.isNullOrBlank() && startedAt != null
                    val valid = complete && session.firebaseAuthenticated && !SessionPolicy.isExpired(startedAt!!)
                    _isLoggedIn.value = valid
                    if (valid) {
                        unauthenticatedSessionCleanupJob?.cancel()
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
                        } else if (complete && !session.firebaseAuthenticated) {
                            unauthenticatedSessionCleanupJob?.cancel()
                            unauthenticatedSessionCleanupJob = viewModelScope.launch {
                                delay(1500)
                                if (auth.currentUser == null) settingsDataStore.clearDriverSession()
                            }
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
                            driverSignatureUrl = data["driverSignaturePath"]?.toString() ?: data["driverSignatureUrl"]?.toString(),
                            customerName = data["customerName"]?.toString(),
                            customerSignatureUrl = data["customerSignaturePath"]?.toString() ?: data["customerSignatureUrl"]?.toString(),
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
        val user = username.trim().lowercase()
        
        Log.d("FleetDebug", "Attempting login for: $user, Fleet: $upperFleet")
        
        if (_activeCompanyId.value != companyId) {
            onResult(false, "Company selection changed. Please try again.")
            return
        }
        functions.getHttpsCallable("driverLogin")
            .call(mapOf("companyId" to companyId, "username" to user, "password" to pass, "fleetNumber" to upperFleet))
            .addOnSuccessListener { callableResult ->
                @Suppress("UNCHECKED_CAST")
                val result = callableResult.data as? Map<String, Any?>
                val customToken = result?.get("token")?.toString().orEmpty()
                val driverDocId = result?.get("driverId")?.toString().orEmpty()
                val returnedCompany = result?.get("companyId")?.toString().orEmpty()
                val returnedFleet = result?.get("fleetNumber")?.toString().orEmpty()
                if (customToken.isBlank() || driverDocId.isBlank() || returnedCompany != companyId || returnedFleet != upperFleet) {
                    onResult(false, "The secure login response was incomplete. Please retry.")
                    return@addOnSuccessListener
                }
                auth.signInWithCustomToken(customToken)
                    .addOnSuccessListener {
                        viewModelScope.launch {
                            if (_activeCompanyId.value != companyId) {
                                auth.signOut()
                                withContext(Dispatchers.Main) { onResult(false, "Company selection changed. Please try again.") }
                                return@launch
                            }
                            settingsDataStore.commitDriverSession(driverDocId, upperFleet)
                            withContext(Dispatchers.Main) { onResult(true, null) }
                        }
                    }
                    .addOnFailureListener { error ->
                        onResult(false, "Secure sign-in failed: ${error.localizedMessage ?: "Please retry."}")
                    }
            }
            .addOnFailureListener { error ->
                val functionsError = error as? FirebaseFunctionsException
                val message = when (functionsError?.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> "The company, username, password, or fleet is incorrect."
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Too many attempts. Wait 15 minutes and retry."
                    FirebaseFunctionsException.Code.FAILED_PRECONDITION -> functionsError.message
                        ?: "This driver login must be secured by the Site Owner first."
                    FirebaseFunctionsException.Code.NOT_FOUND -> "The secure login service has not been deployed yet."
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "The secure login service could not be reached. Retry when signal returns."
                    else -> functionsError?.message ?: error.localizedMessage ?: "Secure login failed."
                }
                onResult(false, message)
            }
    }

    fun logout() {
        activeSessionKey = null
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        unauthenticatedSessionCleanupJob?.cancel()
        stopJobsListener()
        _jobs.value = emptyList()
        auth.signOut()
        viewModelScope.launch {
            settingsDataStore.clearDriverSession()
        }
    }

    fun changeCompany() {
        activeSessionKey = null
        sessionExpiryJob?.cancel()
        sessionExpiryJob = null
        unauthenticatedSessionCleanupJob?.cancel()
        stopJobsListener()
        _jobs.value = emptyList()
        auth.signOut()
        viewModelScope.launch { settingsDataStore.clearCompanyAndDriverSession() }
    }

    override fun onCleared() {
        unauthenticatedSessionCleanupJob?.cancel()
        auth.removeAuthStateListener(authStateListener)
        super.onCleared()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setNotificationsEnabled(enabled)
        }
    }

    fun checkForUpdates() = appUpdater.check()

    fun downloadUpdate(manifest: UpdateManifest) = appUpdater.download(manifest)

    fun installUpdate(manifest: UpdateManifest, apk: File): String? = appUpdater.openInstaller(manifest, apk)

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

    private fun safeStorageSegment(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "unknown" }

    private fun evidencePhase(job: Job): String =
        if (job.status == JobStatus.NEW || job.status == JobStatus.ACCEPTED || job.status == JobStatus.ONSCAN) "pickup" else "delivery"

    private fun mediaContentType(file: File, isVideo: Boolean): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> if (isVideo) "video/mp4" else "image/jpeg"
    }

    private fun mainResult(callback: (Boolean, String?) -> Unit, success: Boolean, message: String?) {
        viewModelScope.launch { withContext(Dispatchers.Main) { callback(success, message) } }
    }

    private fun evidenceErrorText(error: Throwable): String =
        (error.localizedMessage ?: error.message ?: "Unknown upload error").take(300)

    private fun recordEvidenceFailure(companyId: String, jobId: String, evidenceId: String, error: Throwable) {
        paths.evidence(companyId, jobId).document(evidenceId).set(
            mapOf(
                "status" to "failed",
                "error" to evidenceErrorText(error),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }

    fun submitSignoff(jobId: String, isDriver: Boolean, name: String, bitmap: android.graphics.Bitmap, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch withContext(Dispatchers.Main) { onComplete(false) }
            if (jobId.isBlank()) return@launch withContext(Dispatchers.Main) { onComplete(false) }
            val nextStatus = if (isDriver) JobStatus.PICKED_UP else JobStatus.DONE
            Log.d("FleetDebug", "Starting cloud signoff for $companyId/$jobId. isDriver=$isDriver -> Next Status: $nextStatus")

            try {
                val filename = if (isDriver) "driver_sig.png" else "customer_sig.png"
                val file = File(getApplication<Application>().filesDir, "jobs/$companyId/$jobId/$filename")
                file.parentFile?.mkdirs()
                file.outputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                if (storageResult.isFailure) throw storageResult.exceptionOrNull() ?: IllegalStateException("Firebase Storage is unavailable")

                val role = if (isDriver) "driver" else "customer"
                val phase = if (isDriver) "pickup" else "delivery"
                val evidenceId = "$role-signature"
                val evidenceDoc = paths.evidence(companyId, jobId).document(evidenceId)
                val driverId = settingsDataStore.driverDocumentId.firstOrNull().orEmpty()
                val fleet = settingsDataStore.fleetNumber.firstOrNull().orEmpty()
                val driverUid = auth.currentUser?.uid.orEmpty()
                if (driverId.isBlank() || driverUid.isBlank()) throw IllegalStateException("The secure driver session has expired")
                val storagePath = "companies/${safeStorageSegment(companyId)}/drivers/${safeStorageSegment(driverUid)}/jobs/${safeStorageSegment(jobId)}/evidence/signatures/$evidenceId.png"
                val pending = mapOf(
                    "type" to "signature",
                    "phase" to phase,
                    "signerRole" to role,
                    "signerName" to name.trim(),
                    "companyId" to companyId,
                    "jobId" to jobId,
                    "driverId" to driverId,
                    "driverUid" to driverUid,
                    "fleetNumber" to fleet,
                    "status" to "uploading",
                    "storagePath" to storagePath,
                    "contentType" to "image/png",
                    "capturedAt" to System.currentTimeMillis(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                evidenceDoc.set(pending, SetOptions.merge())
                    .addOnSuccessListener {
                        val storageRef = storage.reference.child(storagePath)
                        val metadata = StorageMetadata.Builder()
                            .setContentType("image/png")
                            .setCustomMetadata("companyId", companyId)
                            .setCustomMetadata("jobId", jobId)
                            .setCustomMetadata("evidenceId", evidenceId)
                            .setCustomMetadata("driverId", driverId)
                            .setCustomMetadata("driverUid", driverUid)
                            .build()
                        storageRef.putFile(Uri.fromFile(file), metadata)
                            .addOnSuccessListener {
                                val jobUpdate: Map<String, Any> = if (isDriver) {
                                    mapOf(
                                        "driverName" to name.trim(),
                                        "driverSignaturePath" to storagePath,
                                        "status" to nextStatus.name,
                                        "evidenceUpdatedAt" to FieldValue.serverTimestamp()
                                    )
                                } else {
                                    mapOf(
                                        "customerName" to name.trim(),
                                        "customerSignaturePath" to storagePath,
                                        "status" to nextStatus.name,
                                        "evidenceUpdatedAt" to FieldValue.serverTimestamp()
                                    )
                                }
                                firestore.runBatch { batch ->
                                    batch.update(paths.jobs(companyId).document(jobId), jobUpdate)
                                    batch.set(
                                        evidenceDoc,
                                        mapOf(
                                            "status" to "ready",
                                            "error" to "",
                                            "uploadedAt" to FieldValue.serverTimestamp(),
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        ),
                                        SetOptions.merge()
                                    )
                                }.addOnSuccessListener {
                                    viewModelScope.launch {
                                        if (isDriver) dao.markJobAsPickedUp(companyId, jobId, name.trim(), storagePath)
                                        else dao.markJobAsDone(companyId, jobId, name.trim(), storagePath)
                                        withContext(Dispatchers.Main) { onComplete(true) }
                                    }
                                }.addOnFailureListener { error ->
                                    recordEvidenceFailure(companyId, jobId, evidenceId, error)
                                    Log.e("FleetDebug", "Signature metadata update failed", error)
                                    viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                                }
                            }
                            .addOnFailureListener { error ->
                                recordEvidenceFailure(companyId, jobId, evidenceId, error)
                                Log.e("FleetDebug", "Signature upload failed", error)
                                viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                            }
                    }
                    .addOnFailureListener { error ->
                        Log.e("FleetDebug", "Could not create signature evidence record", error)
                        viewModelScope.launch { withContext(Dispatchers.Main) { onComplete(false) } }
                    }
            } catch (e: Exception) {
                Log.e("FleetDebug", "CRITICAL ERROR in submitSignoff: ${e.message}", e)
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

    fun addMediaAsset(
        folderId: Long,
        filePath: String,
        isVideo: Boolean,
        onUploadComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch
            val folder = dao.getFolderByIdOnce(companyId, folderId) ?: return@launch
            val jobId = folder.jobId
            val job = jobId?.takeIf { it.isNotBlank() }?.let { dao.getJobById(companyId, it) }
            val phase = job?.let(::evidencePhase)
            val assetId = dao.insertMediaAsset(
                MediaAsset(
                    folderId = folderId,
                    filePath = filePath,
                    isVideo = isVideo,
                    jobId = jobId,
                    evidencePhase = phase,
                    cloudState = if (job == null) "LOCAL_ONLY" else "PENDING"
                )
            )
            val asset = dao.getMediaByIdOnce(companyId, assetId)
            if (asset == null || job == null) {
                mainResult(onUploadComplete, true, "Saved on this phone. This media is not linked to a dispatched job.")
                return@launch
            }
            uploadMediaAsset(companyId, asset, job, onUploadComplete)
        }
    }

    fun retryMediaUpload(mediaId: Long, onUploadComplete: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value
                ?: return@launch mainResult(onUploadComplete, false, "Driver session is unavailable.")
            val asset = dao.getMediaByIdOnce(companyId, mediaId)
                ?: return@launch mainResult(onUploadComplete, false, "The local photo could not be found.")
            val jobId = asset.jobId
                ?: dao.getFolderByIdOnce(companyId, asset.folderId)?.jobId
                ?: return@launch mainResult(onUploadComplete, false, "This photo is not linked to a dispatched job.")
            val job = dao.getJobById(companyId, jobId)
                ?: return@launch mainResult(onUploadComplete, false, "The linked job is no longer available on this phone.")
            uploadMediaAsset(companyId, asset.copy(jobId = jobId), job, onUploadComplete)
        }
    }

    private suspend fun uploadMediaAsset(
        companyId: String,
        asset: MediaAsset,
        job: Job,
        onUploadComplete: (Boolean, String?) -> Unit
    ) {
        val file = File(asset.filePath)
        if (!file.exists()) {
            dao.updateMediaCloudState(companyId, asset.id, asset.evidenceId, asset.evidencePhase, "FAILED", asset.storagePath, null, "Local file is missing")
            mainResult(onUploadComplete, false, "The local photo file is missing.")
            return
        }
        if (storageResult.isFailure) {
            val error = storageResult.exceptionOrNull() ?: IllegalStateException("Firebase Storage is unavailable")
            dao.updateMediaCloudState(companyId, asset.id, asset.evidenceId, asset.evidencePhase, "FAILED", asset.storagePath, null, evidenceErrorText(error))
            mainResult(onUploadComplete, false, "Saved on this phone, but Firebase Storage is unavailable.")
            return
        }

        val driverId = settingsDataStore.driverDocumentId.firstOrNull().orEmpty()
        val driverUid = auth.currentUser?.uid.orEmpty()
        if (driverId.isBlank() || driverUid.isBlank()) {
            val error = IllegalStateException("The secure driver session has expired")
            dao.updateMediaCloudState(companyId, asset.id, asset.evidenceId, asset.evidencePhase, "FAILED", asset.storagePath, null, evidenceErrorText(error))
            mainResult(onUploadComplete, false, "Your secure driver session has expired. Sign in again, then tap Retry.")
            return
        }
        val fleet = settingsDataStore.fleetNumber.firstOrNull().orEmpty()
        val phase = asset.evidencePhase ?: evidencePhase(job)
        val evidenceId = asset.evidenceId ?: "media-${safeStorageSegment(driverId.ifBlank { fleet })}-${asset.id}"
        val extension = file.extension.lowercase().ifBlank { if (asset.isVideo) "mp4" else "jpg" }
        val securePrefix = "companies/${safeStorageSegment(companyId)}/drivers/${safeStorageSegment(driverUid)}/jobs/${safeStorageSegment(job.id)}/evidence/"
        val storagePath = asset.storagePath?.takeIf { it.startsWith(securePrefix) }
            ?: "$securePrefix$phase/$evidenceId.${safeStorageSegment(extension)}"
        val contentType = mediaContentType(file, asset.isVideo)
        val evidenceDoc = paths.evidence(companyId, job.id).document(evidenceId)
        val pending = mapOf(
            "type" to if (asset.isVideo) "video" else "photo",
            "phase" to phase,
            "companyId" to companyId,
            "jobId" to job.id,
            "driverId" to driverId,
            "driverUid" to driverUid,
            "fleetNumber" to fleet,
            "status" to "uploading",
            "storagePath" to storagePath,
            "contentType" to contentType,
            "originalName" to file.name,
            "notes" to (asset.notes ?: ""),
            "capturedAt" to asset.timestamp,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        dao.updateMediaCloudState(companyId, asset.id, evidenceId, phase, "UPLOADING", storagePath, null, null)
        evidenceDoc.set(pending, SetOptions.merge())
            .addOnSuccessListener {
                val storageRef = storage.reference.child(storagePath)
                val metadata = StorageMetadata.Builder()
                    .setContentType(contentType)
                    .setCustomMetadata("companyId", companyId)
                    .setCustomMetadata("jobId", job.id)
                    .setCustomMetadata("evidenceId", evidenceId)
                    .setCustomMetadata("phase", phase)
                    .setCustomMetadata("driverId", driverId)
                    .setCustomMetadata("driverUid", driverUid)
                    .build()
                storageRef.putFile(Uri.fromFile(file), metadata)
                    .addOnSuccessListener {
                        firestore.runBatch { batch ->
                            batch.set(
                                evidenceDoc,
                                mapOf(
                                    "status" to "ready",
                                    "error" to "",
                                    "uploadedAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                            batch.update(paths.jobs(companyId).document(job.id), mapOf("evidenceUpdatedAt" to FieldValue.serverTimestamp()))
                        }.addOnSuccessListener {
                            viewModelScope.launch {
                                dao.updateMediaCloudState(companyId, asset.id, evidenceId, phase, "READY", storagePath, null, null)
                                mainResult(onUploadComplete, true, if (asset.isVideo) "Video uploaded to FW Dispatch." else "Photo uploaded to FW Dispatch.")
                            }
                        }.addOnFailureListener { error ->
                            recordEvidenceFailure(companyId, job.id, evidenceId, error)
                            viewModelScope.launch {
                                dao.updateMediaCloudState(companyId, asset.id, evidenceId, phase, "FAILED", storagePath, null, evidenceErrorText(error))
                                mainResult(onUploadComplete, false, "Saved on this phone, but FW Dispatch could not record the upload. Tap Retry in the gallery.")
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        recordEvidenceFailure(companyId, job.id, evidenceId, error)
                        viewModelScope.launch {
                            dao.updateMediaCloudState(companyId, asset.id, evidenceId, phase, "FAILED", storagePath, null, evidenceErrorText(error))
                            mainResult(onUploadComplete, false, "Saved on this phone, but upload failed. Tap Retry in the gallery when signal returns.")
                        }
                    }
            }
            .addOnFailureListener { error ->
                viewModelScope.launch {
                    dao.updateMediaCloudState(companyId, asset.id, evidenceId, phase, "FAILED", storagePath, null, evidenceErrorText(error))
                    mainResult(onUploadComplete, false, "Saved on this phone, but the upload record could not be created. Tap Retry in the gallery.")
                }
            }
    }

    fun deleteMediaAsset(mediaId: Long) {
        viewModelScope.launch {
            _activeCompanyId.value?.let { dao.deleteMediaAsset(it, mediaId) }
        }
    }

    fun updateMediaNotes(mediaId: Long, notes: String?) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch
            dao.updateMediaNotes(companyId, mediaId, notes)
            dao.getMediaByIdOnce(companyId, mediaId)?.let { syncEvidenceNotes(companyId, it, notes) }
        }
    }

    fun updateLastMediaNote(folderId: Long, notes: String?) {
        viewModelScope.launch {
            val companyId = _activeCompanyId.value ?: return@launch
            dao.updateLastMediaNoteForFolder(companyId, folderId, notes)
            dao.getLatestMediaForFolderOnce(companyId, folderId)?.let { syncEvidenceNotes(companyId, it, notes) }
        }
    }

    private fun syncEvidenceNotes(companyId: String, asset: MediaAsset, notes: String?) {
        val jobId = asset.jobId ?: return
        val evidenceId = asset.evidenceId ?: return
        paths.evidence(companyId, jobId).document(evidenceId).set(
            mapOf("notes" to (notes ?: ""), "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge()
        )
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
