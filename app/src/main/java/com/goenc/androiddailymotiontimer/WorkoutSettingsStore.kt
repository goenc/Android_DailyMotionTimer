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
    val earlyTickVolume: Int = DEFAULT_EARLY_TICK_VOLUME,
    val tickVolume: Int = DEFAULT_TICK_VOLUME,
    val loopCompleteVolume: Int = DEFAULT_LOOP_COMPLETE_VOLUME,
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
            earlyTickVolume = (preferences[EARLY_TICK_VOLUME_KEY] ?: DEFAULT_EARLY_TICK_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            tickVolume = (preferences[TICK_VOLUME_KEY] ?: DEFAULT_TICK_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            loopCompleteVolume = (preferences[LOOP_COMPLETE_VOLUME_KEY] ?: DEFAULT_LOOP_COMPLETE_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
        )
    }

    suspend fun save(settings: WorkoutTimerSettings) {
        dataStore.edit { preferences ->
            preferences[SELECTED_SECONDS_KEY] = settings.selectedSeconds
            preferences[LOOP_ENABLED_KEY] = settings.loopEnabled
            preferences[TICK_VIBRATION_ENABLED_KEY] = settings.tickVibrationEnabled
            preferences[LOOP_VIBRATION_ENABLED_KEY] = settings.loopVibrationEnabled
            preferences[COUNTDOWN_SOUND_ENABLED_KEY] = settings.countdownSoundEnabled
            preferences[EARLY_TICK_VOLUME_KEY] = settings.earlyTickVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            preferences[TICK_VOLUME_KEY] = settings.tickVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            preferences[LOOP_COMPLETE_VOLUME_KEY] =
                settings.loopCompleteVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        }
    }

    private companion object {
        private const val DATA_STORE_NAME = "workout_timer_settings"
        private const val MIN_VOLUME = 0
        private const val MAX_VOLUME = 100

        private val SELECTED_SECONDS_KEY = intPreferencesKey("selected_seconds")
        private val LOOP_ENABLED_KEY = booleanPreferencesKey("loop_enabled")
        private val TICK_VIBRATION_ENABLED_KEY = booleanPreferencesKey("tick_vibration_enabled")
        private val LOOP_VIBRATION_ENABLED_KEY = booleanPreferencesKey("loop_vibration_enabled")
        private val COUNTDOWN_SOUND_ENABLED_KEY = booleanPreferencesKey("countdown_sound_enabled")
        private val EARLY_TICK_VOLUME_KEY = intPreferencesKey("early_tick_volume")
        private val TICK_VOLUME_KEY = intPreferencesKey("tick_volume")
        private val LOOP_COMPLETE_VOLUME_KEY = intPreferencesKey("loop_complete_volume")
    }
}
