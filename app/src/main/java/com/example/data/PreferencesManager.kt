package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "docuscan_settings")

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

enum class PdfPageSize(val displayName: String, val subtitle: String) {
    LETTER("Carta", "8.5 × 11 in (216 × 279 mm)"),
    OFICIO("Oficio", "8.5 × 13 in (216 × 330 mm)"),
    LEGAL("Legal", "8.5 × 14 in (216 × 356 mm)"),
    FIT_PAGE("Ajustar a foto", "Sin márgenes blancos"),
    A4("A4", "210 × 297 mm")
}

enum class PdfQuality(val compressionQuality: Int) {
    HIGH(95), MEDIUM(80), LOW(60)
}

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultPageSize: PdfPageSize = PdfPageSize.LETTER,
    val defaultQuality: PdfQuality = PdfQuality.HIGH,
    val autoCaptureEnabled: Boolean = false,
    val fileNamePrefix: String = "DocuScan_"
)

class PreferencesManager(private val context: Context) {

    private object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val PAGE_SIZE = stringPreferencesKey("page_size")
        val QUALITY = stringPreferencesKey("quality")
        val AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val FILE_PREFIX = stringPreferencesKey("file_prefix")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { preferences ->
        val themeModeStr = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        val pageSizeStr = preferences[PreferencesKeys.PAGE_SIZE] ?: PdfPageSize.LETTER.name
        val qualityStr = preferences[PreferencesKeys.QUALITY] ?: PdfQuality.HIGH.name
        val autoCapture = preferences[PreferencesKeys.AUTO_CAPTURE] ?: false
        val prefix = preferences[PreferencesKeys.FILE_PREFIX] ?: "DocuScan_"

        UserPreferences(
            themeMode = try { ThemeMode.valueOf(themeModeStr) } catch (e: Exception) { ThemeMode.SYSTEM },
            defaultPageSize = try { PdfPageSize.valueOf(pageSizeStr) } catch (e: Exception) { PdfPageSize.LETTER },
            defaultQuality = try { PdfQuality.valueOf(qualityStr) } catch (e: Exception) { PdfQuality.HIGH },
            autoCaptureEnabled = autoCapture,
            fileNamePrefix = prefix
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setPageSize(size: PdfPageSize) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.PAGE_SIZE] = size.name
        }
    }

    suspend fun setQuality(quality: PdfQuality) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.QUALITY] = quality.name
        }
    }

    suspend fun setAutoCaptureEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_CAPTURE] = enabled
        }
    }

    suspend fun setFileNamePrefix(prefix: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FILE_PREFIX] = prefix
        }
    }
}
