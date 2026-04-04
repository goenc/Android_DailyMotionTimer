package com.goenc.androiddailymotiontimer

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
const val MIN_VIBRATION_LEVEL = 1
const val MAX_VIBRATION_LEVEL = 4
const val DEFAULT_NORMAL_VIBRATION_LEVEL = 2
const val DEFAULT_COMPLETE_VIBRATION_LEVEL = 2

private const val PREPARATION_SECONDS = 10
private const val PREPARATION_COMPLETE_DURATION_MS = (PREPARATION_SECONDS + 1) * 1_000L
private const val SESSION_STATUS_KEY = "session_status"
private const val CURRENT_PHASE_KEY = "current_phase"
private const val ROUND_TRIP_COUNT_KEY = "round_trip_count"
private const val ACTIVE_ELAPSED_MS_KEY = "active_elapsed_ms"
private const val PHASE_STARTED_AT_MS_KEY = "phase_started_at_ms"
private const val NEXT_BOUNDARY_INDEX_KEY = "next_boundary_index"
private const val REMAINING_SECONDS_KEY = "remaining_seconds"
private const val PREPARATION_ELAPSED_MS_KEY = "preparation_elapsed_ms"
private const val PREPARATION_REMAINING_SECONDS_KEY = "preparation_remaining_seconds"

enum class CountSoundMode {
    Beep,
    Voice,
}

enum class WorkoutPhase {
    Fast,
    Slow,
}

enum class TimerSessionStatus {
    Idle,
    PreparingRunning,
    PreparingPaused,
    ActiveRunning,
    ActivePaused,
    Completed,
    ;

    val isRunning: Boolean
        get() = this == PreparingRunning || this == ActiveRunning

    val isPreparing: Boolean
        get() = this == PreparingRunning || this == PreparingPaused

    val isPaused: Boolean
        get() = this == PreparingPaused || this == ActivePaused
}

data class WorkoutTimerUiState(
    val selectedSeconds: Int = DEFAULT_SECONDS,
    val remainingSeconds: Int = DEFAULT_SECONDS,
    val preparationRemainingSeconds: Int = PREPARATION_SECONDS,
    val elapsedTimeText: String = "00:00",
    val sessionStatus: TimerSessionStatus = TimerSessionStatus.Idle,
    val currentPhase: WorkoutPhase = WorkoutPhase.Fast,
    val roundTripCount: Int = 0,
    val loopEnabled: Boolean = false,
    val tickVibrationEnabled: Boolean = false,
    val loopVibrationEnabled: Boolean = true,
    val countdownSoundEnabled: Boolean = true,
    val countSoundMode: CountSoundMode = CountSoundMode.Beep,
    val earlyTickVolume: Int = DEFAULT_EARLY_TICK_VOLUME,
    val tickVolume: Int = DEFAULT_TICK_VOLUME,
    val loopCompleteVolume: Int = DEFAULT_LOOP_COMPLETE_VOLUME,
    val normalVibrationLevel: Int = DEFAULT_NORMAL_VIBRATION_LEVEL,
    val completeVibrationLevel: Int = DEFAULT_COMPLETE_VIBRATION_LEVEL,
) {
    val vibrationEnabled: Boolean
        get() = tickVibrationEnabled || loopVibrationEnabled

    val isRunning: Boolean
        get() = sessionStatus.isRunning

    val isPreparing: Boolean
        get() = sessionStatus.isPreparing

    val isPaused: Boolean
        get() = sessionStatus.isPaused

    val hasStarted: Boolean
        get() = sessionStatus != TimerSessionStatus.Idle

    val canResume: Boolean
        get() = sessionStatus == TimerSessionStatus.PreparingPaused ||
            sessionStatus == TimerSessionStatus.ActivePaused

    val canChangeSeconds: Boolean
        get() = sessionStatus == TimerSessionStatus.Idle ||
            sessionStatus == TimerSessionStatus.Completed ||
            sessionStatus == TimerSessionStatus.PreparingPaused ||
            sessionStatus == TimerSessionStatus.ActivePaused

    val primaryButtonShowsStart: Boolean
        get() = sessionStatus == TimerSessionStatus.Idle || sessionStatus == TimerSessionStatus.Completed

    val primaryButtonShowsStop: Boolean
        get() = sessionStatus == TimerSessionStatus.PreparingPaused ||
            sessionStatus == TimerSessionStatus.ActivePaused

    val secondaryButtonEnabled: Boolean
        get() = sessionStatus.isRunning || sessionStatus.isPaused

    val displaySeconds: Int
        get() = if (isPreparing) preparationRemainingSeconds else remainingSeconds
}

enum class VibrationEvent {
    Tick,
    LoopComplete,
}

data class CountdownSoundEvent(
    val cueType: CountdownCueType,
    val displayedValue: Int,
)

enum class CountdownCueType {
    EarlyTick,
    Tick,
    LoopComplete,
}

class WorkoutSecondTimerViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore = WorkoutSettingsStore(application.applicationContext)
    private val _uiState = MutableStateFlow(WorkoutTimerUiState())
    val uiState: StateFlow<WorkoutTimerUiState> = _uiState.asStateFlow()

    private val _vibrationEvents = MutableSharedFlow<VibrationEvent>(extraBufferCapacity = 8)
    val vibrationEvents: SharedFlow<VibrationEvent> = _vibrationEvents.asSharedFlow()
    private val _countdownSoundEvents = MutableSharedFlow<CountdownSoundEvent>(extraBufferCapacity = 8)
    val countdownSoundEvents: SharedFlow<CountdownSoundEvent> = _countdownSoundEvents.asSharedFlow()

    private var timerJob: Job? = null
    private var sessionStatus: TimerSessionStatus = TimerSessionStatus.Idle
    private var currentPhase: WorkoutPhase = WorkoutPhase.Fast
    private var roundTripCount: Int = 0
    private var activeElapsedMs: Long = 0L
    private var activeRunStartedAtMs: Long? = null
    private var currentPhaseStartedAtElapsedMs: Long = 0L
    private var nextBoundaryIndex: Int = 1
    private var displayedRemainingSeconds: Int = DEFAULT_SECONDS
    private var preparationElapsedMs: Long = 0L
    private var preparationRunStartedAtMs: Long? = null
    private var displayedPreparationSeconds: Int = PREPARATION_SECONDS
    private var hasPlayedInitialDisplayCue: Boolean = false

    init {
        scope.launch {
            restoreSettings()
        }
    }

    fun setSelectedSeconds(seconds: Int) {
        if (!_uiState.value.canChangeSeconds) return
        val clampedSeconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        resetAllProgress(clampedSeconds, resetRoundTrips = true)
        sessionStatus = TimerSessionStatus.Idle
        publishUiState()
        _uiState.update { it.copy(selectedSeconds = clampedSeconds) }
        persistCurrentSettings()
    }

    fun setLoopEnabled(enabled: Boolean) {
        _uiState.update { it.copy(loopEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                tickVibrationEnabled = enabled,
                loopVibrationEnabled = enabled,
            )
        }
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

    fun setNormalVibrationLevel(value: Int) {
        _uiState.update {
            it.copy(normalVibrationLevel = value.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL))
        }
        persistCurrentSettings()
    }

    fun setCompleteVibrationLevel(value: Int) {
        _uiState.update {
            it.copy(
                completeVibrationLevel = value.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL)
            )
        }
        persistCurrentSettings()
    }

    fun setCountdownSoundEnabled(enabled: Boolean) {
        _uiState.update { it.copy(countdownSoundEnabled = enabled) }
        persistCurrentSettings()
    }

    fun setCountSoundMode(mode: CountSoundMode) {
        _uiState.update { it.copy(countSoundMode = mode) }
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

    fun onPrimaryAction() {
        when {
            _uiState.value.primaryButtonShowsStart -> start()
            _uiState.value.primaryButtonShowsStop -> stop()
            else -> resetWithPreparation()
        }
    }

    fun onSecondaryAction() {
        when (sessionStatus) {
            TimerSessionStatus.PreparingRunning,
            TimerSessionStatus.ActiveRunning,
            -> pause()

            TimerSessionStatus.PreparingPaused,
            TimerSessionStatus.ActivePaused,
            -> resume()

            else -> Unit
        }
    }

    fun start() {
        if (!_uiState.value.primaryButtonShowsStart) return
        resetAllProgress(_uiState.value.selectedSeconds, resetRoundTrips = true)
        startActiveRun(playInitialCue = true)
    }

    fun pause() {
        when (sessionStatus) {
            TimerSessionStatus.PreparingRunning -> {
                preparationElapsedMs = currentPreparationElapsedMs()
                displayedPreparationSeconds = preparationDisplayValue(preparationElapsedMs)
                preparationRunStartedAtMs = null
                timerJob?.cancel()
                timerJob = null
                sessionStatus = TimerSessionStatus.PreparingPaused
                publishUiState(preparationElapsedMsSnapshot = preparationElapsedMs)
                hasPlayedInitialDisplayCue = true
            }

            TimerSessionStatus.ActiveRunning -> {
                activeElapsedMs = currentActiveElapsedMs()
                activeRunStartedAtMs = null
                timerJob?.cancel()
                timerJob = null
                sessionStatus = TimerSessionStatus.ActivePaused
                publishUiState(activeElapsedMsSnapshot = activeElapsedMs)
                hasPlayedInitialDisplayCue = true
            }

            else -> Unit
        }
    }

    fun resume() {
        when (sessionStatus) {
            TimerSessionStatus.PreparingPaused -> {
                sessionStatus = TimerSessionStatus.PreparingRunning
                preparationRunStartedAtMs = SystemClock.elapsedRealtime()
                publishUiState(preparationElapsedMsSnapshot = preparationElapsedMs)
                launchTimerLoop()
            }

            TimerSessionStatus.ActivePaused -> {
                sessionStatus = TimerSessionStatus.ActiveRunning
                activeRunStartedAtMs = SystemClock.elapsedRealtime()
                publishUiState(activeElapsedMsSnapshot = activeElapsedMs)
                launchTimerLoop()
            }

            else -> Unit
        }
    }

    fun stop() {
        if (sessionStatus == TimerSessionStatus.Idle || sessionStatus == TimerSessionStatus.Completed) return
        resetAllProgress(_uiState.value.selectedSeconds, resetRoundTrips = true)
        sessionStatus = TimerSessionStatus.Idle
        publishUiState()
    }

    fun resetWithPreparation() {
        if (sessionStatus == TimerSessionStatus.Idle || sessionStatus == TimerSessionStatus.Completed) return
        resetAllProgress(_uiState.value.selectedSeconds, resetRoundTrips = true)
        startPreparationRun()
    }

    private fun launchTimerLoop() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                when (sessionStatus) {
                    TimerSessionStatus.PreparingRunning -> updatePreparationState()
                    TimerSessionStatus.ActiveRunning -> updateActiveTimerState()
                    else -> return@launch
                }
                delay(20L)
            }
        }
    }

    private suspend fun updatePreparationState() {
        val state = _uiState.value
        val totalPreparationElapsedMs = currentPreparationElapsedMs()
        val currentDisplay = preparationDisplayValue(totalPreparationElapsedMs)

        if (currentDisplay != displayedPreparationSeconds) {
            displayedPreparationSeconds = currentDisplay
            emitCountSwitchEffects(state, displayedPreparationSeconds)
        }

        if (totalPreparationElapsedMs >= PREPARATION_COMPLETE_DURATION_MS) {
            preparationElapsedMs = 0L
            preparationRunStartedAtMs = null
            displayedPreparationSeconds = PREPARATION_SECONDS
            startActiveRun(playInitialCue = true)
            return
        }

        publishUiState(preparationElapsedMsSnapshot = totalPreparationElapsedMs)
    }

    private suspend fun updateActiveTimerState() {
        val state = _uiState.value
        val engine = WorkoutTimerEngine(state.selectedSeconds)
        val totalActiveElapsedMs = currentActiveElapsedMs()

        while (true) {
            val nextBoundaryElapsedMs = nextBoundaryElapsedMs(engine)
            val phaseFinished = totalActiveElapsedMs >= currentPhaseStartedAtElapsedMs + engine.phaseDurationMs

            when {
                nextBoundaryElapsedMs != null && totalActiveElapsedMs >= nextBoundaryElapsedMs -> {
                    displayedRemainingSeconds = engine.displayValueForBoundary(nextBoundaryIndex)
                    emitCountSwitchEffects(state, displayedRemainingSeconds)
                    nextBoundaryIndex += 1
                }

                phaseFinished && currentPhase == WorkoutPhase.Fast -> {
                    currentPhase = WorkoutPhase.Slow
                    currentPhaseStartedAtElapsedMs += engine.phaseDurationMs
                    displayedRemainingSeconds = engine.initialDisplayValue()
                    nextBoundaryIndex = 1
                    hasPlayedInitialDisplayCue = false
                    emitInitialDisplayCueIfNeeded(state, displayedRemainingSeconds)
                }

                phaseFinished && _uiState.value.loopEnabled -> {
                    roundTripCount += 1
                    currentPhase = WorkoutPhase.Fast
                    currentPhaseStartedAtElapsedMs += engine.phaseDurationMs
                    displayedRemainingSeconds = engine.initialDisplayValue()
                    nextBoundaryIndex = 1
                    hasPlayedInitialDisplayCue = false
                    emitInitialDisplayCueIfNeeded(state, displayedRemainingSeconds)
                }

                phaseFinished -> {
                    roundTripCount += 1
                    activeElapsedMs = currentPhaseStartedAtElapsedMs + engine.phaseDurationMs
                    activeRunStartedAtMs = null
                    timerJob?.cancel()
                    timerJob = null
                    sessionStatus = TimerSessionStatus.Completed
                    displayedRemainingSeconds = 0
                    nextBoundaryIndex = engine.boundaryCount + 1
                    publishUiState(activeElapsedMsSnapshot = activeElapsedMs)
                    return
                }

                else -> break
            }
        }

        publishUiState(activeElapsedMsSnapshot = totalActiveElapsedMs)
    }

    private suspend fun restoreSettings() {
        val settings = settingsStore.settings.first()
        restoreSessionState(settings.selectedSeconds)
        _uiState.update { state ->
            state.copy(
                selectedSeconds = settings.selectedSeconds,
                remainingSeconds = displayedRemainingSeconds,
                preparationRemainingSeconds = displayedPreparationSeconds,
                elapsedTimeText = formatElapsedTime(activeElapsedMs),
                sessionStatus = sessionStatus,
                currentPhase = currentPhase,
                roundTripCount = roundTripCount,
                loopEnabled = settings.loopEnabled,
                tickVibrationEnabled = settings.tickVibrationEnabled,
                loopVibrationEnabled = settings.loopVibrationEnabled,
                countdownSoundEnabled = settings.countdownSoundEnabled,
                countSoundMode = settings.countSoundMode,
                earlyTickVolume = settings.earlyTickVolume,
                tickVolume = settings.tickVolume,
                loopCompleteVolume = settings.loopCompleteVolume,
                normalVibrationLevel = settings.normalVibrationLevel,
                completeVibrationLevel = settings.completeVibrationLevel,
            )
        }
        persistSessionSnapshot()
    }

    private fun restoreSessionState(selectedSeconds: Int) {
        val engine = WorkoutTimerEngine(selectedSeconds)
        val restoredStatus = savedStateHandle.get<String>(SESSION_STATUS_KEY)
            ?.let { name -> TimerSessionStatus.entries.firstOrNull { it.name == name } }
            ?: TimerSessionStatus.Idle
        val restoredPhase = savedStateHandle.get<String>(CURRENT_PHASE_KEY)
            ?.let { name -> WorkoutPhase.entries.firstOrNull { it.name == name } }
            ?: WorkoutPhase.Fast

        currentPhase = restoredPhase
        roundTripCount = max(0, savedStateHandle.get<Int>(ROUND_TRIP_COUNT_KEY) ?: 0)
        activeElapsedMs = max(0L, savedStateHandle.get<Long>(ACTIVE_ELAPSED_MS_KEY) ?: 0L)
        currentPhaseStartedAtElapsedMs = max(
            0L,
            savedStateHandle.get<Long>(PHASE_STARTED_AT_MS_KEY)
                ?: if (restoredPhase == WorkoutPhase.Slow) engine.phaseDurationMs else 0L,
        )
        nextBoundaryIndex = (savedStateHandle.get<Int>(NEXT_BOUNDARY_INDEX_KEY)
            ?: 1).coerceIn(1, engine.boundaryCount + 1)
        displayedRemainingSeconds = (savedStateHandle.get<Int>(REMAINING_SECONDS_KEY)
            ?: engine.initialDisplayValue()).coerceIn(0, selectedSeconds)
        preparationElapsedMs = max(0L, savedStateHandle.get<Long>(PREPARATION_ELAPSED_MS_KEY) ?: 0L)
        displayedPreparationSeconds = (savedStateHandle.get<Int>(PREPARATION_REMAINING_SECONDS_KEY)
            ?: PREPARATION_SECONDS).coerceIn(0, PREPARATION_SECONDS)
        sessionStatus = when (restoredStatus) {
            TimerSessionStatus.PreparingRunning -> TimerSessionStatus.PreparingPaused
            TimerSessionStatus.ActiveRunning -> TimerSessionStatus.ActivePaused
            else -> restoredStatus
        }

        if (sessionStatus == TimerSessionStatus.Idle) {
            resetAllProgress(selectedSeconds, resetRoundTrips = true)
        } else {
            activeRunStartedAtMs = null
            preparationRunStartedAtMs = null
            timerJob = null
            hasPlayedInitialDisplayCue = sessionStatus.isPaused
        }
    }

    private fun startPreparationRun() {
        timerJob?.cancel()
        timerJob = null
        activeRunStartedAtMs = null
        preparationElapsedMs = 0L
        preparationRunStartedAtMs = SystemClock.elapsedRealtime()
        displayedPreparationSeconds = PREPARATION_SECONDS
        sessionStatus = TimerSessionStatus.PreparingRunning
        hasPlayedInitialDisplayCue = false
        publishUiState(preparationElapsedMsSnapshot = 0L)
        emitInitialDisplayCueIfNeeded(_uiState.value, displayedPreparationSeconds)
        launchTimerLoop()
    }

    private fun startActiveRun(playInitialCue: Boolean) {
        timerJob?.cancel()
        timerJob = null
        sessionStatus = TimerSessionStatus.ActiveRunning
        activeRunStartedAtMs = SystemClock.elapsedRealtime()
        preparationRunStartedAtMs = null
        displayedPreparationSeconds = PREPARATION_SECONDS
        publishUiState(activeElapsedMsSnapshot = activeElapsedMs, preparationElapsedMsSnapshot = 0L)
        if (playInitialCue) {
            emitInitialDisplayCueIfNeeded(_uiState.value, displayedRemainingSeconds)
        }
        launchTimerLoop()
    }

    private fun resetAllProgress(selectedSeconds: Int, resetRoundTrips: Boolean) {
        timerJob?.cancel()
        timerJob = null
        activeElapsedMs = 0L
        activeRunStartedAtMs = null
        currentPhase = WorkoutPhase.Fast
        currentPhaseStartedAtElapsedMs = 0L
        nextBoundaryIndex = 1
        displayedRemainingSeconds = WorkoutTimerEngine(selectedSeconds).initialDisplayValue()
        preparationElapsedMs = 0L
        preparationRunStartedAtMs = null
        displayedPreparationSeconds = PREPARATION_SECONDS
        hasPlayedInitialDisplayCue = false
        if (resetRoundTrips) {
            roundTripCount = 0
        }
    }

    private fun publishUiState(
        activeElapsedMsSnapshot: Long = activeElapsedMs,
        preparationElapsedMsSnapshot: Long = preparationElapsedMs,
    ) {
        _uiState.update { state ->
            state.copy(
                remainingSeconds = displayedRemainingSeconds,
                preparationRemainingSeconds = displayedPreparationSeconds,
                elapsedTimeText = formatElapsedTime(activeElapsedMsSnapshot),
                sessionStatus = sessionStatus,
                currentPhase = currentPhase,
                roundTripCount = roundTripCount,
            )
        }
        persistSessionSnapshot(
            activeElapsedMsSnapshot = activeElapsedMsSnapshot,
            preparationElapsedMsSnapshot = preparationElapsedMsSnapshot,
        )
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
        if (state.tickVibrationEnabled) {
            _vibrationEvents.tryEmit(VibrationEvent.Tick)
        }
        emitCountdownSound(displayedValue, state.countdownSoundEnabled)
        hasPlayedInitialDisplayCue = true
    }

    private fun emitCountdownSound(displayedValue: Int, countdownSoundEnabled: Boolean) {
        if (!countdownSoundEnabled) return
        if (displayedValue == 0) {
            _countdownSoundEvents.tryEmit(
                CountdownSoundEvent(
                    cueType = CountdownCueType.LoopComplete,
                    displayedValue = displayedValue,
                )
            )
        } else if (displayedValue >= 4) {
            _countdownSoundEvents.tryEmit(
                CountdownSoundEvent(
                    cueType = CountdownCueType.EarlyTick,
                    displayedValue = displayedValue,
                )
            )
        } else if (displayedValue in 1..3) {
            _countdownSoundEvents.tryEmit(
                CountdownSoundEvent(
                    cueType = CountdownCueType.Tick,
                    displayedValue = displayedValue,
                )
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
                    countdownSoundEnabled = state.countdownSoundEnabled,
                    countSoundMode = state.countSoundMode,
                    earlyTickVolume = state.earlyTickVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                    tickVolume = state.tickVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                    loopCompleteVolume = state.loopCompleteVolume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME),
                    normalVibrationLevel = state.normalVibrationLevel.coerceIn(
                        MIN_VIBRATION_LEVEL,
                        MAX_VIBRATION_LEVEL,
                    ),
                    completeVibrationLevel = state.completeVibrationLevel.coerceIn(
                        MIN_VIBRATION_LEVEL,
                        MAX_VIBRATION_LEVEL,
                    ),
                )
            )
        }
    }

    private fun persistSessionSnapshot(
        activeElapsedMsSnapshot: Long = activeElapsedMs,
        preparationElapsedMsSnapshot: Long = preparationElapsedMs,
    ) {
        savedStateHandle[SESSION_STATUS_KEY] = sessionStatus.name
        savedStateHandle[CURRENT_PHASE_KEY] = currentPhase.name
        savedStateHandle[ROUND_TRIP_COUNT_KEY] = roundTripCount
        savedStateHandle[ACTIVE_ELAPSED_MS_KEY] = activeElapsedMsSnapshot
        savedStateHandle[PHASE_STARTED_AT_MS_KEY] = currentPhaseStartedAtElapsedMs
        savedStateHandle[NEXT_BOUNDARY_INDEX_KEY] = nextBoundaryIndex
        savedStateHandle[REMAINING_SECONDS_KEY] = displayedRemainingSeconds
        savedStateHandle[PREPARATION_ELAPSED_MS_KEY] = preparationElapsedMsSnapshot
        savedStateHandle[PREPARATION_REMAINING_SECONDS_KEY] = displayedPreparationSeconds
    }

    private fun formatElapsedTime(totalElapsedMs: Long): String {
        val totalSeconds = totalElapsedMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun currentActiveElapsedMs(): Long {
        val startedAtMs = activeRunStartedAtMs ?: return activeElapsedMs
        return activeElapsedMs + max(0L, SystemClock.elapsedRealtime() - startedAtMs)
    }

    private fun currentPreparationElapsedMs(): Long {
        val startedAtMs = preparationRunStartedAtMs ?: return preparationElapsedMs
        return preparationElapsedMs + max(0L, SystemClock.elapsedRealtime() - startedAtMs)
    }

    private fun preparationDisplayValue(elapsedMs: Long): Int {
        val elapsedWholeSeconds = (elapsedMs / 1_000L).toInt()
        return (PREPARATION_SECONDS - elapsedWholeSeconds).coerceIn(0, PREPARATION_SECONDS)
    }

    private fun nextBoundaryElapsedMs(engine: WorkoutTimerEngine): Long? {
        if (nextBoundaryIndex > engine.boundaryCount) return null
        return currentPhaseStartedAtElapsedMs + engine.boundaryElapsedMs(nextBoundaryIndex)
    }

    override fun onCleared() {
        timerJob?.cancel()
        scope.cancel()
    }
}
