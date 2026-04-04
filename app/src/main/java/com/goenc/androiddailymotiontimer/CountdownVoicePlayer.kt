package com.goenc.androiddailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class CountdownVoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
        )
        .build()
    private val soundIds = mutableMapOf<Int, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private var textToSpeech: TextToSpeech? = null
    private var activeStreamId: Int? = null
    private var pendingPlayback: PendingPlayback? = null
    private var textToSpeechReady = false
    private var pendingPhaseSpeech: PhaseSpeech? = null
    private var earlyTickVolume = DEFAULT_EARLY_TICK_VOLUME
    private var tickVolume = DEFAULT_TICK_VOLUME
    private var loopCompleteVolume = DEFAULT_LOOP_COMPLETE_VOLUME

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            val tts = textToSpeech ?: return@TextToSpeech
            textToSpeechReady = status == TextToSpeech.SUCCESS
            if (textToSpeechReady) {
                tts.language = Locale.JAPAN
                pendingPhaseSpeech?.let { pendingSpeech ->
                    pendingPhaseSpeech = null
                    speakPhaseCue(pendingSpeech)
                }
            } else {
                pendingPhaseSpeech = null
                Log.w(TAG, "Failed to initialize TextToSpeech status=$status")
            }
        }
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedSoundIds += soundId
            val queuedPlayback = pendingPlayback ?: return@setOnLoadCompleteListener
            if (soundIds[queuedPlayback.count] == soundId) {
                pendingPlayback = null
                playLoadedSound(soundId, queuedPlayback.cueType)
            }
        }
        COUNT_RESOURCE_IDS.forEach { (count, resId) ->
            soundIds[count] = soundPool.load(appContext, resId, 1)
        }
    }

    fun playCount(
        count: Int,
        cueType: CountdownCueType,
        voicePhase: WorkoutPhase? = null,
    ) {
        if (voicePhase != null) {
            pendingPlayback = null
            speakPhaseCue(
                PhaseSpeech(
                    count = count,
                    cueType = cueType,
                    voicePhase = voicePhase,
                )
            )
            return
        }

        val soundId = soundIds[count] ?: return
        pendingPhaseSpeech = null
        stopTextToSpeech()
        if (loadedSoundIds.contains(soundId)) {
            pendingPlayback = null
            playLoadedSound(soundId, cueType)
        } else {
            stopActivePlayback()
            pendingPlayback = PendingPlayback(count = count, cueType = cueType)
        }
    }

    fun setEarlyTickVolume(value: Int) {
        earlyTickVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
    }

    fun setTickVolume(value: Int) {
        tickVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
    }

    fun setLoopCompleteVolume(value: Int) {
        loopCompleteVolume = value.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME)
    }

    fun stop() {
        pendingPlayback = null
        pendingPhaseSpeech = null
        stopTextToSpeech()
        stopActivePlayback()
    }

    fun release() {
        stop()
        textToSpeech?.shutdown()
        soundPool.release()
    }

    private fun stopActivePlayback() {
        activeStreamId?.let(soundPool::stop)
        activeStreamId = null
    }

    private fun stopTextToSpeech() {
        textToSpeech?.stop()
    }

    private fun playLoadedSound(soundId: Int, cueType: CountdownCueType) {
        stopActivePlayback()
        val volume = resolveVolume(cueType)
        if (volume <= 0f) return
        val streamId = soundPool.play(soundId, volume, volume, 1, 0, 1f)
        if (streamId == 0) {
            Log.w(TAG, "Failed to play countdown voice for soundId=$soundId")
            activeStreamId = null
            return
        }
        activeStreamId = streamId
    }

    private fun resolveVolume(cueType: CountdownCueType): Float {
        val volume = when (cueType) {
            CountdownCueType.EarlyTick -> earlyTickVolume
            CountdownCueType.Tick -> tickVolume
            CountdownCueType.LoopComplete -> loopCompleteVolume
        }
        return volume.coerceIn(MIN_CUE_VOLUME, MAX_CUE_VOLUME) / MAX_CUE_VOLUME.toFloat()
    }

    private fun speakPhaseCue(phaseSpeech: PhaseSpeech) {
        stopActivePlayback()
        stopTextToSpeech()
        if (!textToSpeechReady) {
            pendingPhaseSpeech = phaseSpeech
            return
        }
        val tts = textToSpeech ?: return

        val phaseLabel = when (phaseSpeech.voicePhase) {
            WorkoutPhase.Fast -> appContext.getString(R.string.timer_phase_fast)
            WorkoutPhase.Slow -> appContext.getString(R.string.timer_phase_slow)
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, resolveVolume(phaseSpeech.cueType))
        }
        val speakText = "${phaseSpeech.count}$phaseLabel"
        val status = tts.speak(
            speakText,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "${phaseSpeech.count}-${phaseSpeech.voicePhase.name}",
        )
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Failed to speak countdown voice text=$speakText")
        }
    }

    private companion object {
        private const val TAG = "CountdownVoicePlayer"
        private val COUNT_RESOURCE_IDS = mapOf(
            10 to R.raw.count_10,
            9 to R.raw.count_9,
            8 to R.raw.count_8,
            7 to R.raw.count_7,
            6 to R.raw.count_6,
            5 to R.raw.count_5,
            4 to R.raw.count_4,
            3 to R.raw.count_3,
            2 to R.raw.count_2,
            1 to R.raw.count_1,
            0 to R.raw.count_0,
        )
    }

    private data class PendingPlayback(
        val count: Int,
        val cueType: CountdownCueType,
    )

    private data class PhaseSpeech(
        val count: Int,
        val cueType: CountdownCueType,
        val voicePhase: WorkoutPhase,
    )
}
