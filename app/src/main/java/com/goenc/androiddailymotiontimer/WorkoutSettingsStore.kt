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
    val timerMode: TimerMode = TimerMode.Motion,
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val loopEnabled: Boolean = false,
    val maxLoopCount: Int = DEFAULT_MAX_LOOP_COUNT,
    val normalCountMaxCount: Int = DEFAULT_MAX_LOOP_COUNT,
    val normalCountInterval: NormalCountInterval = NormalCountInterval.Medium,
    val tickVibrationEnabled: Boolean = false,
    val loopVibrationEnabled: Boolean = true,
    val countdownSoundEnabled: Boolean = true,
    val countSoundMode: CountSoundMode = CountSoundMode.Beep,
    val earlyTickVolume: Int = DEFAULT_EARLY_TICK_VOLUME,
    val tickVolume: Int = DEFAULT_TICK_VOLUME,
    val loopCompleteVolume: Int = DEFAULT_LOOP_COMPLETE_VOLUME,
    val normalVibrationLevel: Int = DEFAULT_NORMAL_VIBRATION_LEVEL,
    val completeVibrationLevel: Int = DEFAULT_COMPLETE_VIBRATION_LEVEL,
)

class WorkoutSettingsStore(context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(DATA_STORE_NAME) },
    )

    val settings: Flow<WorkoutTimerSettings> = dataStore.data.map { preferences ->
        WorkoutTimerSettings(
            timerMode = TimerMode.entries.getOrElse(
                preferences[TIMER_MODE_KEY] ?: TimerMode.Motion.ordinal
            ) { TimerMode.Motion },
            selectedSeconds = (preferences[SELECTED_SECONDS_KEY] ?: DEFAULT_SECONDS)
                .coerceIn(MIN_SECONDS, MAX_SECONDS),
            loopEnabled = preferences[LOOP_ENABLED_KEY] ?: false,
            maxLoopCount = normalizeLoopCount(
                preferences[MAX_LOOP_COUNT_KEY] ?: DEFAULT_MAX_LOOP_COUNT,
            ),
            normalCountMaxCount = normalizeLoopCount(
                preferences[NORMAL_COUNT_MAX_COUNT_KEY] ?: DEFAULT_MAX_LOOP_COUNT,
            ),
            normalCountInterval = NormalCountInterval.entries.getOrElse(
                preferences[NORMAL_COUNT_INTERVAL_KEY] ?: NormalCountInterval.Medium.ordinal
            ) { NormalCountInterval.Medium },
            tickVibrationEnabled = preferences[TICK_VIBRATION_ENABLED_KEY] ?: false,
            loopVibrationEnabled = preferences[LOOP_VIBRATION_ENABLED_KEY] ?: true,
            countdownSoundEnabled = preferences[COUNTDOWN_SOUND_ENABLED_KEY] ?: true,
            countSoundMode = CountSoundMode.entries.getOrElse(
                preferences[COUNT_SOUND_MODE_KEY] ?: CountSoundMode.Beep.ordinal
            ) { CountSoundMode.Beep },
            earlyTickVolume = (preferences[EARLY_TICK_VOLUME_KEY] ?: DEFAULT_EARLY_TICK_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            tickVolume = (preferences[TICK_VOLUME_KEY] ?: DEFAULT_TICK_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            loopCompleteVolume = (preferences[LOOP_COMPLETE_VOLUME_KEY] ?: DEFAULT_LOOP_COMPLETE_VOLUME)
                .coerceIn(MIN_VOLUME, MAX_VOLUME),
            normalVibrationLevel = (
                preferences[NORMAL_VIBRATION_LEVEL_KEY] ?: DEFAULT_NORMAL_VIBRATION_LEVEL
                ).coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL),
            completeVibrationLevel = (
                preferences[COMPLETE_VIBRATION_LEVEL_KEY] ?: DEFAULT_COMPLETE_VIBRATION_LEVEL
                ).coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL),
        )
    }

    suspend fun save(settings: WorkoutTimerSettings) {
        dataStore.edit { preferences ->
            preferences[TIMER_MODE_KEY] = settings.timerMode.ordinal
            preferences[SELECTED_SECONDS_KEY] = settings.selectedSeconds
            preferences[LOOP_ENABLED_KEY] = settings.loopEnabled
            preferences[MAX_LOOP_COUNT_KEY] = normalizeLoopCount(settings.maxLoopCount)
            preferences[NORMAL_COUNT_MAX_COUNT_KEY] = normalizeLoopCount(settings.normalCountMaxCount)
            preferences[NORMAL_COUNT_INTERVAL_KEY] = settings.normalCountInterval.ordinal
            preferences[TICK_VIBRATION_ENABLED_KEY] = settings.tickVibrationEnabled
            preferences[LOOP_VIBRATION_ENABLED_KEY] = settings.loopVibrationEnabled
            preferences[COUNTDOWN_SOUND_ENABLED_KEY] = settings.countdownSoundEnabled
            preferences[COUNT_SOUND_MODE_KEY] = settings.countSoundMode.ordinal
            preferences[EARLY_TICK_VOLUME_KEY] = settings.earlyTickVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            preferences[TICK_VOLUME_KEY] = settings.tickVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            preferences[LOOP_COMPLETE_VOLUME_KEY] =
                settings.loopCompleteVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
            preferences[NORMAL_VIBRATION_LEVEL_KEY] =
                settings.normalVibrationLevel.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL)
            preferences[COMPLETE_VIBRATION_LEVEL_KEY] =
                settings.completeVibrationLevel.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL)
        }
    }

    private companion object {
        private const val DATA_STORE_NAME = "workout_timer_settings"
        private const val MIN_VOLUME = 0
        private const val MAX_VOLUME = 100

        private val TIMER_MODE_KEY = intPreferencesKey("timer_mode")
        private val SELECTED_SECONDS_KEY = intPreferencesKey("selected_seconds")
        private val LOOP_ENABLED_KEY = booleanPreferencesKey("loop_enabled")
        private val MAX_LOOP_COUNT_KEY = intPreferencesKey("max_loop_count")
        private val NORMAL_COUNT_MAX_COUNT_KEY = intPreferencesKey("normal_count_max_count")
        private val NORMAL_COUNT_INTERVAL_KEY = intPreferencesKey("normal_count_interval")
        private val TICK_VIBRATION_ENABLED_KEY = booleanPreferencesKey("tick_vibration_enabled")
        private val LOOP_VIBRATION_ENABLED_KEY = booleanPreferencesKey("loop_vibration_enabled")
        private val COUNTDOWN_SOUND_ENABLED_KEY = booleanPreferencesKey("countdown_sound_enabled")
        private val COUNT_SOUND_MODE_KEY = intPreferencesKey("count_sound_mode")
        private val EARLY_TICK_VOLUME_KEY = intPreferencesKey("early_tick_volume")
        private val TICK_VOLUME_KEY = intPreferencesKey("tick_volume")
        private val LOOP_COMPLETE_VOLUME_KEY = intPreferencesKey("loop_complete_volume")
        private val NORMAL_VIBRATION_LEVEL_KEY = intPreferencesKey("normal_vibration_level")
        private val COMPLETE_VIBRATION_LEVEL_KEY = intPreferencesKey("complete_vibration_level")

        private fun normalizeLoopCount(value: Int): Int {
            val clampedValue = value.coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
            val snappedValue = ((clampedValue - MIN_LOOP_COUNT) / 5) * 5 + MIN_LOOP_COUNT
            return snappedValue.coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
        }
    }
}
