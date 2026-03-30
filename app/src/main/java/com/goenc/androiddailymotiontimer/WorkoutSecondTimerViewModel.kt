package com.goenc.androiddailymotiontimer

import android.os.SystemClock
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

private const val DEFAULT_SECONDS = 5
private const val MIN_SECONDS = 1
private const val MAX_SECONDS = 10

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

class WorkoutSecondTimerViewModel : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(WorkoutTimerUiState())
    val uiState: StateFlow<WorkoutTimerUiState> = _uiState.asStateFlow()

    private val _vibrationEvents = MutableSharedFlow<VibrationEvent>(extraBufferCapacity = 8)
    val vibrationEvents: SharedFlow<VibrationEvent> = _vibrationEvents.asSharedFlow()

    private var timerJob: Job? = null
    private var accumulatedElapsedMs: Long = 0L
    private var runStartedAtMs: Long? = null
    private var lastProcessedElapsedSeconds: Long = 0L

    fun setSelectedSeconds(seconds: Int) {
        if (_uiState.value.isRunning) return
        val clampedSeconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        accumulatedElapsedMs = 0L
        lastProcessedElapsedSeconds = 0L
        _uiState.update {
            it.copy(
                selectedSeconds = clampedSeconds,
                remainingSeconds = clampedSeconds,
                elapsedTimeText = formatElapsedTime(0L),
            )
        }
    }

    fun setLoopEnabled(enabled: Boolean) {
        _uiState.update { it.copy(loopEnabled = enabled) }
    }

    fun setTickVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(tickVibrationEnabled = enabled) }
    }

    fun setLoopVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(loopVibrationEnabled = enabled) }
    }

    fun start() {
        if (_uiState.value.isRunning) return
        val cycleMs = _uiState.value.selectedSeconds * 1_000L
        if (accumulatedElapsedMs >= cycleMs) {
            accumulatedElapsedMs = 0L
            lastProcessedElapsedSeconds = 0L
            _uiState.update {
                it.copy(
                    remainingSeconds = it.selectedSeconds,
                    elapsedTimeText = formatElapsedTime(0L),
                )
            }
        }
        runStartedAtMs = SystemClock.elapsedRealtime()
        lastProcessedElapsedSeconds = accumulatedElapsedMs / 1_000L
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
        syncUiState(accumulatedElapsedMs, isRunning = false)
    }

    fun reset() {
        timerJob?.cancel()
        timerJob = null
        accumulatedElapsedMs = 0L
        runStartedAtMs = null
        lastProcessedElapsedSeconds = 0L
        _uiState.update {
            it.copy(
                remainingSeconds = it.selectedSeconds,
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

        if (currentWholeSeconds > lastProcessedElapsedSeconds) {
            for (second in (lastProcessedElapsedSeconds + 1)..currentWholeSeconds) {
                val atCycleEnd = second > 0L && second % state.selectedSeconds == 0L
                if (atCycleEnd) {
                    if (state.loopVibrationEnabled) {
                        _vibrationEvents.tryEmit(VibrationEvent.LoopComplete)
                    }
                    if (!state.loopEnabled) {
                        accumulatedElapsedMs = cycleMs
                        runStartedAtMs = null
                        lastProcessedElapsedSeconds = state.selectedSeconds.toLong()
                        timerJob?.cancel()
                        timerJob = null
                        syncUiState(cycleMs, isRunning = false, remainingSecondsOverride = 0)
                        return
                    }
                } else if (state.tickVibrationEnabled) {
                    _vibrationEvents.tryEmit(VibrationEvent.Tick)
                }
            }
            lastProcessedElapsedSeconds = currentWholeSeconds
        }

        syncUiState(totalElapsedMs, isRunning = true)
    }

    private fun syncUiState(
        totalElapsedMs: Long,
        isRunning: Boolean,
        remainingSecondsOverride: Int? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                remainingSeconds = remainingSecondsOverride ?: calculateRemainingSeconds(
                    totalElapsedMs = totalElapsedMs,
                    selectedSeconds = state.selectedSeconds,
                    loopEnabled = state.loopEnabled,
                ),
                elapsedTimeText = formatElapsedTime(totalElapsedMs),
                isRunning = isRunning,
            )
        }
    }

    private fun currentTotalElapsedMs(): Long {
        val startedAtMs = runStartedAtMs ?: return accumulatedElapsedMs
        return accumulatedElapsedMs + max(0L, SystemClock.elapsedRealtime() - startedAtMs)
    }

    private fun calculateRemainingSeconds(
        totalElapsedMs: Long,
        selectedSeconds: Int,
        loopEnabled: Boolean,
    ): Int {
        val cycleMs = selectedSeconds * 1_000L
        if (!loopEnabled) {
            if (totalElapsedMs >= cycleMs) return 0
            return selectedSeconds - (totalElapsedMs / 1_000L).toInt()
        }

        if (totalElapsedMs > 0L && totalElapsedMs % cycleMs == 0L) {
            return 0
        }

        return selectedSeconds - ((totalElapsedMs % cycleMs) / 1_000L).toInt()
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
