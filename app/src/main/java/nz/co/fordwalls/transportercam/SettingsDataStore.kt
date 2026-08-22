package nz.co.fordwalls.transportercam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsDataStore(private val context: Context) {

    companion object {
        val TIMESTAMP_ENABLED = booleanPreferencesKey("timestamp_enabled")
        val DATE_FORMAT = stringPreferencesKey("date_format")
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
        val DARK_MODE = stringPreferencesKey("dark_mode") // "auto", "on", "off"
        val IMAGE_QUALITY = intPreferencesKey("image_quality")
        val SHARE_SUMMARY_ENABLED = booleanPreferencesKey("share_summary_enabled")
        val CAPTURE_FEEDBACK_ENABLED = booleanPreferencesKey("capture_feedback_enabled")
        val FLEET_NUMBER = stringPreferencesKey("fleet_number")
        val SELECTED_COMPANY_ID = stringPreferencesKey("selected_company_id")
        val SELECTED_COMPANY_NAME = stringPreferencesKey("selected_company_name")
        val DRIVER_DOCUMENT_ID = stringPreferencesKey("driver_document_id")
        val SESSION_STARTED_AT = longPreferencesKey("session_started_at")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        
        const val DEFAULT_DATE_FORMAT = "dd/MM/yyyy HH:mm:ss"
    }

    val fleetNumber: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[FLEET_NUMBER]
    }

    val selectedCompanyId: Flow<String?> = context.dataStore.data.map { it[SELECTED_COMPANY_ID] }
    val selectedCompanyName: Flow<String?> = context.dataStore.data.map { it[SELECTED_COMPANY_NAME] }
    val driverDocumentId: Flow<String?> = context.dataStore.data.map { it[DRIVER_DOCUMENT_ID] }
    val sessionStartedAt: Flow<Long?> = context.dataStore.data.map { it[SESSION_STARTED_AT] }

    suspend fun migrateExistingSessionToFordWalls() {
        val preferences = context.dataStore.data.first()
        if (preferences[FLEET_NUMBER] != null && preferences[SELECTED_COMPANY_ID] == null) {
            context.dataStore.edit {
                it[SELECTED_COMPANY_ID] = TenantFirestorePaths.FORDWALLS_ID
                it[SELECTED_COMPANY_NAME] = "FordWalls"
                it.remove(FLEET_NUMBER)
                it.remove(SESSION_STARTED_AT)
            }
        }
    }

    suspend fun selectCompany(company: CompanySummary) {
        context.dataStore.edit {
            it[SELECTED_COMPANY_ID] = company.id
            it[SELECTED_COMPANY_NAME] = company.name
            it.remove(DRIVER_DOCUMENT_ID)
            it.remove(FLEET_NUMBER)
            it.remove(SESSION_STARTED_AT)
        }
    }

    suspend fun commitDriverSession(driverId: String, fleet: String, startedAt: Long = System.currentTimeMillis()) {
        context.dataStore.edit {
            it[DRIVER_DOCUMENT_ID] = driverId
            it[FLEET_NUMBER] = fleet
            it[SESSION_STARTED_AT] = startedAt
        }
    }

    suspend fun clearDriverSession() {
        context.dataStore.edit {
            it.remove(DRIVER_DOCUMENT_ID)
            it.remove(FLEET_NUMBER)
            it.remove(SESSION_STARTED_AT)
        }
    }

    suspend fun clearCompanyAndDriverSession() {
        context.dataStore.edit {
            it.remove(SELECTED_COMPANY_ID)
            it.remove(SELECTED_COMPANY_NAME)
            it.remove(DRIVER_DOCUMENT_ID)
            it.remove(FLEET_NUMBER)
            it.remove(SESSION_STARTED_AT)
        }
    }

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NOTIFICATIONS_ENABLED] ?: true
    }

    val timestampEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TIMESTAMP_ENABLED] ?: true
    }

    val dateFormat: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DATE_FORMAT] ?: DEFAULT_DATE_FORMAT
    }

    val gpsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[GPS_ENABLED] ?: false
    }

    val darkMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[DARK_MODE] ?: "auto"
    }

    val imageQuality: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[IMAGE_QUALITY] ?: 95
    }

    val shareSummaryEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SHARE_SUMMARY_ENABLED] ?: true
    }

    val captureFeedbackEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[CAPTURE_FEEDBACK_ENABLED] ?: true
    }

    suspend fun setTimestampEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TIMESTAMP_ENABLED] = enabled
        }
    }

    suspend fun setDateFormat(format: String) {
        context.dataStore.edit { preferences ->
            preferences[DATE_FORMAT] = format
        }
    }

    suspend fun setGpsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[GPS_ENABLED] = enabled
        }
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[DARK_MODE] = mode
        }
    }

    suspend fun setImageQuality(quality: Int) {
        context.dataStore.edit { preferences ->
            preferences[IMAGE_QUALITY] = quality
        }
    }

    suspend fun setShareSummaryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHARE_SUMMARY_ENABLED] = enabled
        }
    }

    suspend fun setCaptureFeedbackEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CAPTURE_FEEDBACK_ENABLED] = enabled
        }
    }

    suspend fun setFleetNumber(number: String?) {
        context.dataStore.edit { preferences ->
            if (number == null) {
                preferences.remove(FLEET_NUMBER)
            } else {
                preferences[FLEET_NUMBER] = number
            }
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATIONS_ENABLED] = enabled
        }
    }
}
