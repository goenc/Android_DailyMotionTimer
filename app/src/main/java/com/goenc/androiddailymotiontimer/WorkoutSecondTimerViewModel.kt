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

data class WorkoutTimerUiState(
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val remainingSeconds: Int = DEFAULT_SECONDS,
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
    private var displayedRemainingSeconds: Int = DEFAULT_SECONDS
    private var pendingLoopRestart: Boolean = false

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
        pendingLoopRestart = false
        displayedRemainingSeconds = clampedSeconds
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
                pendingLoopRestart = false
                displayedRemainingSeconds = state.selectedSeconds
                _uiState.update { it.copy(remainingSeconds = displayedRemainingSeconds) }
            } else {
                accumulatedElapsedMs = 0L
                processedElapsedSeconds = 0L
                pendingLoopRestart = false
                displayedRemainingSeconds = state.selectedSeconds
                _uiState.update {
                    it.copy(
                        remainingSeconds = displayedRemainingSeconds,
                        elapsedTimeText = formatElapsedTime(0L),
                    )
                }
            }
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
        promoteNextLoopCycleIfNeeded(accumulatedElapsedMs, _uiState.value.selectedSeconds)
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
        pendingLoopRestart = false
        displayedRemainingSeconds = _uiState.value.selectedSeconds
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
        val cycleMs = state.selectedSeconds * 1_000L
        val totalElapsedMs = currentTotalElapsedMs()
        val currentWholeSeconds = totalElapsedMs / 1_000L

        if (currentWholeSeconds > processedElapsedSeconds) {
            for (second in (processedElapsedSeconds + 1)..currentWholeSeconds) {
                val secondInCycle = ((second - 1L) % state.selectedSeconds.toLong()) + 1L
                val isCycleComplete = secondInCycle == state.selectedSeconds.toLong()

                if (isCycleComplete) {
                    displayedRemainingSeconds = 0
                    if (state.loopVibrationEnabled) {
                        _vibrationEvents.tryEmit(VibrationEvent.LoopComplete)
                    }
                    if (!state.loopEnabled) {
                        accumulatedElapsedMs = cycleMs
                        runStartedAtMs = null
                        processedElapsedSeconds = cycleMs / 1_000L
                        timerJob?.cancel()
                        timerJob = null
                        syncUiState(cycleMs, isRunning = false, remainingSeconds = displayedRemainingSeconds)
                        return
                    }
                    pendingLoopRestart = true
                } else {
                    pendingLoopRestart = false
                    displayedRemainingSeconds = state.selectedSeconds - secondInCycle.toInt()
                    if (state.tickVibrationEnabled) {
                        _vibrationEvents.tryEmit(VibrationEvent.Tick)
                    }
                }
            }
            processedElapsedSeconds = currentWholeSeconds
        }

        promoteNextLoopCycleIfNeeded(totalElapsedMs, state.selectedSeconds)
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

    private fun promoteNextLoopCycleIfNeeded(totalElapsedMs: Long, selectedSeconds: Int) {
        val nextCycleThresholdMs = processedElapsedSeconds * 1_000L
        if (pendingLoopRestart && totalElapsedMs > nextCycleThresholdMs) {
            pendingLoopRestart = false
            displayedRemainingSeconds = selectedSeconds
        }
    }

    private suspend fun restoreSettings() {
        val settings = settingsStore.settings.first()
        displayedRemainingSeconds = settings.selectedSeconds
        _uiState.update { state ->
            state.copy(
                selectedSeconds = settings.selectedSeconds,
                remainingSeconds = settings.selectedSeconds,
                loopEnabled = settings.loopEnabled,
                tickVibrationEnabled = settings.tickVibrationEnabled,
                loopVibrationEnabled = settings.loopVibrationEnabled,
            )
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
