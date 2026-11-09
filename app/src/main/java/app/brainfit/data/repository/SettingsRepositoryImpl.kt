package app.brainfit.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brainfit.domain.model.AppSettings
import app.brainfit.domain.model.ThemeMode
import app.brainfit.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ошибки чтения и записи DataStore не глотаются: они уходят вызывающему,
 * а тот показывает их пользователю (правило 8 ТЗ).
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SOUND = booleanPreferencesKey("sound")
        val VIBRATION = booleanPreferencesKey("vibration")
        val SHAKE_DETECTOR = booleanPreferencesKey("shake_detector")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = p[Keys.THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.themeMode,
            soundEnabled = p[Keys.SOUND] ?: defaults.soundEnabled,
            vibrationEnabled = p[Keys.VIBRATION] ?: defaults.vibrationEnabled,
            shakeDetectorEnabled = p[Keys.SHAKE_DETECTOR] ?: defaults.shakeDetectorEnabled,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setSoundEnabled(enabled: Boolean) {
        store.edit { it[Keys.SOUND] = enabled }
    }

    override suspend fun setVibrationEnabled(enabled: Boolean) {
        store.edit { it[Keys.VIBRATION] = enabled }
    }

    override suspend fun setShakeDetectorEnabled(enabled: Boolean) {
        store.edit { it[Keys.SHAKE_DETECTOR] = enabled }
    }
}
