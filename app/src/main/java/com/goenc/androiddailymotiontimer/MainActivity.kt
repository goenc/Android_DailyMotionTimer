package com.goenc.androiddailymotiontimer

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.goenc.androiddailymotiontimer.ui.theme.AndroidDailyMotionTimerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

private val PreparationCountColor = Color(0xFFFF9800)
private val HeaderCountColor = Color(0xFFFF7A00)
private val ProgressGreenBackground = Color(0xFFD9F4D1)
private val ProgressWarmLowBackground = Color(0xFFFFDA9E)
private val ProgressWarmMidBackground = Color(0xFFFFBC73)
private val ProgressWarmHighBackground = Color(0xFFFF9B47)
private val ProgressCompleteBackground = Color(0xFFFF7A1A)
private val RunningInfoTextColor = Color(0xFF111111)

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: WorkoutSecondTimerViewModel
    private val countdownCuePlayer = CountdownCuePlayer()
    private lateinit var countdownVoicePlayer: CountdownVoicePlayer
    private lateinit var heartRateMonitor: HeartRateMonitor
    private var heartRate by mutableIntStateOf(0)
    private var heartRateDevices by mutableStateOf(emptyList<HeartRateDevice>())
    private var heartRateConnectionState by mutableStateOf(HeartRateConnectionState.Disconnected)
    private var heartRateError by mutableStateOf<String?>(null)
    private var savedHeartRateDevice by mutableStateOf<SavedHeartRateDevice?>(null)
    private var hasBluetoothPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialStartupBackgroundTransform =
            WorkoutSettingsStore(applicationContext).readStartupBackgroundTransformSync()
        timerViewModel = ViewModelProvider(this)[WorkoutSecondTimerViewModel::class.java]
        countdownVoicePlayer = CountdownVoicePlayer(applicationContext)
        hasBluetoothPermission = hasBluetoothPermissions()
        heartRateMonitor = HeartRateMonitor(
            context = applicationContext,
            bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter,
            onDevicesChanged = { devices -> runOnUiThread { heartRateDevices = devices } },
            onConnectionChanged = { state, error ->
                runOnUiThread {
                    heartRateConnectionState = state
                    heartRateError = error
                }
            },
            onHeartRateChanged = { value -> runOnUiThread { heartRate = value } },
        )
        savedHeartRateDevice = heartRateMonitor.savedDevice
        enableEdgeToEdge()
        setContent {
            AndroidDailyMotionTimerTheme {
                val uiState by timerViewModel.uiState.collectAsState()
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    hasBluetoothPermission = hasBluetoothPermissions()
                    if (hasBluetoothPermission) {
                        heartRateError = null
                        heartRateMonitor.connectSavedDevice()
                    } else {
                        heartRateError = "Bluetooth権限が必要です"
                    }
                }
                WorkoutSecondTimerScreen(
                    uiState = uiState,
                    heartRate = heartRate,
                    heartRateDevices = heartRateDevices,
                    heartRateConnectionState = heartRateConnectionState,
                    heartRateError = heartRateError,
                    savedHeartRateDevice = savedHeartRateDevice,
                    hasBluetoothPermission = hasBluetoothPermission,
                    initialStartupBackgroundTransform = initialStartupBackgroundTransform,
                    vibrationEvents = timerViewModel.vibrationEvents,
                    countdownSoundEvents = timerViewModel.countdownSoundEvents,
                    onFastPhaseDurationSelected = timerViewModel::setSelectedSeconds,
                    onSlowPhaseDurationSelected = timerViewModel::setSlowPhaseDurationSeconds,
                    onLoopChanged = timerViewModel::setLoopEnabled,
                    onMaxLoopCountChanged = timerViewModel::setMaxLoopCount,
                    onNormalCountMaxCountChanged = timerViewModel::setNormalCountMaxCount,
                    onVibrationChanged = timerViewModel::setVibrationEnabled,
                    onNormalVibrationLevelChanged = timerViewModel::setNormalVibrationLevel,
                    onCompleteVibrationLevelChanged = timerViewModel::setCompleteVibrationLevel,
                    onCountdownSoundChanged = timerViewModel::setCountdownSoundEnabled,
                    onCountSoundModeChanged = timerViewModel::setCountSoundMode,
                    onTimerModeSelected = timerViewModel::setTimerMode,
                    onNormalCountIntervalSelected = timerViewModel::setNormalCountInterval,
                    onEarlyTickVolumeChanged = timerViewModel::setEarlyTickVolume,
                    onTickVolumeChanged = timerViewModel::setTickVolume,
                    onLoopCompleteVolumeChanged = timerViewModel::setLoopCompleteVolume,
                    onStartupBackgroundTransformSaved = timerViewModel::setStartupBackgroundTransform,
                    onPrimaryAction = timerViewModel::onPrimaryAction,
                    onSecondaryAction = timerViewModel::onSecondaryAction,
                    onRequestBluetoothPermission = {
                        permissionLauncher.launch(bluetoothPermissions())
                    },
                    onStartHeartRateScan = heartRateMonitor::startScan,
                    onConnectHeartRateDevice = { address ->
                        heartRateMonitor.connect(address)
                        savedHeartRateDevice = heartRateMonitor.savedDevice
                    },
                    onDisconnectHeartRateDevice = heartRateMonitor::disconnect,
                    onForgetHeartRateDevice = {
                        heartRateMonitor.forgetDevice()
                        savedHeartRateDevice = null
                        heartRateDevices = emptyList()
                    },
                    countdownCuePlayer = countdownCuePlayer,
                    countdownVoicePlayer = countdownVoicePlayer,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        hasBluetoothPermission = hasBluetoothPermissions()
        if (hasBluetoothPermission && heartRateMonitor.savedDevice != null) {
            heartRateMonitor.connectSavedDevice()
        }
    }

    override fun onStop() {
        heartRateMonitor.stopScan()
        heartRateMonitor.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        countdownVoicePlayer.release()
        countdownCuePlayer.release()
        super.onDestroy()
    }

    private fun hasBluetoothPermissions(): Boolean = bluetoothPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

@Composable
private fun WorkoutSecondTimerScreen(
    uiState: WorkoutTimerUiState,
    heartRate: Int,
    heartRateDevices: List<HeartRateDevice>,
    heartRateConnectionState: HeartRateConnectionState,
    heartRateError: String?,
    savedHeartRateDevice: SavedHeartRateDevice?,
    hasBluetoothPermission: Boolean,
    initialStartupBackgroundTransform: StartupBackgroundTransform,
    vibrationEvents: SharedFlow<VibrationEvent>,
    countdownSoundEvents: SharedFlow<CountdownSoundEvent>,
    onFastPhaseDurationSelected: (Int) -> Unit,
    onSlowPhaseDurationSelected: (Int) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onMaxLoopCountChanged: (Int) -> Unit,
    onNormalCountMaxCountChanged: (Int) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onNormalVibrationLevelChanged: (Int) -> Unit,
    onCompleteVibrationLevelChanged: (Int) -> Unit,
    onCountdownSoundChanged: (Boolean) -> Unit,
    onCountSoundModeChanged: (CountSoundMode) -> Unit,
    onTimerModeSelected: (TimerMode) -> Unit,
    onNormalCountIntervalSelected: (NormalCountInterval) -> Unit,
    onEarlyTickVolumeChanged: (Int) -> Unit,
    onTickVolumeChanged: (Int) -> Unit,
    onLoopCompleteVolumeChanged: (Int) -> Unit,
    onStartupBackgroundTransformSaved: (Float, Float, Float) -> Unit,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onStartHeartRateScan: () -> Unit,
    onConnectHeartRateDevice: (String) -> Unit,
    onDisconnectHeartRateDevice: () -> Unit,
    onForgetHeartRateDevice: () -> Unit,
    countdownCuePlayer: CountdownCuePlayer,
    countdownVoicePlayer: CountdownVoicePlayer,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val secondOptions = (MIN_SECONDS..MAX_SECONDS).toList()
    val fastSecondListState = rememberLazyListState()
    val slowSecondListState = rememberLazyListState()
    var hasCenteredInitialSelection by remember { mutableStateOf(false) }
    var showLaunchOverlay by remember { mutableStateOf(true) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showStartupBackgroundDialog by remember { mutableStateOf(false) }
    val latestUiState by rememberUpdatedState(uiState)
    val idleBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val timerBackgroundColor = remember(uiState, idleBackgroundColor) {
        timerBackgroundColor(uiState, idleBackgroundColor)
    }
    val launchBackgroundScale = if (uiState.isSettingsReady) {
        uiState.startupBackgroundScale
    } else {
        initialStartupBackgroundTransform.scale
    }
    val launchBackgroundOffsetXPct = if (uiState.isSettingsReady) {
        uiState.startupBackgroundOffsetXPct
    } else {
        initialStartupBackgroundTransform.offsetXPct
    }
    val launchBackgroundOffsetYPct = if (uiState.isSettingsReady) {
        uiState.startupBackgroundOffsetYPct
    } else {
        initialStartupBackgroundTransform.offsetYPct
    }

    DisposableEffect(view, uiState.isRunning) {
        view.keepScreenOn = uiState.isRunning
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(vibrationEvents, context) {
        vibrationEvents.collectLatest { event ->
            vibrate(
                context = context,
                event = event,
                normalVibrationLevel = latestUiState.normalVibrationLevel,
                completeVibrationLevel = latestUiState.completeVibrationLevel,
            )
        }
    }

    LaunchedEffect(countdownSoundEvents, countdownCuePlayer, countdownVoicePlayer) {
        countdownSoundEvents.collectLatest { event ->
            when (latestUiState.countSoundMode) {
                CountSoundMode.Beep -> {
                    when (event.cueType) {
                        CountdownCueType.EarlyTick -> countdownCuePlayer.playEarlySingleCue()
                        CountdownCueType.Tick -> countdownCuePlayer.playSingleCue()
                        CountdownCueType.LoopComplete -> countdownCuePlayer.playDoubleCue()
                    }
                }

                CountSoundMode.Voice -> countdownVoicePlayer.playCount(
                    count = event.displayedValue,
                    cueType = event.cueType,
                    isNormalCountMode = event.isNormalCountMode,
                    voicePhase = event.voicePhase,
                    voiceRoundTripCount = event.voiceRoundTripCount,
                )
            }
        }
    }

    LaunchedEffect(
        countdownCuePlayer,
        uiState.earlyTickVolume,
        uiState.tickVolume,
        uiState.loopCompleteVolume,
    ) {
        countdownCuePlayer.setEarlyTickVolume(uiState.earlyTickVolume)
        countdownCuePlayer.setTickVolume(uiState.tickVolume)
        countdownCuePlayer.setLoopCompleteVolume(uiState.loopCompleteVolume)
        countdownVoicePlayer.setEarlyTickVolume(uiState.earlyTickVolume)
        countdownVoicePlayer.setTickVolume(uiState.tickVolume)
        countdownVoicePlayer.setLoopCompleteVolume(uiState.loopCompleteVolume)
    }

    DisposableEffect(countdownCuePlayer, countdownVoicePlayer) {
        onDispose {
            countdownVoicePlayer.stop()
            countdownCuePlayer.stop()
        }
    }

    LaunchedEffect(uiState.isRunning) {
        if (!uiState.isRunning) {
            countdownVoicePlayer.stop()
            countdownCuePlayer.stop()
        }
    }

    LaunchedEffect(uiState.countdownSoundEnabled) {
        if (!uiState.countdownSoundEnabled) {
            countdownVoicePlayer.stop()
            countdownCuePlayer.stop()
        }
    }

    LaunchedEffect(uiState.countSoundMode) {
        countdownVoicePlayer.stop()
        countdownCuePlayer.stop()
    }

    LaunchedEffect(uiState.isSettingsReady) {
        if (uiState.isSettingsReady) {
            delay(1200)
            showLaunchOverlay = false
        }
    }

    if (showLaunchOverlay) {
        LaunchLoadingScreen(
            scale = launchBackgroundScale,
            offsetXPct = launchBackgroundOffsetXPct,
            offsetYPct = launchBackgroundOffsetYPct,
        )
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(timerBackgroundColor),
        color = Color.Transparent,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val compactLayout = maxHeight < 760.dp
            val countFontSize = when {
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 620.dp -> 132.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 700.dp -> 154.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 760.dp -> 178.sp
                uiState.timerMode == TimerMode.NormalCount -> 196.sp
                maxHeight < 620.dp -> 116.sp
                maxHeight < 700.dp -> 136.sp
                maxHeight < 760.dp -> 158.sp
                else -> 176.sp
            }
            val countLineHeight = when {
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 620.dp -> 124.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 700.dp -> 144.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 760.dp -> 168.sp
                uiState.timerMode == TimerMode.NormalCount -> 186.sp
                maxHeight < 620.dp -> 110.sp
                maxHeight < 700.dp -> 128.sp
                maxHeight < 760.dp -> 150.sp
                else -> 166.sp
            }
            val phaseFontSize = when {
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 620.dp -> 38.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 760.dp -> 42.sp
                uiState.timerMode == TimerMode.NormalCount -> 46.sp
                maxHeight < 620.dp -> 32.sp
                maxHeight < 760.dp -> 36.sp
                else -> 40.sp
            }
            val phaseLineHeight = when {
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 620.dp -> 42.sp
                uiState.timerMode == TimerMode.NormalCount && maxHeight < 760.dp -> 46.sp
                uiState.timerMode == TimerMode.NormalCount -> 50.sp
                maxHeight < 620.dp -> 36.sp
                maxHeight < 760.dp -> 40.sp
                else -> 44.sp
            }
            val roundTripFontSize = when {
                maxHeight < 620.dp -> 52.sp
                maxHeight < 700.dp -> 56.sp
                maxHeight < 760.dp -> 60.sp
                else -> 64.sp
            }
            val roundTripLineHeight = when {
                maxHeight < 620.dp -> 54.sp
                maxHeight < 700.dp -> 58.sp
                maxHeight < 760.dp -> 62.sp
                else -> 66.sp
            }
            val headerCountFontSize = when {
                maxHeight < 620.dp -> 34.sp
                maxHeight < 700.dp -> 38.sp
                maxHeight < 760.dp -> 42.sp
                else -> 46.sp
            }
            val headerCountLineHeight = when {
                maxHeight < 620.dp -> 38.sp
                maxHeight < 700.dp -> 42.sp
                maxHeight < 760.dp -> 46.sp
                else -> 50.sp
            }
            val countSectionSpacing = if (compactLayout) 2.dp else 4.dp
            val secondChipWidth = 72.dp
            val secondChipSpacing = 8.dp
            val secondsRowHorizontalPadding = maxOf(0.dp, (maxWidth - secondChipWidth) / 2)
            val phaseLabel = when {
                uiState.isPreparing -> stringResource(R.string.timer_phase_preparation)
                uiState.sessionStatus == TimerSessionStatus.Completed ->
                    stringResource(R.string.timer_phase_complete)

                uiState.timerMode == TimerMode.NormalCount ->
                    stringResource(R.string.timer_phase_normal_count)

                uiState.currentPhase == WorkoutPhase.Fast ->
                    stringResource(R.string.timer_phase_fast)

                else -> stringResource(R.string.timer_phase_slow)
            }
            val countColor = if (uiState.isPreparing) {
                PreparationCountColor
            } else {
                MaterialTheme.colorScheme.primary
            }
            val primaryButtonLabel = when {
                uiState.primaryButtonShowsStart -> stringResource(R.string.timer_action_start)
                uiState.primaryButtonShowsStop -> stringResource(R.string.timer_action_stop)
                else -> stringResource(R.string.timer_action_reset)
            }
            val secondaryButtonLabel = if (uiState.canResume) {
                stringResource(R.string.timer_action_resume)
            } else {
                stringResource(R.string.timer_action_pause)
            }
            val headerCountText = stringResource(R.string.round_trip_count, uiState.roundTripCount)

            LaunchedEffect(Unit) {
                while (
                    fastSecondListState.layoutInfo.viewportSize.width == 0 ||
                    slowSecondListState.layoutInfo.viewportSize.width == 0
                ) {
                    delay(16)
                }
                delay(100)
                if (latestUiState.timerMode == TimerMode.Motion) {
                    val fastStartupSelectedIndex = latestUiState.fastPhaseDurationSeconds - MIN_SECONDS
                    val slowStartupSelectedIndex = latestUiState.slowPhaseDurationSeconds - MIN_SECONDS
                    fastSecondListState.scrollToItem(fastStartupSelectedIndex)
                    slowSecondListState.scrollToItem(slowStartupSelectedIndex)
                }
                hasCenteredInitialSelection = true
            }

            LaunchedEffect(uiState.canChangeSeconds) {
                if (!uiState.canChangeSeconds) {
                    fastSecondListState.stopScroll()
                    slowSecondListState.stopScroll()
                }
            }

                Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 8.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        TimerModeTabs(
                            selectedMode = uiState.timerMode,
                            heartRate = heartRate,
                            enabled = uiState.canChangeTimerMode,
                            onModeSelected = onTimerModeSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 48.dp),
                        )
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.align(Alignment.TopEnd),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.timer_sound_settings_title),
                            )
                        }
                    }
                    if (uiState.timerMode == TimerMode.Motion) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = headerCountText,
                            fontSize = headerCountFontSize,
                            lineHeight = headerCountLineHeight,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            color = HeaderCountColor,
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = phaseFontSize,
                        lineHeight = phaseLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (uiState.isPreparing) {
                            PreparationCountColor
                        } else {
                            RunningInfoTextColor
                        },
                    )
                    if (uiState.sessionStatus == TimerSessionStatus.Completed) {
                        Spacer(modifier = Modifier.height(countSectionSpacing))
                        Text(
                            text = stringResource(
                                R.string.timer_completed_rounds,
                                if (uiState.timerMode == TimerMode.NormalCount) {
                                    uiState.normalCountMaxCount
                                } else {
                                    uiState.maxLoopCount
                                },
                            ),
                            fontSize = roundTripFontSize,
                            lineHeight = roundTripLineHeight,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                            color = RunningInfoTextColor,
                        )
                    } else {
                        Spacer(modifier = Modifier.height(countSectionSpacing))
                        Text(
                            text = uiState.displaySeconds.toString(),
                            fontSize = countFontSize,
                            lineHeight = countLineHeight,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                            color = countColor,
                        )
                    }
                }

                if (uiState.timerMode == TimerMode.Motion) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PhaseDurationOptionRow(
                            label = stringResource(R.string.fast_phase_duration_label),
                            selectedSeconds = uiState.fastPhaseDurationSeconds,
                            enabled = uiState.canChangeSeconds,
                            secondOptions = secondOptions,
                            listState = fastSecondListState,
                            secondsRowHorizontalPadding = secondsRowHorizontalPadding,
                            secondChipSpacing = secondChipSpacing,
                            secondChipWidth = secondChipWidth,
                            onSecondSelected = onFastPhaseDurationSelected,
                        )
                        PhaseDurationOptionRow(
                            label = stringResource(R.string.slow_phase_duration_label),
                            selectedSeconds = uiState.slowPhaseDurationSeconds,
                            enabled = uiState.canChangeSeconds,
                            secondOptions = secondOptions,
                            listState = slowSecondListState,
                            secondsRowHorizontalPadding = secondsRowHorizontalPadding,
                            secondChipSpacing = secondChipSpacing,
                            secondChipWidth = secondChipWidth,
                            onSecondSelected = onSlowPhaseDurationSelected,
                        )
                        if (!uiState.hasStarted) {
                            LoopCountSelectorRow(
                                label = stringResource(R.string.timer_loop_count_label),
                                selectedCount = uiState.maxLoopCount,
                                enabled = true,
                                onCountSelected = onMaxLoopCountChanged,
                            )
                        } else {
                            NormalCountTargetPanel(
                                targetCount = uiState.maxLoopCount,
                            )
                        }
                    }
                } else if (!uiState.hasStarted) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NormalCountIntervalSelectorRow(
                            selectedInterval = uiState.normalCountInterval,
                            enabled = true,
                            onIntervalSelected = onNormalCountIntervalSelected,
                        )
                        LoopCountSelectorRow(
                            label = stringResource(R.string.timer_loop_count_label),
                            selectedCount = uiState.normalCountMaxCount,
                            enabled = true,
                            onCountSelected = onNormalCountMaxCountChanged,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NormalCountIntervalSelectorRow(
                            selectedInterval = uiState.normalCountInterval,
                            enabled = false,
                            onIntervalSelected = onNormalCountIntervalSelected,
                        )
                        NormalCountTargetPanel(
                            targetCount = uiState.normalCountMaxCount,
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimerActionButton(
                            label = primaryButtonLabel,
                            onClick = onPrimaryAction,
                            enabled = true,
                            modifier = Modifier.weight(1f),
                        )
                        TimerActionButton(
                            label = secondaryButtonLabel,
                            onClick = onSecondaryAction,
                            enabled = uiState.secondaryButtonEnabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
                }
            }
        }
    }

    if (showSettingsDialog) {
        CountdownSoundSettingsDialog(
            uiState = uiState,
            heartRateDevices = heartRateDevices,
            heartRateConnectionState = heartRateConnectionState,
            heartRateError = heartRateError,
            savedHeartRateDevice = savedHeartRateDevice,
            hasBluetoothPermission = hasBluetoothPermission,
            onDismiss = { showSettingsDialog = false },
            onStartupBackgroundClick = { showStartupBackgroundDialog = true },
            onLoopChanged = onLoopChanged,
            onVibrationChanged = onVibrationChanged,
            onCountdownSoundChanged = onCountdownSoundChanged,
            onCountSoundModeChanged = onCountSoundModeChanged,
            onEarlyTickVolumeChanged = onEarlyTickVolumeChanged,
            onTickVolumeChanged = onTickVolumeChanged,
            onLoopCompleteVolumeChanged = onLoopCompleteVolumeChanged,
            onNormalVibrationLevelChanged = onNormalVibrationLevelChanged,
            onCompleteVibrationLevelChanged = onCompleteVibrationLevelChanged,
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onStartHeartRateScan = onStartHeartRateScan,
            onConnectHeartRateDevice = onConnectHeartRateDevice,
            onDisconnectHeartRateDevice = onDisconnectHeartRateDevice,
            onForgetHeartRateDevice = onForgetHeartRateDevice,
        )
    }

    if (showStartupBackgroundDialog) {
        StartupBackgroundSettingsDialog(
            initialScale = uiState.startupBackgroundScale,
            initialOffsetXPct = uiState.startupBackgroundOffsetXPct,
            initialOffsetYPct = uiState.startupBackgroundOffsetYPct,
            onDismiss = { showStartupBackgroundDialog = false },
            onSave = { scale, offsetXPct, offsetYPct ->
                onStartupBackgroundTransformSaved(scale, offsetXPct, offsetYPct)
                showStartupBackgroundDialog = false
            },
        )
    }
}

@Composable
private fun TimerModeTabs(
    selectedMode: TimerMode,
    heartRate: Int,
    enabled: Boolean,
    onModeSelected: (TimerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(TimerMode.Motion, TimerMode.NormalCount)
    TabRow(
        modifier = modifier,
        selectedTabIndex = modes.indexOf(selectedMode),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        modes.forEach { mode ->
            Tab(
                selected = selectedMode == mode,
                enabled = enabled || selectedMode == mode,
                onClick = {
                    if (enabled) {
                        onModeSelected(mode)
                    }
                },
                text = {
                    Text(
                        text = when (mode) {
                            TimerMode.Motion -> stringResource(
                                R.string.timer_mode_motion_with_heart_rate,
                                if (heartRate > 0) heartRate.toString() else "--",
                            )
                            TimerMode.NormalCount -> stringResource(R.string.timer_mode_normal_count)
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        }
    }
}

@Composable
private fun PhaseDurationOptionRow(
    label: String,
    selectedSeconds: Int,
    enabled: Boolean,
    secondOptions: List<Int>,
    listState: LazyListState,
    secondsRowHorizontalPadding: Dp,
    secondChipSpacing: Dp,
    secondChipWidth: Dp,
    onSecondSelected: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            userScrollEnabled = enabled,
            contentPadding = PaddingValues(horizontal = secondsRowHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(secondChipSpacing),
        ) {
            items(secondOptions) { second ->
                FilterChip(
                    selected = selectedSeconds == second,
                    onClick = { onSecondSelected(second) },
                    label = {
                        Text(
                            text = "${second}秒",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.width(secondChipWidth),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = enabled,
                        selected = selectedSeconds == second,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CountdownSoundSettingsDialog(
    uiState: WorkoutTimerUiState,
    heartRateDevices: List<HeartRateDevice>,
    heartRateConnectionState: HeartRateConnectionState,
    heartRateError: String?,
    savedHeartRateDevice: SavedHeartRateDevice?,
    hasBluetoothPermission: Boolean,
    onDismiss: () -> Unit,
    onStartupBackgroundClick: () -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onVibrationChanged: (Boolean) -> Unit,
    onCountdownSoundChanged: (Boolean) -> Unit,
    onCountSoundModeChanged: (CountSoundMode) -> Unit,
    onEarlyTickVolumeChanged: (Int) -> Unit,
    onTickVolumeChanged: (Int) -> Unit,
    onLoopCompleteVolumeChanged: (Int) -> Unit,
    onNormalVibrationLevelChanged: (Int) -> Unit,
    onCompleteVibrationLevelChanged: (Int) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onStartHeartRateScan: () -> Unit,
    onConnectHeartRateDevice: (String) -> Unit,
    onDisconnectHeartRateDevice: () -> Unit,
    onForgetHeartRateDevice: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints {
                val dialogHeight = (maxHeight * 0.85f).coerceAtMost(640.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dialogHeight)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.timer_sound_settings_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Text("閉じる")
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onStartupBackgroundClick,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(stringResource(R.string.timer_startup_background_button))
                        }
                        TimerToggleRow(
                            label = stringResource(R.string.timer_toggle_loop),
                            checked = uiState.loopEnabled,
                            onCheckedChange = onLoopChanged,
                        )
                        TimerToggleRow(
                            label = stringResource(R.string.timer_toggle_vibration),
                            checked = uiState.vibrationEnabled,
                            onCheckedChange = onVibrationChanged,
                        )
                        TimerToggleRow(
                            label = stringResource(R.string.timer_toggle_countdown_sound),
                            checked = uiState.countdownSoundEnabled,
                            onCheckedChange = onCountdownSoundChanged,
                        )
                        CountSoundModeSelectorRow(
                            selectedMode = uiState.countSoundMode,
                            onModeSelected = onCountSoundModeChanged,
                        )
                        CountdownVolumeSliderRow(
                            label = "早期ティック音量",
                            value = uiState.earlyTickVolume,
                            onValueChanged = onEarlyTickVolumeChanged,
                        )
                        CountdownVolumeSliderRow(
                            label = "通常ティック音量",
                            value = uiState.tickVolume,
                            onValueChanged = onTickVolumeChanged,
                        )
                        CountdownVolumeSliderRow(
                            label = "完了音量",
                            value = uiState.loopCompleteVolume,
                            onValueChanged = onLoopCompleteVolumeChanged,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.timer_sound_credit_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.timer_sound_credit_value),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "バイブ設定",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        VibrationLevelSelectorRow(
                            label = "通常バイブ強度",
                            selectedLevel = uiState.normalVibrationLevel,
                            onLevelSelected = onNormalVibrationLevelChanged,
                        )
                        VibrationLevelSelectorRow(
                            label = "完了バイブ強度",
                            selectedLevel = uiState.completeVibrationLevel,
                            onLevelSelected = onCompleteVibrationLevelChanged,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.heart_rate_device_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.heart_rate_connection_status,
                                heartRateConnectionState.label,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        heartRateError?.let { error ->
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!hasBluetoothPermission) {
                            Button(
                                onClick = onRequestBluetoothPermission,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.heart_rate_permission_button))
                            }
                        } else {
                            savedHeartRateDevice?.let { device ->
                                Text(
                                    text = device.name,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onStartHeartRateScan,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.heart_rate_scan_button))
                                }
                                if (heartRateConnectionState == HeartRateConnectionState.Connected ||
                                    heartRateConnectionState == HeartRateConnectionState.Connecting
                                ) {
                                    Button(
                                        onClick = onDisconnectHeartRateDevice,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.heart_rate_disconnect_button))
                                    }
                                }
                            }
                            if (savedHeartRateDevice != null) {
                                TextButton(onClick = onForgetHeartRateDevice) {
                                    Text(stringResource(R.string.heart_rate_forget_button))
                                }
                            }
                            if (heartRateConnectionState == HeartRateConnectionState.Scanning) {
                                Text(
                                    text = stringResource(R.string.heart_rate_detected_devices),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                heartRateDevices.forEach { device ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onConnectHeartRateDevice(device.address) }
                                            .padding(vertical = 8.dp),
                                    ) {
                                        Text(
                                            text = if (device.supportsHeartRate) {
                                                stringResource(
                                                    R.string.heart_rate_supported_device,
                                                    device.name,
                                                )
                                            } else {
                                                device.name
                                            },
                                            fontWeight = if (device.supportsHeartRate) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                        Text(
                                            text = "${device.address}  RSSI: ${device.rssi} dBm",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchLoadingScreen(
    scale: Float,
    offsetXPct: Float,
    offsetYPct: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)),
    ) {
        StartupBackgroundImage(
            modifier = Modifier.fillMaxSize(),
            scale = scale,
            offsetXPct = offsetXPct,
            offsetYPct = offsetYPct,
        )
        Text(
            text = stringResource(R.string.timer_loading_message),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .safeDrawingPadding(),
        )
    }
}

@Composable
private fun StartupBackgroundSettingsDialog(
    initialScale: Float,
    initialOffsetXPct: Float,
    initialOffsetYPct: Float,
    onDismiss: () -> Unit,
    onSave: (Float, Float, Float) -> Unit,
) {
    var scale by remember(initialScale) { mutableStateOf(initialScale) }
    var offsetXPct by remember(initialOffsetXPct) { mutableStateOf(initialOffsetXPct) }
    var offsetYPct by remember(initialOffsetYPct) { mutableStateOf(initialOffsetYPct) }
    var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            BoxWithConstraints {
                val dialogHeight = (maxHeight * 0.9f).coerceAtMost(720.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dialogHeight)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.timer_startup_background_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Text("閉じる")
                        }
                    }

                    Text(
                        text = stringResource(R.string.timer_startup_background_instruction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .graphicsLayer { clip = true }
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .onSizeChanged { previewSize = it }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(
                                        MIN_STARTUP_BACKGROUND_SCALE,
                                        MAX_STARTUP_BACKGROUND_SCALE,
                                    )
                                    val width = previewSize.width.coerceAtLeast(1).toFloat()
                                    val height = previewSize.height.coerceAtLeast(1).toFloat()
                                    offsetXPct = (offsetXPct + pan.x / width).coerceIn(
                                        MIN_STARTUP_BACKGROUND_OFFSET_PCT,
                                        MAX_STARTUP_BACKGROUND_OFFSET_PCT,
                                    )
                                    offsetYPct = (offsetYPct + pan.y / height).coerceIn(
                                        MIN_STARTUP_BACKGROUND_OFFSET_PCT,
                                        MAX_STARTUP_BACKGROUND_OFFSET_PCT,
                                    )
                                }
                            },
                    ) {
                        StartupBackgroundImage(
                            modifier = Modifier.fillMaxSize(),
                            scale = scale,
                            offsetXPct = offsetXPct,
                            offsetYPct = offsetYPct,
                        )
                        Text(
                            text = stringResource(
                                R.string.timer_startup_background_preview_scale,
                                scale,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                scale = DEFAULT_STARTUP_BACKGROUND_SCALE
                                offsetXPct = DEFAULT_STARTUP_BACKGROUND_OFFSET_X_PCT
                                offsetYPct = DEFAULT_STARTUP_BACKGROUND_OFFSET_Y_PCT
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.timer_startup_background_reset))
                        }
                        Button(
                            onClick = { onSave(scale, offsetXPct, offsetYPct) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(stringResource(R.string.timer_startup_background_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupBackgroundImage(
    modifier: Modifier = Modifier,
    scale: Float,
    offsetXPct: Float,
    offsetYPct: Float,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val translationXPx = with(density) { maxWidth.toPx() * offsetXPct }
        val translationYPx = with(density) { maxHeight.toPx() * offsetYPct }
        Image(
            painter = painterResource(id = R.drawable.splash_background_optimized),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomEnd,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translationXPx
                    translationY = translationYPx
                },
        )
    }
}

@Composable
private fun LoopCountSelectorRow(
    label: String,
    selectedCount: Int,
    enabled: Boolean,
    onCountSelected: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 88.dp),
            )
            Slider(
                modifier = Modifier.weight(1f),
                value = selectedCount.toFloat(),
                onValueChange = {
                    val snappedValue = (it / LOOP_COUNT_STEP.toFloat()).roundToInt() * LOOP_COUNT_STEP
                    onCountSelected(snappedValue)
                },
                valueRange = MIN_LOOP_COUNT.toFloat()..MAX_LOOP_COUNT.toFloat(),
                steps = (MAX_LOOP_COUNT - MIN_LOOP_COUNT) / LOOP_COUNT_STEP - 1,
                enabled = enabled,
            )
            Text(
                text = "$selectedCount 回",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun NormalCountTargetPanel(
    targetCount: Int,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.normal_count_target_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.normal_count_limit_value, targetCount),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun NormalCountIntervalSelectorRow(
    selectedInterval: NormalCountInterval,
    enabled: Boolean,
    onIntervalSelected: (NormalCountInterval) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.normal_count_interval_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NormalCountInterval.entries.forEach { interval ->
                    FilterChip(
                        selected = selectedInterval == interval,
                        onClick = { onIntervalSelected(interval) },
                        enabled = enabled,
                        label = {
                            Text(
                                text = when (interval) {
                                    NormalCountInterval.Small -> stringResource(R.string.normal_count_interval_small)
                                    NormalCountInterval.Medium -> stringResource(R.string.normal_count_interval_medium)
                                    NormalCountInterval.Large -> stringResource(R.string.normal_count_interval_large)
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = enabled,
                            selected = selectedInterval == interval,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountSoundModeSelectorRow(
    selectedMode: CountSoundMode,
    onModeSelected: (CountSoundMode) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.timer_sound_mode_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedMode == CountSoundMode.Beep,
                onClick = { onModeSelected(CountSoundMode.Beep) },
                label = { Text("ピ音") },
                modifier = Modifier.weight(1f),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedMode == CountSoundMode.Beep,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            FilterChip(
                selected = selectedMode == CountSoundMode.Voice,
                onClick = { onModeSelected(CountSoundMode.Voice) },
                label = { Text("音声") },
                modifier = Modifier.weight(1f),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedMode == CountSoundMode.Voice,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun VibrationLevelSelectorRow(
    label: String,
    selectedLevel: Int,
    onLevelSelected: (Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "現在 $selectedLevel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (MIN_VIBRATION_LEVEL..MAX_VIBRATION_LEVEL).forEach { level ->
                FilterChip(
                    selected = selectedLevel == level,
                    onClick = { onLevelSelected(level) },
                    label = { Text(level.toString()) },
                    modifier = Modifier.weight(1f),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedLevel == level,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CountdownVolumeSliderRow(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChanged(it.toInt()) },
            valueRange = MIN_CUE_VOLUME.toFloat()..MAX_CUE_VOLUME.toFloat(),
            steps = MAX_CUE_VOLUME - MIN_CUE_VOLUME - 1,
        )
    }
}

@Composable
private fun TimerActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors(),
    border: BorderStroke? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RoundedCornerShape(18.dp),
        colors = colors,
        border = border,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun TimerToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

private fun vibrate(
    context: Context,
    event: VibrationEvent,
    normalVibrationLevel: Int,
    completeVibrationLevel: Int,
) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    } ?: return

    if (!vibrator.hasVibrator()) return

    val clampedNormalLevel = normalVibrationLevel.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL)
    val clampedCompleteLevel = completeVibrationLevel.coerceIn(MIN_VIBRATION_LEVEL, MAX_VIBRATION_LEVEL)
    val tickDuration = when (clampedNormalLevel) {
        1 -> 25L
        2 -> 45L
        3 -> 65L
        else -> 85L
    }
    val completePattern = when (clampedCompleteLevel) {
        1 -> longArrayOf(0L, 45L, 65L, 70L)
        2 -> longArrayOf(0L, 70L, 45L, 120L)
        3 -> longArrayOf(0L, 90L, 40L, 150L)
        else -> longArrayOf(0L, 110L, 35L, 180L)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = when (event) {
            VibrationEvent.Tick -> {
                VibrationEffect.createOneShot(tickDuration, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            VibrationEvent.LoopComplete -> {
                VibrationEffect.createWaveform(completePattern, -1)
            }
        }
        vibrator.vibrate(effect)
        return
    }

    when (event) {
        VibrationEvent.Tick -> vibrator.vibrate(tickDuration)
        VibrationEvent.LoopComplete -> vibrator.vibrate(completePattern, -1)
    }
}

private fun timerBackgroundColor(
    uiState: WorkoutTimerUiState,
    idleBackgroundColor: Color,
): Color {
    if (uiState.sessionStatus == TimerSessionStatus.Completed) {
        return ProgressCompleteBackground
    }
    if (!uiState.hasStarted || uiState.isPreparing) {
        return idleBackgroundColor
    }

    if (uiState.timerMode == TimerMode.NormalCount) {
        val configuredCount = uiState.normalCountMaxCount.coerceAtLeast(1)
        val currentCount = uiState.normalCount.coerceIn(1, configuredCount)
        val overallProgress = if (configuredCount == 1) {
            0f
        } else {
            (currentCount - 1).toFloat() / (configuredCount - 1).toFloat()
        }
        return progressPaletteColor(overallProgress)
    }

    if (!uiState.loopEnabled) {
        return ProgressGreenBackground
    }

    val configuredLoopCount = uiState.maxLoopCount.coerceAtLeast(1)
    val currentLoopIndex = uiState.roundTripCount.coerceIn(1, configuredLoopCount)
    val overallProgress = if (configuredLoopCount == 1) {
        0f
    } else {
        (currentLoopIndex - 1).toFloat() / (configuredLoopCount - 1).toFloat()
    }

    return progressPaletteColor(overallProgress)
}

private fun progressPaletteColor(progress: Float): Color {
    val clampedProgress = progress.coerceIn(0f, 1f)

    return when {
        clampedProgress < 0.33f -> {
            val localProgress = clampedProgress / 0.33f
            lerp(ProgressGreenBackground, ProgressWarmLowBackground, localProgress)
        }
        clampedProgress < 0.66f -> {
            val localProgress = (clampedProgress - 0.33f) / 0.33f
            lerp(ProgressWarmLowBackground, ProgressWarmMidBackground, localProgress)
        }
        else -> {
            val localProgress = (clampedProgress - 0.66f) / 0.34f
            lerp(ProgressWarmMidBackground, ProgressWarmHighBackground, localProgress)
        }
    }
}

private const val LOOP_COUNT_STEP = 5
