package com.goenc.androiddailymotiontimer

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

const val DEFAULT_SECONDS = 5
const val MIN_SECONDS = 1
const val MAX_SECONDS = 10
const val MIN_CUE_VOLUME = 0
const val MAX_CUE_VOLUME = 100
const val DEFAULT_EARLY_TICK_VOLUME = 45
const val DEFAULT_TICK_VOLUME = 80
const val DEFAULT_LOOP_COMPLETE_VOLUME = 80

data class WorkoutTimerUiState(
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val remainingSeconds: Int = DEFAULT_SECONDS,
    val elapsedTimeText: String = "00:00",
    val isRunning: Boolean = false,
    val loopEnabled: Boolean = false,
    val tickVibrationEnabled: Boolean = false,
    val loopVibrationEnabled: Boolean = true,
    val countdownSoundEnabled: Boolean = true,
    val earlyTickVolume: Int = DEFAULT_EARLY_TICK_VOLUME,
    val tickVolume: Int = DEFAULT_TICK_VOLUME,
    val loopCompleteVolume: Int = DEFAULT_LOOP_COMPLETE_VOLUME,
)

enum class VibrationEvent {
    Tick,
    LoopComplete,
}

enum class CountdownSoundEvent {
    EarlyTick,
    Tick,
    LoopComplete,
}

class WorkoutSecondTimerViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore = WorkoutSettingsStore(application.applicationContext)
    private val _uiState = MutableStateFlow(WorkoutTimerUiState())
    val uiState: StateFlow<WorkoutTimerUiState> = _uiState.asStateFlow()

    private val _vibrationEvents = MutableSharedFlow<VibrationEvent>(extraBufferCapacity = 8)
    val vibrationEvents: SharedFlow<VibrationEvent> = _vibrationEvents.asSharedFlow()
    private val _countdownSoundEvents = MutableSharedFlow<CountdownSoundEvent>(extraBufferCapacity = 8)
    val countdownSoundEvents: SharedFlow<CountdownSoundEvent> = _countdownSoundEvents.asSharedFlow()

    private var timerJob: Job? = null
    private var accumulatedElapsedMs: Long = 0L
    private var runStartedAtMs: Long? = null
    private var currentLoopStartedAtElapsedMs: Long = 0L
    private var nextBoundaryIndex: Int = 1
    private var displayedRemainingSeconds: Int = DEFAULT_SECONDS
    private var hasPlayedInitialDisplayCue: Boolean = false

    init {
        scope.launch {
            restoreSettings()
        }
    }

    fun setSelectedSeconds(seconds: Int) {
        if (_uiState.value.isRunning) return
        val clampedSeconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        resetTimerProgress(clampedSeconds)
        _uiState.update {
            it.copy(
                selectedSeconds = clampedSeconds,
                remainingSeconds = displayedRemainingSeconds,
                elapsedTimeText = formatElapsedTime(0L),
            )
        }
        persistCurrentSettings()
    }

    fun setLoopEnabled(enabled: Boolean) {
        _uiState.update { it.copy(loopEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setTickVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(tickVibrationEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setLoopVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(loopVibrationEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setCountdownSoundEnabled(enabled: Boolean) {
        _uiState.update { it.copy(countdownSoundEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setEarlyTickVolume(value: Int) {
        _uiState.update {
            it.copy(earlyTickVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME))
        }
        persistCurrentSettings()
    }

    fun setTickVolume(value: Int) {
        _uiState.update {
            it.copy(tickVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME))
        }
        persistCurrentSettings()
    }

    fun setLoopCompleteVolume(value: Int) {
        _uiState.update {
            it.copy(loopCompleteVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME))
        }
        persistCurrentSettings()
    }

    fun start() {
        val state = _uiState.value
        if (state.isRunning) return
        val engine = WorkoutTimerEngine(state.selectedSeconds)
        if (!state.loopEnabled && isSingleRunCompleted(engine)) {
            resetTimerProgress(state.selectedSeconds)
            _uiState.update {
                it.copy(
                    remainingSeconds = displayedRemainingSeconds,
                    elapsedTimeText = formatElapsedTime(0L),
                )
            }
        }
        runStartedAtMs = SystemClock.elapsedRealtime()
        _uiState.update { it.copy(isRunning = true, remainingSeconds = displayedRemainingSeconds) }
        emitInitialDisplayCueIfNeeded(state, displayedRemainingSeconds)
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                updateTimerState()
                delay(20L)
            }
        }
    }

    fun pause() {
        if (!_uiState.value.isRunning) return
        val engine = WorkoutTimerEngine(_uiState.value.selectedSeconds)
        accumulatedElapsedMs = currentTotalElapsedMs()
        if (!_uiState.value.loopEnabled && isSingleRunCompleted(engine)) {
            accumulatedElapsedMs = currentLoopStartedAtElapsedMs + engine.totalDurationMs
            displayedRemainingSeconds = 0
            nextBoundaryIndex = engine.boundaryCount + 1
        }
        runStartedAtMs = null
        timerJob?.cancel()
        timerJob = null
        syncUiState(accumulatedElapsedMs, isRunning = false, remainingSeconds = displayedRemainingSeconds)
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        runStartedAtMs = null
        resetTimerProgress(_uiState.value.selectedSeconds)
        _uiState.update {
            it.copy(
                remainingSeconds = displayedRemainingSeconds,
                elapsedTimeText = formatElapsedTime(0L),
                isRunning = false,
            )
        }
    }

    private suspend fun updateTimerState() {
        val state = _uiState.value
        val engine = WorkoutTimerEngine(state.selectedSeconds)
        val totalElapsedMs = currentTotalElapsedMs()
        while (true) {
            val nextBoundaryElapsedMs = nextBoundaryElapsedMs(engine)
            val loopFinished = totalElapsedMs >= currentLoopStartedAtElapsedMs + engine.totalDurationMs
            when {
                nextBoundaryElapsedMs != null && totalElapsedMs >= nextBoundaryElapsedMs -> {
                    displayedRemainingSeconds = engine.displayValueForBoundary(nextBoundaryIndex)
                    emitCountSwitchEffects(state, displayedRemainingSeconds)
                    nextBoundaryIndex += 1
                }

                loopFinished && state.loopEnabled -> {
                    currentLoopStartedAtElapsedMs += engine.totalDurationMs
                    displayedRemainingSeconds = engine.initialDisplayValue()
                    nextBoundaryIndex = 1
                    hasPlayedInitialDisplayCue = false
                    emitInitialDisplayCueIfNeeded(state, displayedRemainingSeconds)
                }

                loopFinished -> {
                    accumulatedElapsedMs = currentLoopStartedAtElapsedMs + engine.totalDurationMs
                    runStartedAtMs = null
                    timerJob?.cancel()
                    timerJob = null
                    displayedRemainingSeconds = 0
                    nextBoundaryIndex = engine.boundaryCount + 1
                    syncUiState(accumulatedElapsedMs, isRunning = false, remainingSeconds = displayedRemainingSeconds)
                    return
                }

                else -> break
            }
        }

        syncUiState(totalElapsedMs, isRunning = true, remainingSeconds = displayedRemainingSeconds)
    }

    private fun syncUiState(
        totalElapsedMs: Long,
        isRunning: Boolean,
        remainingSeconds: Int,
    ) {
        _uiState.update { state ->
            state.copy(
                remainingSeconds = remainingSeconds,
                elapsedTimeText = formatElapsedTime(totalElapsedMs),
                isRunning = isRunning,
            )
        }
    }

    private fun currentTotalElapsedMs(): Long {
        val startedAtMs = runStartedAtMs ?: return accumulatedElapsedMs
        return accumulatedElapsedMs + max(0L, SystemClock.elapsedRealtime() - startedAtMs)
    }

    private suspend fun restoreSettings() {
        val settings = settingsStore.settings.first()
        resetTimerProgress(settings.selectedSeconds)
        _uiState.update { state ->
            state.copy(
                selectedSeconds = settings.selectedSeconds,
                remainingSeconds = displayedRemainingSeconds,
                loopEnabled = settings.loopEnabled,
                tickVibrationEnabled = settings.tickVibrationEnabled,
                loopVibrationEnabled = settings.loopVibrationEnabled,
                countdownSoundEnabled = settings.countdownSoundEnabled,
                earlyTickVolume = settings.earlyTickVolume,
                tickVolume = settings.tickVolume,
                loopCompleteVolume = settings.loopCompleteVolume,
            )
        }
    }

    private fun emitCountSwitchEffects(state: WorkoutTimerUiState, displayedValue: Int) {
        if (displayedValue == 0 && state.loopVibrationEnabled) {
            _vibrationEvents.tryEmit(VibrationEvent.LoopComplete)
        } else if (state.tickVibrationEnabled) {
            _vibrationEvents.tryEmit(VibrationEvent.Tick)
        }

        emitCountdownSound(displayedValue, state.countdownSoundEnabled)
    }

    private fun emitInitialDisplayCueIfNeeded(state: WorkoutTimerUiState, displayedValue: Int) {
        if (hasPlayedInitialDisplayCue || displayedValue == 0) return
        emitCountdownSound(displayedValue, state.countdownSoundEnabled)
        hasPlayedInitialDisplayCue = true
    }

    private fun emitCountdownSound(displayedValue: Int, countdownSoundEnabled: Boolean) {
        if (!countdownSoundEnabled) return
        if (displayedValue == 0) {
            _countdownSoundEvents.tryEmit(CountdownSoundEvent.LoopComplete)
        } else if (displayedValue >= 4) {
            _countdownSoundEvents.tryEmit(CountdownSoundEvent.EarlyTick)
        } else if (displayedValue in 1..3) {
            _countdownSoundEvents.tryEmit(CountdownSoundEvent.Tick)
        }
    }

    private fun persistCurrentSettings() {
        val state = _uiState.value
        scope.launch {
            settingsStore.save(
                WorkoutTimerSettings(
                    selectedSeconds = state.selectedSeconds,
                    loopEnabled = state.loopEnabled,
                    tickVibrationEnabled = state.tickVibrationEnabled,
                    loopVibrationEnabled = state.loopVibrationEnabled,
                    countdownSoundEnabled = state.countdownSoundEnabled,
                    earlyTickVolume = state.earlyTickVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                    tickVolume = state.tickVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                    loopCompleteVolume = state.loopCompleteVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                )
            )
        }
    }

    private fun formatElapsedTime(totalElapsedMs: Long): String {
        val totalSeconds = totalElapsedMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun resetTimerProgress(selectedSeconds: Int) {
        accumulatedElapsedMs = 0L
        currentLoopStartedAtElapsedMs = 0L
        nextBoundaryIndex = 1
        displayedRemainingSeconds = WorkoutTimerEngine(selectedSeconds).initialDisplayValue()
        hasPlayedInitialDisplayCue = false
    }

    private fun nextBoundaryElapsedMs(engine: WorkoutTimerEngine): Long? {
        if (nextBoundaryIndex > engine.boundaryCount) return null
        return currentLoopStartedAtElapsedMs + engine.boundaryElapsedMs(nextBoundaryIndex)
    }

    private fun isSingleRunCompleted(engine: WorkoutTimerEngine): Boolean {
        return accumulatedElapsedMs >= currentLoopStartedAtElapsedMs + engine.totalDurationMs &&
            displayedRemainingSeconds == 0
    }

    override fun onCleared() {
        timerJob?.cancel()
        scope.cancel()
    }
}
