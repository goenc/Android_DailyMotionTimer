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
    private var pendingCountSpeech: CountSpeech? = null
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
                    speakPhaseCueNow(pendingSpeech)
                }
                pendingCountSpeech?.let { pendingSpeech ->
                    pendingCountSpeech = null
                    speakCountCueNow(pendingSpeech)
                }
            } else {
                pendingPhaseSpeech = null
                pendingCountSpeech = null
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
        voiceRoundTripCount: Int? = null,
    ) {
        if (voicePhase != null) {
            pendingPlayback = null
            speakPhaseCue(
                PhaseSpeech(
                    count = count,
                    cueType = cueType,
                    voicePhase = voicePhase,
                    voiceRoundTripCount = voiceRoundTripCount,
                )
            )
            return
        }

        if (count >= 11) {
            pendingPlayback = null
            speakCountCue(CountSpeech(count = count, cueType = cueType))
            return
        }

        val soundId = soundIds[count] ?: return
        pendingPhaseSpeech = null
        pendingCountSpeech = null
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
        pendingCountSpeech = null
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
        if (!textToSpeechReady) {
            pendingCountSpeech = null
            pendingPhaseSpeech = phaseSpeech
            return
        }
        pendingPhaseSpeech = null
        // QUEUE_FLUSH already replaces the current utterance, so avoid an extra stop() here.
        speakPhaseCueNow(phaseSpeech)
    }

    private fun speakCountCue(countSpeech: CountSpeech) {
        stopActivePlayback()
        if (!textToSpeechReady) {
            pendingPhaseSpeech = null
            pendingCountSpeech = countSpeech
            return
        }
        pendingCountSpeech = null
        speakCountCueNow(countSpeech)
    }

    private fun speakPhaseCueNow(phaseSpeech: PhaseSpeech) {
        val tts = textToSpeech ?: return

        val speakText = when (phaseSpeech.voicePhase) {
            WorkoutPhase.Fast -> phaseSpeech.voiceRoundTripCount?.let { "${it}回" }
                ?: appContext.getString(R.string.timer_phase_fast)
            WorkoutPhase.Slow -> appContext.getString(R.string.timer_phase_slow)
        }
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, resolveVolume(phaseSpeech.cueType))
        }
        val status = tts.speak(
            speakText,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "${phaseSpeech.count}-${phaseSpeech.voicePhase.name}-${phaseSpeech.voiceRoundTripCount ?: 0}",
        )
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Failed to speak countdown voice text=$speakText")
        }
    }

    private fun speakCountCueNow(countSpeech: CountSpeech) {
        val tts = textToSpeech ?: return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, resolveVolume(countSpeech.cueType))
        }
        val status = tts.speak(
            countSpeech.count.toString(),
            TextToSpeech.QUEUE_FLUSH,
            params,
            "count-${countSpeech.count}",
        )
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Failed to speak countdown count=${countSpeech.count}")
        }
    }

    private companion object {
        private const val TAG = "CountdownVoicePlayer"
        private val COUNT_RESOURCE_IDS = mapOf(
            50 to R.raw.count_50,
            49 to R.raw.count_49,
            48 to R.raw.count_48,
            47 to R.raw.count_47,
            46 to R.raw.count_46,
            45 to R.raw.count_45,
            44 to R.raw.count_44,
            43 to R.raw.count_43,
            42 to R.raw.count_42,
            41 to R.raw.count_41,
            40 to R.raw.count_40,
            39 to R.raw.count_39,
            38 to R.raw.count_38,
            37 to R.raw.count_37,
            36 to R.raw.count_36,
            35 to R.raw.count_35,
            34 to R.raw.count_34,
            33 to R.raw.count_33,
            32 to R.raw.count_32,
            31 to R.raw.count_31,
            30 to R.raw.count_30,
            29 to R.raw.count_29,
            28 to R.raw.count_28,
            27 to R.raw.count_27,
            26 to R.raw.count_26,
            25 to R.raw.count_25,
            24 to R.raw.count_24,
            23 to R.raw.count_23,
            22 to R.raw.count_22,
            21 to R.raw.count_21,
            20 to R.raw.count_20,
            19 to R.raw.count_19,
            18 to R.raw.count_18,
            17 to R.raw.count_17,
            16 to R.raw.count_16,
            15 to R.raw.count_15,
            14 to R.raw.count_14,
            13 to R.raw.count_13,
            12 to R.raw.count_12,
            11 to R.raw.count_11,
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
        val voiceRoundTripCount: Int?,
    )

    private data class CountSpeech(
        val count: Int,
        val cueType: CountdownCueType,
    )
}
