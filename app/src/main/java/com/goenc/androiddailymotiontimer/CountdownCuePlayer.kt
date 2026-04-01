package com.goenc.androiddailymotiontimer

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class CountdownCuePlayer {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingToneStarts = mutableListOf<Runnable>()
    private var earlyTickVolume = DEFAULT_EARLY_TICK_VOLUME
    private var tickVolume = DEFAULT_TICK_VOLUME
    private var loopCompleteVolume = DEFAULT_LOOP_COMPLETE_VOLUME
    private var earlyToneGenerator = createToneGenerator(earlyTickVolume)
    private var tickToneGenerator = createToneGenerator(tickVolume)
    private var loopCompleteToneGenerator = createToneGenerator(loopCompleteVolume)

    fun playEarlySingleCue() {
        stop()
        earlyToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, SINGLE_TONE_DURATION_MS)
    }

    fun playSingleCue() {
        stop()
        tickToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, SINGLE_TONE_DURATION_MS)
    }

    fun playDoubleCue() {
        stop()
        startTone(loopCompleteToneGenerator, DOUBLE_TONE_DURATION_MS, 0L)
        startTone(loopCompleteToneGenerator, DOUBLE_TONE_DURATION_MS, DOUBLE_TONE_DELAY_MS)
    }

    fun setEarlyTickVolume(value: Int) {
        val normalized = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
        if (earlyTickVolume == normalized) return
        stop()
        earlyToneGenerator.release()
        earlyTickVolume = normalized
        earlyToneGenerator = createToneGenerator(earlyTickVolume)
    }

    fun setTickVolume(value: Int) {
        val normalized = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
        if (tickVolume == normalized) return
        stop()
        tickToneGenerator.release()
        tickVolume = normalized
        tickToneGenerator = createToneGenerator(tickVolume)
    }

    fun setLoopCompleteVolume(value: Int) {
        val normalized = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
        if (loopCompleteVolume == normalized) return
        stop()
        loopCompleteToneGenerator.release()
        loopCompleteVolume = normalized
        loopCompleteToneGenerator = createToneGenerator(loopCompleteVolume)
    }

    fun stop() {
        pendingToneStarts.forEach(handler::removeCallbacks)
        pendingToneStarts.clear()
        earlyToneGenerator.stopTone()
        tickToneGenerator.stopTone()
        loopCompleteToneGenerator.stopTone()
    }

    fun release() {
        stop()
        earlyToneGenerator.release()
        tickToneGenerator.release()
        loopCompleteToneGenerator.release()
    }

    private fun startTone(generator: ToneGenerator, durationMs: Int, delayMs: Long) {
        val action = Runnable {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        }
        pendingToneStarts += action
        if (delayMs == 0L) {
            action.run()
        } else {
            handler.postDelayed(action, delayMs)
        }
    }

    private fun createToneGenerator(volume: Int): ToneGenerator {
        return ToneGenerator(AudioManager.STREAM_ALARM, volume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME))
    }

    private companion object {
        private const val SINGLE_TONE_DURATION_MS = 120
        private const val DOUBLE_TONE_DURATION_MS = 90
        private const val DOUBLE_TONE_DELAY_MS = 150L
    }
}
