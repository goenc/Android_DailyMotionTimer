package com.goenc.androiddailymotiontimer

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class WorkoutTimerSettings(
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val loopEnabled: Boolean = false,
    val tickVibrationEnabled: Boolean = false,
    val loopVibrationEnabled: Boolean = true,
    val countdownSoundEnabled: Boolean = true,
)

class WorkoutSettingsStore(context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(DATA_STORE_NAME) },
    )

    val settings: Flow<WorkoutTimerSettings> = dataStore.data.map { preferences ->
        WorkoutTimerSettings(
            selectedSeconds = (preferences[SELECTED_SECONDS_KEY] ?: DEFAULT_SECONDS)
                .coerceIn(MIN_SECONDS, MAX_SECONDS),
            loopEnabled = preferences[LOOP_ENABLED_KEY] ?: false,
            tickVibrationEnabled = preferences[TICK_VIBRATION_ENABLED_KEY] ?: false,
            loopVibrationEnabled = preferences[LOOP_VIBRATION_ENABLED_KEY] ?: true,
            countdownSoundEnabled = preferences[COUNTDOWN_SOUND_ENABLED_KEY] ?: true,
        )
    }

    suspend fun save(settings: WorkoutTimerSettings) {
        dataStore.edit { preferences ->
            preferences[SELECTED_SECONDS_KEY] = settings.selectedSeconds
            preferences[LOOP_ENABLED_KEY] = settings.loopEnabled
            preferences[TICK_VIBRATION_ENABLED_KEY] = settings.tickVibrationEnabled
            preferences[LOOP_VIBRATION_ENABLED_KEY] = settings.loopVibrationEnabled
            preferences[COUNTDOWN_SOUND_ENABLED_KEY] = settings.countdownSoundEnabled
        }
    }

    private companion object {
        private const val DATA_STORE_NAME = "workout_timer_settings"

        private val SELECTED_SECONDS_KEY = intPreferencesKey("selected_seconds")
        private val LOOP_ENABLED_KEY = booleanPreferencesKey("loop_enabled")
        private val TICK_VIBRATION_ENABLED_KEY = booleanPreferencesKey("tick_vibration_enabled")
        private val LOOP_VIBRATION_ENABLED_KEY = booleanPreferencesKey("loop_vibration_enabled")
        private val COUNTDOWN_SOUND_ENABLED_KEY = booleanPreferencesKey("countdown_sound_enabled")
    }
}
