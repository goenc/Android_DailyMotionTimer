package com.goenc.androiddailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CountdownVoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
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
    private val motionSoundIds = mutableMapOf<Int, Int>()
    private val normalCountSoundIds = mutableMapOf<Int, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private var textToSpeech: TextToSpeech? = null
    private var activeStreamId: Int? = null
    private var pendingPlayback: PendingPlayback? = null
    private var textToSpeechReady = false
    private var pendingPhaseSpeech: PhaseSpeech? = null
    private var activePhaseUtteranceId: String? = null
    private var earlyTickVolume = DEFAULT_EARLY_TICK_VOLUME
    private var tickVolume = DEFAULT_TICK_VOLUME
    private var loopCompleteVolume = DEFAULT_LOOP_COMPLETE_VOLUME
    private val compositeVoiceDir = File(appContext.cacheDir, "countdown_voice")
    private val baseClips by lazy { loadBaseClips() }

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            val tts = textToSpeech ?: return@TextToSpeech
            textToSpeechReady = status == TextToSpeech.SUCCESS
            if (textToSpeechReady) {
                tts.language = Locale.JAPAN
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            handlePhaseSpeechFinished(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handlePhaseSpeechFinished(utteranceId)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handlePhaseSpeechFinished(utteranceId)
                        }
                    }
                )
                pendingPhaseSpeech?.let { pendingSpeech ->
                    pendingPhaseSpeech = null
                    speakPhaseCueNow(pendingSpeech)
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
            if (resolveSoundId(queuedPlayback.count, queuedPlayback.isNormalCountMode) == soundId) {
                pendingPlayback = null
                playLoadedSound(soundId, queuedPlayback.cueType)
            }
        }
        MOTION_COUNT_RESOURCE_IDS.forEach { (count, resId) ->
            motionSoundIds[count] = soundPool.load(appContext, resId, 1)
        }
        NORMAL_COUNT_RESOURCE_IDS.forEach { (count, resId) ->
            normalCountSoundIds[count] = soundPool.load(appContext, resId, 1)
        }
    }

    fun playCount(
        count: Int,
        cueType: CountdownCueType,
        isNormalCountMode: Boolean,
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

        if (!isNormalCountMode && count >= 11) {
            ensureCompositeSoundLoaded(count)
        }

        val soundId = resolveSoundId(count, isNormalCountMode) ?: return
        pendingPhaseSpeech = null
        if (activePhaseUtteranceId != null) {
            pendingPlayback = PendingPlayback(
                count = count,
                cueType = cueType,
                isNormalCountMode = isNormalCountMode,
            )
            return
        }
        if (loadedSoundIds.contains(soundId)) {
            pendingPlayback = null
            playLoadedSound(soundId, cueType)
        } else {
            stopActivePlayback()
            pendingPlayback = PendingPlayback(
                count = count,
                cueType = cueType,
                isNormalCountMode = isNormalCountMode,
            )
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
        activePhaseUtteranceId = null
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

    private fun handlePhaseSpeechFinished(utteranceId: String?) {
        mainHandler.post {
            if (utteranceId != activePhaseUtteranceId) return@post
            activePhaseUtteranceId = null
            val queuedPlayback = pendingPlayback ?: return@post
            val soundId = resolveSoundId(
                queuedPlayback.count,
                queuedPlayback.isNormalCountMode,
            ) ?: run {
                pendingPlayback = null
                return@post
            }
            if (loadedSoundIds.contains(soundId)) {
                pendingPlayback = null
                playLoadedSound(soundId, queuedPlayback.cueType)
            }
        }
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
            pendingPhaseSpeech = phaseSpeech
            return
        }
        pendingPhaseSpeech = null
        // QUEUE_FLUSH already replaces the current utterance, so avoid an extra stop() here.
        speakPhaseCueNow(phaseSpeech)
    }

    private fun resolveSoundId(count: Int, isNormalCountMode: Boolean): Int? {
        return if (isNormalCountMode) {
            normalCountSoundIds[count]
        } else {
            motionSoundIds[count]
        }
    }

    private fun speakPhaseCueNow(phaseSpeech: PhaseSpeech) {
        val tts = textToSpeech ?: return

        val speakText = when (phaseSpeech.voicePhase) {
            WorkoutPhase.Fast -> phaseSpeech.voiceRoundTripCount?.let { "${it}回" }
                ?: appContext.getString(R.string.timer_phase_fast)
            WorkoutPhase.Slow -> appContext.getString(R.string.timer_phase_slow)
        }
        val utteranceId = "${phaseSpeech.count}-${phaseSpeech.voicePhase.name}-${phaseSpeech.voiceRoundTripCount ?: 0}"
        activePhaseUtteranceId = utteranceId
        val status = tts.speak(
            speakText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (status != TextToSpeech.SUCCESS) {
            activePhaseUtteranceId = null
            Log.w(TAG, "Failed to speak countdown voice text=$speakText")
        }
    }

    private fun ensureCompositeSoundLoaded(count: Int) {
        if (motionSoundIds.containsKey(count)) return
        val outputFile = compositeVoiceFile(count)
        if (!outputFile.exists()) {
            generateCompositeVoiceFile(count, outputFile)
        }
        motionSoundIds[count] = soundPool.load(outputFile.absolutePath, 1)
    }

    private fun compositeVoiceFile(count: Int): File {
        if (!compositeVoiceDir.exists()) {
            compositeVoiceDir.mkdirs()
        }
        return File(compositeVoiceDir, "count_$count.wav")
    }

    private fun generateCompositeVoiceFile(count: Int, outputFile: File) {
        val parts = pronunciationParts(count).mapNotNull { baseClips[it] }
        if (parts.isEmpty()) {
            return
        }
        val mergedClip = mergeClips(parts)
        FileOutputStream(outputFile).use { output ->
            writeWaveFile(output, mergedClip)
        }
    }

    private fun loadBaseClips(): Map<Int, PcmClip> {
        return BASE_COUNT_RESOURCE_IDS.mapValues { (_, resId) ->
            appContext.resources.openRawResource(resId).use(::readWaveFile)
        }
    }

    private fun pronunciationParts(count: Int): List<Int> {
        val tens = count / 10
        val ones = count % 10
        if (count in 11..19) {
            return listOf(10, ones)
        }
        if (ones == 0) {
            return listOf(tens, 10)
        }
        return listOf(tens, 10, ones)
    }

    private fun mergeClips(parts: List<PcmClip>): PcmClip {
        val sampleRate = parts.first().sampleRate
        val overlapSamples = (sampleRate * CROSSFADE_MS) / 1000
        var mergedSamples = parts.first().trimmedSamples
        parts.drop(1).forEach { clip ->
            mergedSamples = crossfade(mergedSamples, clip.trimmedSamples, overlapSamples)
        }
        return PcmClip(
            sampleRate = sampleRate,
            channelCount = 1,
            bitsPerSample = 16,
            samples = mergedSamples,
        )
    }

    private fun crossfade(first: ShortArray, second: ShortArray, overlapSamples: Int): ShortArray {
        val actualOverlap = min(min(first.size, second.size), overlapSamples)
        if (actualOverlap <= 0) {
            return first + second
        }
        val result = ShortArray(first.size + second.size - actualOverlap)
        val cutPoint = first.size - actualOverlap
        first.copyInto(result, endIndex = cutPoint)
        for (index in 0 until actualOverlap) {
            val firstWeight = (actualOverlap - index).toFloat() / actualOverlap.toFloat()
            val secondWeight = index.toFloat() / actualOverlap.toFloat()
            val mixed = (first[cutPoint + index] * firstWeight) + (second[index] * secondWeight)
            result[cutPoint + index] = mixed.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        second.copyInto(result, destinationOffset = first.size, startIndex = actualOverlap)
        return result
    }

    private fun readWaveFile(input: InputStream): PcmClip {
        val bytes = input.readBytes()
        require(bytes.size > 44) { "Wave file too short" }
        require(String(bytes, 0, 4) == "RIFF") { "Unsupported wave file header" }
        val channelCount = littleEndianShort(bytes, 22)
        val sampleRate = littleEndianInt(bytes, 24)
        val bitsPerSample = littleEndianShort(bytes, 34)
        require(channelCount == 1) { "Only mono wav files are supported" }
        require(bitsPerSample == 16) { "Only 16-bit wav files are supported" }
        val dataStart = findDataChunkStart(bytes)
        val dataSize = littleEndianInt(bytes, dataStart - 4)
        val sampleCount = dataSize / 2
        val samples = ShortArray(sampleCount)
        var byteIndex = dataStart
        for (sampleIndex in 0 until sampleCount) {
            samples[sampleIndex] = littleEndianShort(bytes, byteIndex).toShort()
            byteIndex += 2
        }
        return PcmClip(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            samples = trimSilence(samples, sampleRate),
        )
    }

    private fun trimSilence(samples: ShortArray, sampleRate: Int): ShortArray {
        val paddingSamples = (sampleRate * SILENCE_PADDING_MS) / 1000
        var startIndex = 0
        while (startIndex < samples.size && abs(samples[startIndex].toInt()) < SILENCE_THRESHOLD) {
            startIndex += 1
        }
        var endIndex = samples.lastIndex
        while (endIndex >= startIndex && abs(samples[endIndex].toInt()) < SILENCE_THRESHOLD) {
            endIndex -= 1
        }
        if (startIndex > endIndex) {
            return samples
        }
        val trimmedStart = max(0, startIndex - paddingSamples)
        val trimmedEnd = min(samples.size, endIndex + paddingSamples + 1)
        return samples.copyOfRange(trimmedStart, trimmedEnd)
    }

    private fun writeWaveFile(output: FileOutputStream, clip: PcmClip) {
        val dataSize = clip.samples.size * 2
        val header = ByteArrayOutputStream(44).apply {
            write("RIFF".toByteArray())
            writeIntLE(36 + dataSize)
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(clip.channelCount)
            writeIntLE(clip.sampleRate)
            writeIntLE(clip.sampleRate * clip.channelCount * 2)
            writeShortLE(clip.channelCount * 2)
            writeShortLE(16)
            write("data".toByteArray())
            writeIntLE(dataSize)
        }.toByteArray()
        output.write(header)
        clip.samples.forEach { sample ->
            output.write(sample.toInt() and 0xFF)
            output.write((sample.toInt() shr 8) and 0xFF)
        }
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun findDataChunkStart(bytes: ByteArray): Int {
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            if (chunkId == "data") {
                return offset + 8
            }
            offset += 8 + chunkSize
        }
        error("Wave data chunk not found")
    }

    private companion object {
        private const val TAG = "CountdownVoicePlayer"
        private const val SILENCE_THRESHOLD = 300
        private const val SILENCE_PADDING_MS = 10
        private const val CROSSFADE_MS = 40
        private val NORMAL_COUNT_RESOURCE_IDS = mapOf(
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
            11 to R.raw.count_11,
            12 to R.raw.count_12,
            13 to R.raw.count_13,
            14 to R.raw.count_14,
            15 to R.raw.count_15,
            16 to R.raw.count_16,
            17 to R.raw.count_17,
            18 to R.raw.count_18,
            19 to R.raw.count_19,
            20 to R.raw.count_20,
            21 to R.raw.count_21,
            22 to R.raw.count_22,
            23 to R.raw.count_23,
            24 to R.raw.count_24,
            25 to R.raw.count_25,
            26 to R.raw.count_26,
            27 to R.raw.count_27,
            28 to R.raw.count_28,
            29 to R.raw.count_29,
            30 to R.raw.count_30,
            31 to R.raw.count_31,
            32 to R.raw.count_32,
            33 to R.raw.count_33,
            34 to R.raw.count_34,
            35 to R.raw.count_35,
            36 to R.raw.count_36,
            37 to R.raw.count_37,
            38 to R.raw.count_38,
            39 to R.raw.count_39,
            40 to R.raw.count_40,
            41 to R.raw.count_41,
            42 to R.raw.count_42,
            43 to R.raw.count_43,
            44 to R.raw.count_44,
            45 to R.raw.count_45,
            46 to R.raw.count_46,
            47 to R.raw.count_47,
            48 to R.raw.count_48,
            49 to R.raw.count_49,
            50 to R.raw.count_50,
        )
        private val MOTION_COUNT_RESOURCE_IDS = mapOf(
            10 to R.raw.motion_count_10,
            9 to R.raw.motion_count_9,
            8 to R.raw.motion_count_8,
            7 to R.raw.motion_count_7,
            6 to R.raw.motion_count_6,
            5 to R.raw.motion_count_5,
            4 to R.raw.motion_count_4,
            3 to R.raw.motion_count_3,
            2 to R.raw.motion_count_2,
            1 to R.raw.motion_count_1,
            0 to R.raw.motion_count_0,
        )
        private val BASE_COUNT_RESOURCE_IDS = MOTION_COUNT_RESOURCE_IDS
    }

    private data class PendingPlayback(
        val count: Int,
        val cueType: CountdownCueType,
        val isNormalCountMode: Boolean,
    )

    private data class PhaseSpeech(
        val count: Int,
        val cueType: CountdownCueType,
        val voicePhase: WorkoutPhase,
        val voiceRoundTripCount: Int?,
    )

    private data class PcmClip(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val samples: ShortArray,
    ) {
        val trimmedSamples: ShortArray
            get() = samples
    }
}
