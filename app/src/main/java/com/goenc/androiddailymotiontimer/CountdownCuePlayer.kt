package com.goenc.androiddailymotiontimer

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

class CountdownCuePlayer {
    private val handler = Handler(Looper.getMainLooper())
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME)
    private val pendingStops = mutableListOf<Runnable>()

    fun playSingleCue() {
        stop()
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, SINGLE_TONE_DURATION_MS)
    }

    fun playDoubleCue() {
        stop()
        startTone(DOUBLE_TONE_DURATION_MS, 0L)
        startTone(DOUBLE_TONE_DURATION_MS, DOUBLE_TONE_DELAY_MS)
    }

    fun stop() {
        pendingStops.forEach(handler::removeCallbacks)
        pendingStops.clear()
        toneGenerator.stopTone()
    }

    fun release() {
        stop()
        toneGenerator.release()
    }

    private fun startTone(durationMs: Int, delayMs: Long) {
        val action = Runnable {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        }
        pendingStops += action
        if (delayMs == 0L) {
            action.run()
        } else {
            handler.postDelayed(action, delayMs)
        }
    }

    private companion object {
        private const val TONE_VOLUME = 80
        private const val SINGLE_TONE_DURATION_MS = 120
        private const val DOUBLE_TONE_DURATION_MS = 90
        private const val DOUBLE_TONE_DELAY_MS = 150L
    }
}
