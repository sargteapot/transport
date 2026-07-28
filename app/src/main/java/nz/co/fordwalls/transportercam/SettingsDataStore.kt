package nz.co.fordwalls.transportercam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        
        const val DEFAULT_DATE_FORMAT = "dd/MM/yyyy HH:mm:ss"
    }

    val fleetNumber: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[FLEET_NUMBER]
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
}
