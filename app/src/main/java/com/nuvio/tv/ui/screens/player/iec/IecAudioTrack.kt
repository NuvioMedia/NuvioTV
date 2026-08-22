package com.nuvio.tv.ui.screens.player.iec

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack


internal interface IecAudioTrack {
    val sampleRate: Int
    val frameSizeBytes: Int
    fun write(data: ByteArray, offset: Int, size: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun playbackHeadFrames(): Long
    fun setVolume(volume: Float)
}

internal fun interface IecAudioTrackFactory {
    fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack?

    fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val track = open(sampleRate, channelCount, bufferSizeBytes = 0, sessionId = 0)
        track?.release()
        return track != null
    }
}

internal class PlatformIecAudioTrackFactory : IecAudioTrackFactory {
    override fun canOpen(sampleRate: Int, channelCount: Int): Boolean {
        val encoding = AudioFormat.ENCODING_IEC61937
        val channelMask = if (channelCount > 2) {
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        return AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding) > 0
    }

    override fun open(
        sampleRate: Int,
        channelCount: Int,
        bufferSizeBytes: Int,
        sessionId: Int
    ): IecAudioTrack? {
        val encoding = AudioFormat.ENCODING_IEC61937
        val channelMask = if (channelCount > 2) {
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (min <= 0) return null
        val size = maxOf(min, bufferSizeBytes)
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (sessionId != AudioTrack.ERROR && sessionId != 0) {
                builder.setSessionId(sessionId)
            }
            val track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            track.pause()
            track.flush()
            PlatformIecAudioTrack(track, sampleRate, channelCount * 2)
        } catch (_: Exception) {
            null
        }
    }
}

private class PlatformIecAudioTrack(
    private val track: AudioTrack,
    override val sampleRate: Int,
    override val frameSizeBytes: Int
) : IecAudioTrack {
    private var headWrap: Long = 0L
    private var lastHead: Int = 0

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        return track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    override fun play() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    override fun pause() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
        }
    }

    override fun flush() {
        track.pause()
        track.flush()
        headWrap = 0L
        lastHead = 0
    }

    override fun release() {
        try {
            track.pause()
            track.flush()
        } catch (_: Exception) {
        }
        track.release()
    }

    override fun playbackHeadFrames(): Long {
        val head = track.playbackHeadPosition
        if (head < lastHead) {
            headWrap += 1L shl 32
        }
        lastHead = head
        return headWrap + (head.toLong() and 0xFFFFFFFFL)
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }
}
