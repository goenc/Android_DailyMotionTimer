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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.goenc.androiddailymotiontimer.ui.theme.AndroidDailyMotionTimerTheme
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private lateinit var timerViewModel: WorkoutSecondTimerViewModel

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
                    onSecondSelected = timerViewModel::setSelectedSeconds,
                    onLoopChanged = timerViewModel::setLoopEnabled,
                    onTickVibrationChanged = timerViewModel::setTickVibrationEnabled,
                    onLoopVibrationChanged = timerViewModel::setLoopVibrationEnabled,
                    onStart = timerViewModel::start,
                    onPause = timerViewModel::pause,
                    onReset = timerViewModel::reset,
                )
            }
        }
    }
}

@Composable
private fun WorkoutSecondTimerScreen(
    uiState: WorkoutTimerUiState,
    vibrationEvents: SharedFlow<VibrationEvent>,
    onSecondSelected: (Int) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onTickVibrationChanged: (Boolean) -> Unit,
    onLoopVibrationChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    DisposableEffect(view, uiState.isRunning) {
        view.keepScreenOn = uiState.isRunning
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(vibrationEvents, context) {
        vibrationEvents.collectLatest { event ->
            vibrate(context, event)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "筋トレ秒",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "累計経過 ${uiState.elapsedTimeText}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.remainingSeconds.toString(),
                    fontSize = 164.sp,
                    lineHeight = 164.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..10).forEach { second ->
                        FilterChip(
                            selected = uiState.selectedSeconds == second,
                            onClick = { onSecondSelected(second) },
                            label = { Text("${second}秒") },
                            enabled = !uiState.isRunning,
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onStart,
                        enabled = !uiState.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text("開始")
                    }
                    Button(
                        onClick = onPause,
                        enabled = uiState.isRunning,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("一時停止")
                    }
                    Button(
                        onClick = onReset,
                        enabled = uiState.isRunning ||
                            uiState.remainingSeconds != uiState.selectedSeconds ||
                            uiState.elapsedTimeText != "00:00",
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text("リセット")
                    }
                }
            }
        }
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
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

private fun vibrate(context: Context, event: VibrationEvent) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    } ?: return

    if (!vibrator.hasVibrator()) return

    val effect = when (event) {
        VibrationEvent.Tick -> {
            VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        VibrationEvent.LoopComplete -> {
            VibrationEffect.createWaveform(longArrayOf(0L, 70L, 45L, 120L), -1)
        }
    }

    vibrator.vibrate(effect)
}
