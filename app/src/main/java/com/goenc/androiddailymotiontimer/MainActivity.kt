package com.goenc.androiddailymotiontimer

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import com.goenc.androiddailymotiontimer.ui.theme.AndroidDailyMotionTimerTheme
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: WorkoutSecondTimerViewModel
    private val countdownCuePlayer = CountdownCuePlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        timerViewModel = ViewModelProvider(this)[WorkoutSecondTimerViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            AndroidDailyMotionTimerTheme {
                val uiState by timerViewModel.uiState.collectAsState()
                WorkoutSecondTimerScreen(
                    uiState = uiState,
                    vibrationEvents = timerViewModel.vibrationEvents,
                    countdownSoundEvents = timerViewModel.countdownSoundEvents,
                    onSecondSelected = timerViewModel::setSelectedSeconds,
                    onLoopChanged = timerViewModel::setLoopEnabled,
                    onTickVibrationChanged = timerViewModel::setTickVibrationEnabled,
                    onLoopVibrationChanged = timerViewModel::setLoopVibrationEnabled,
                    onNormalVibrationLevelChanged = timerViewModel::setNormalVibrationLevel,
                    onCompleteVibrationLevelChanged = timerViewModel::setCompleteVibrationLevel,
                    onCountdownSoundChanged = timerViewModel::setCountdownSoundEnabled,
                    onEarlyTickVolumeChanged = timerViewModel::setEarlyTickVolume,
                    onTickVolumeChanged = timerViewModel::setTickVolume,
                    onLoopCompleteVolumeChanged = timerViewModel::setLoopCompleteVolume,
                    onStart = timerViewModel::start,
                    onPause = timerViewModel::pause,
                    countdownCuePlayer = countdownCuePlayer,
                )
            }
        }
    }

    override fun onDestroy() {
        countdownCuePlayer.release()
        super.onDestroy()
    }
}

@Composable
private fun WorkoutSecondTimerScreen(
    uiState: WorkoutTimerUiState,
    vibrationEvents: SharedFlow<VibrationEvent>,
    countdownSoundEvents: SharedFlow<CountdownSoundEvent>,
    onSecondSelected: (Int) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onTickVibrationChanged: (Boolean) -> Unit,
    onLoopVibrationChanged: (Boolean) -> Unit,
    onNormalVibrationLevelChanged: (Int) -> Unit,
    onCompleteVibrationLevelChanged: (Int) -> Unit,
    onCountdownSoundChanged: (Boolean) -> Unit,
    onEarlyTickVolumeChanged: (Int) -> Unit,
    onTickVolumeChanged: (Int) -> Unit,
    onLoopCompleteVolumeChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    countdownCuePlayer: CountdownCuePlayer,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val secondOptions = (MIN_SECONDS..MAX_SECONDS).toList()
    val secondListState = rememberLazyListState()
    var hasCenteredInitialSelection by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val latestUiState by rememberUpdatedState(uiState)

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

    LaunchedEffect(countdownSoundEvents, countdownCuePlayer) {
        countdownSoundEvents.collectLatest { event ->
            when (event) {
                CountdownSoundEvent.EarlyTick -> countdownCuePlayer.playEarlySingleCue()
                CountdownSoundEvent.Tick -> countdownCuePlayer.playSingleCue()
                CountdownSoundEvent.LoopComplete -> countdownCuePlayer.playDoubleCue()
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
    }

    DisposableEffect(countdownCuePlayer) {
        onDispose {
            countdownCuePlayer.stop()
        }
    }

    LaunchedEffect(uiState.isRunning) {
        if (!uiState.isRunning) {
            countdownCuePlayer.stop()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant,
                    )
                )
            ),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val countFontSize = if (maxHeight < 700.dp) 178.sp else 196.sp
            val countLineHeight = if (maxHeight < 700.dp) 168.sp else 186.sp
            val secondChipWidth = 72.dp
            val secondChipSpacing = 8.dp
            val secondsRowHorizontalPadding = maxOf(0.dp, (maxWidth - secondChipWidth) / 2)
            val selectedIndex = uiState.selectedSeconds - MIN_SECONDS

            LaunchedEffect(uiState.selectedSeconds) {
                if (hasCenteredInitialSelection) {
                    secondListState.animateScrollToItem(selectedIndex)
                } else {
                    secondListState.scrollToItem(selectedIndex)
                    hasCenteredInitialSelection = true
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "筋トレ秒",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "カウント音設定",
                            )
                        }
                    }
                    Text(
                        text = "累計経過 ${uiState.elapsedTimeText}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = uiState.remainingSeconds.toString(),
                        fontSize = countFontSize,
                        lineHeight = countLineHeight,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        state = secondListState,
                        contentPadding = PaddingValues(horizontal = secondsRowHorizontalPadding),
                        horizontalArrangement = Arrangement.spacedBy(secondChipSpacing),
                    ) {
                        items(secondOptions) { second ->
                            FilterChip(
                                selected = uiState.selectedSeconds == second,
                                onClick = { onSecondSelected(second) },
                                label = { Text("${second}秒") },
                                enabled = !uiState.isRunning,
                                modifier = Modifier.width(secondChipWidth),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = !uiState.isRunning,
                                    selected = uiState.selectedSeconds == second,
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

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimerToggleRow(
                        label = "ループ",
                        checked = uiState.loopEnabled,
                        onCheckedChange = onLoopChanged,
                    )
                    TimerToggleRow(
                        label = "毎秒バイブ",
                        checked = uiState.tickVibrationEnabled,
                        onCheckedChange = onTickVibrationChanged,
                    )
                    TimerToggleRow(
                        label = "ループ完了バイブ",
                        checked = uiState.loopVibrationEnabled,
                        onCheckedChange = onLoopVibrationChanged,
                    )
                    TimerToggleRow(
                        label = "カウント音",
                        checked = uiState.countdownSoundEnabled,
                        onCheckedChange = onCountdownSoundChanged,
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimerActionButton(
                            label = "開始",
                            onClick = onStart,
                            enabled = !uiState.isRunning,
                            modifier = Modifier.weight(1f),
                        )
                        TimerActionButton(
                            label = "一時停止",
                            onClick = onPause,
                            enabled = uiState.isRunning,
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

    if (showSettingsDialog) {
        CountdownSoundSettingsDialog(
            uiState = uiState,
            onDismiss = { showSettingsDialog = false },
            onEarlyTickVolumeChanged = onEarlyTickVolumeChanged,
            onTickVolumeChanged = onTickVolumeChanged,
            onLoopCompleteVolumeChanged = onLoopCompleteVolumeChanged,
            onNormalVibrationLevelChanged = onNormalVibrationLevelChanged,
            onCompleteVibrationLevelChanged = onCompleteVibrationLevelChanged,
        )
    }
}

@Composable
private fun CountdownSoundSettingsDialog(
    uiState: WorkoutTimerUiState,
    onDismiss: () -> Unit,
    onEarlyTickVolumeChanged: (Int) -> Unit,
    onTickVolumeChanged: (Int) -> Unit,
    onLoopCompleteVolumeChanged: (Int) -> Unit,
    onNormalVibrationLevelChanged: (Int) -> Unit,
    onCompleteVibrationLevelChanged: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "カウント音設定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("閉じる")
                    }
                }
            }
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
