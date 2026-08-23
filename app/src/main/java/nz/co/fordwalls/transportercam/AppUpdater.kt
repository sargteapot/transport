package nz.co.fordwalls.transportercam

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minimumVersionCode: Int? = null,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: List<String> = emptyList()
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest, val required: Boolean) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Int?) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val apk: File) : UpdateState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateState
}

internal fun isUpdateRequired(currentVersionCode: Int, manifest: UpdateManifest): Boolean =
    (manifest.minimumVersionCode ?: 0) > currentVersionCode

internal fun validateUpdateManifest(currentVersionCode: Int, manifest: UpdateManifest) {
    require(manifest.versionCode > currentVersionCode) { "The update version is not newer than this app." }
    require((manifest.minimumVersionCode ?: 0) in 0..manifest.versionCode) { "The minimum supported version is invalid." }
    require(manifest.versionName.isNotBlank()) { "The update version name is missing." }
    require(URI(manifest.apkUrl).scheme.equals("https", ignoreCase = true)) { "The APK URL must use HTTPS." }
    require(manifest.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "The APK SHA-256 value is invalid." }
}

class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val manifestUrl: String = BuildConfig.UPDATE_MANIFEST_URL
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val manifest = fetchManifest()
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    UpdateState.UpToDate
                } else {
                    validateUpdateManifest(BuildConfig.VERSION_CODE, manifest)
                    UpdateState.Available(manifest, isUpdateRequired(BuildConfig.VERSION_CODE, manifest))
                }
            } catch (error: Exception) {
                UpdateState.Error(error.message ?: "Could not check for updates.")
            }
        }
    }

    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            try {
                validateUpdateManifest(BuildConfig.VERSION_CODE, manifest)
                val target = updateFile(manifest)
                if (target.exists() && !target.delete()) error("Could not replace the previous update download.")
                val request = DownloadManager.Request(Uri.parse(manifest.apkUrl))
                    .setTitle("FW Driver ${manifest.versionName}")
                    .setDescription("Downloading app update")
                    .setMimeType(APK_MIME_TYPE)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, target.name)
                val id = downloadManager.enqueue(request)
                monitorDownload(id, manifest, target)
            } catch (error: Exception) {
                _state.value = UpdateState.Error(error.message ?: "Could not start the update download.", manifest)
            }
        }
    }

    fun openInstaller(manifest: UpdateManifest, apk: File): String? {
        return try {
            verifyApk(apk, manifest)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Allow FW Driver to install updates, then tap Install update again."
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setDataAndType(uri, APK_MIME_TYPE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                null
            }
        } catch (error: Exception) {
            error.message ?: "The downloaded update could not be verified."
        }
    }

    private suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        require(URI(manifestUrl).scheme.equals("https", ignoreCase = true)) { "The update manifest URL must use HTTPS." }
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) error("Update check failed (HTTP ${connection.responseCode}).")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<UpdateManifest>(body)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun monitorDownload(id: Long, manifest: UpdateManifest, target: File) {
        while (true) {
            val snapshot = queryDownload(id)
            when (snapshot.status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_RUNNING -> {
                    _state.value = UpdateState.Downloading(manifest, snapshot.progress)
                    delay(500)
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    try {
                        verifyApk(target, manifest)
                        _state.value = UpdateState.ReadyToInstall(manifest, target)
                    } catch (error: Exception) {
                        target.delete()
                        _state.value = UpdateState.Error(error.message ?: "The downloaded APK failed verification.", manifest)
                    }
                    return
                }
                else -> {
                    _state.value = UpdateState.Error("The update download failed (reason ${snapshot.reason}).", manifest)
                    return
                }
            }
        }
    }

    private fun queryDownload(id: Long): DownloadSnapshot {
        downloadManager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return DownloadSnapshot(DownloadManager.STATUS_FAILED, 0, null)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else null
            return DownloadSnapshot(status, reason, progress)
        }
    }

    private fun verifyApk(apk: File, manifest: UpdateManifest? = null) {
        require(apk.isFile && apk.length() > 0) { "The downloaded APK is missing." }
        if (manifest != null) {
            require(sha256(apk).equals(manifest.sha256, ignoreCase = true)) { "The downloaded APK checksum does not match the update manifest." }
        }
        val archive = packageInfo(apk.absolutePath) ?: error("Android could not read the downloaded APK.")
        require(archive.packageName == context.packageName) { "The downloaded APK belongs to a different app." }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (manifest != null) require(archiveVersionCode == manifest.versionCode.toLong()) { "The downloaded APK version does not match the update manifest." }
        val installed = packageInfo(context.packageName, installed = true) ?: error("Could not verify the installed app signature.")
        require(signingDigests(archive) == signingDigests(installed)) { "The downloaded APK is not signed with the FW Driver signing certificate." }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(value: String, installed: Boolean = false) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        if (installed) context.packageManager.getPackageInfo(value, flags) else context.packageManager.getPackageArchiveInfo(value, flags)
    } else {
        if (installed) context.packageManager.getPackageInfo(value, PackageManager.GET_SIGNATURES)
        else context.packageManager.getPackageArchiveInfo(value, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }
        require(signatures.isNotEmpty()) { "The APK signing certificate could not be read." }
        return signatures.map { bytesToHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun updateFile(manifest: UpdateManifest): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("Update storage is unavailable.")
        return File(directory, "fw-driver-${manifest.versionCode}.apk")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return bytesToHex(digest.digest())
    }

    private fun bytesToHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private data class DownloadSnapshot(val status: Int, val reason: Int, val progress: Int?)

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
