package com.goenc.androiddailymotiontimer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log

class CountdownVoicePlayer(context: Context) {
    private val appContext = context.applicationContext
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_ALARM)
                .build()
        )
        .build()
    private val soundIds = mutableMapOf<Int, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private var activeStreamId: Int? = null
    private var pendingCount: Int? = null

    init {
        soundPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedSoundIds += soundId
            val queuedCount = pendingCount ?: return@setOnLoadCompleteListener
            if (soundIds[queuedCount] == soundId) {
                pendingCount = null
                playLoadedSound(soundId)
            }
        }
        COUNT_RESOURCE_IDS.forEach { (count, resId) ->
            soundIds[count] = soundPool.load(appContext, resId, 1)
        }
    }

    fun playCount(count: Int) {
        val soundId = soundIds[count] ?: return
        if (loadedSoundIds.contains(soundId)) {
            pendingCount = null
            playLoadedSound(soundId)
        } else {
            stopActivePlayback()
            pendingCount = count
        }
    }

    fun stop() {
        pendingCount = null
        stopActivePlayback()
    }

    fun release() {
        stop()
        soundPool.release()
    }

    private fun stopActivePlayback() {
        activeStreamId?.let(soundPool::stop)
        activeStreamId = null
    }

    private fun playLoadedSound(soundId: Int) {
        stopActivePlayback()
        val streamId = soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        if (streamId == 0) {
            Log.w(TAG, "Failed to play countdown voice for soundId=$soundId")
            activeStreamId = null
            return
        }
        activeStreamId = streamId
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
}
