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

fun initialDisplayValueFor(selectedSeconds: Int): Int = (selectedSeconds - 1).coerceAtLeast(0)

data class WorkoutTimerUiState(
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val remainingSeconds: Int = initialDisplayValueFor(DEFAULT_SECONDS),
    val elapsedTimeText: String = "00:00",
    val isRunning: Boolean = false,
    val loopEnabled: Boolean = false,
    val tickVibrationEnabled: Boolean = false,
    val loopVibrationEnabled: Boolean = true,
)

enum class VibrationEvent {
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

    private var timerJob: Job? = null
    private var accumulatedElapsedMs: Long = 0L
    private var runStartedAtMs: Long? = null
    private var processedElapsedSeconds: Long = 0L
    private var displayedRemainingSeconds: Int = initialDisplayValueFor(DEFAULT_SECONDS)

    init {
        scope.launch {
            restoreSettings()
        }
    }

    fun setSelectedSeconds(seconds: Int) {
        if (_uiState.value.isRunning) return
        val clampedSeconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        accumulatedElapsedMs = 0L
        processedElapsedSeconds = 0L
        displayedRemainingSeconds = initialDisplayValueFor(clampedSeconds)
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

    fun start() {
        val state = _uiState.value
        if (state.isRunning) return
        val cycleMs = state.selectedSeconds * 1_000L
        if (accumulatedElapsedMs >= cycleMs && state.remainingSeconds == 0) {
            if (state.loopEnabled) {
                displayedRemainingSeconds = initialDisplayValueFor(state.selectedSeconds)
                _uiState.update { it.copy(remainingSeconds = displayedRemainingSeconds) }
            } else {
                accumulatedElapsedMs = 0L
                processedElapsedSeconds = 0L
                displayedRemainingSeconds = initialDisplayValueFor(state.selectedSeconds)
                _uiState.update {
                    it.copy(
                        remainingSeconds = displayedRemainingSeconds,
                        elapsedTimeText = formatElapsedTime(0L),
                    )
                }
            }
        }
        if (displayedRemainingSeconds == 0 && accumulatedElapsedMs % cycleMs == 0L) {
            emitCountSwitchVibration(_uiState.value, displayedRemainingSeconds)
        }
        runStartedAtMs = SystemClock.elapsedRealtime()
        processedElapsedSeconds = accumulatedElapsedMs / 1_000L
        _uiState.update { it.copy(isRunning = true) }
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
        accumulatedElapsedMs = currentTotalElapsedMs()
        runStartedAtMs = null
        timerJob?.cancel()
        timerJob = null
        syncUiState(accumulatedElapsedMs, isRunning = false, remainingSeconds = displayedRemainingSeconds)
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        accumulatedElapsedMs = 0L
        runStartedAtMs = null
        processedElapsedSeconds = 0L
        displayedRemainingSeconds = initialDisplayValueFor(_uiState.value.selectedSeconds)
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
        val cycleLength = state.selectedSeconds.toLong()
        val cycleMs = cycleLength * 1_000L
        val totalElapsedMs = currentTotalElapsedMs()
        val currentWholeSeconds = totalElapsedMs / 1_000L

        if (currentWholeSeconds > processedElapsedSeconds) {
            for (second in (processedElapsedSeconds + 1)..currentWholeSeconds) {
                val stepInCycle = (second % cycleLength).toInt()

                if (stepInCycle == 0) {
                    if (!state.loopEnabled) {
                        displayedRemainingSeconds = 0
                        accumulatedElapsedMs = cycleMs
                        runStartedAtMs = null
                        processedElapsedSeconds = cycleLength
                        timerJob?.cancel()
                        timerJob = null
                        syncUiState(cycleMs, isRunning = false, remainingSeconds = displayedRemainingSeconds)
                        return
                    }

                    displayedRemainingSeconds = initialDisplayValueFor(state.selectedSeconds)
                    emitCountSwitchVibration(state, displayedRemainingSeconds)
                } else {
                    displayedRemainingSeconds = state.selectedSeconds - 1 - stepInCycle
                    emitCountSwitchVibration(state, displayedRemainingSeconds)
                }
            }
            processedElapsedSeconds = currentWholeSeconds
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
        displayedRemainingSeconds = initialDisplayValueFor(settings.selectedSeconds)
        _uiState.update { state ->
            state.copy(
                selectedSeconds = settings.selectedSeconds,
                remainingSeconds = displayedRemainingSeconds,
                loopEnabled = settings.loopEnabled,
                tickVibrationEnabled = settings.tickVibrationEnabled,
                loopVibrationEnabled = settings.loopVibrationEnabled,
            )
        }
    }

    private fun emitCountSwitchVibration(state: WorkoutTimerUiState, displayedValue: Int) {
        if (displayedValue == 0 && state.loopVibrationEnabled) {
            _vibrationEvents.tryEmit(VibrationEvent.LoopComplete)
            return
        }
        if (state.tickVibrationEnabled) {
            _vibrationEvents.tryEmit(VibrationEvent.Tick)
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

    override fun onCleared() {
        timerJob?.cancel()
        scope.cancel()
    }
}
