package com.beatlooper.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OboeEngine {

    companion object {
        private const val TAG = "OboeEngine"
        private const val OBOE_SAMPLE_RATE = 48000
        init { System.loadLibrary("beatlooper") }
    }

    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativeLoadSample(padId: Int, samples: FloatArray)
    // FIX LATENCE : triggerPadNow joue immédiatement
    private external fun nativeTriggerPadNow(padId: Int)
    private external fun nativeSchedulePadOnNextBeat(padId: Int)
    private external fun nativeStopPad(padId: Int)
    private external fun nativeSetLooping(padId: Int, loop: Boolean)
    private external fun nativeSetBpm(bpm: Double)
    private external fun nativeGetBpm(): Double
    private external fun nativeGetBeatCount(): Long
    private external fun nativeSetMetronomeEnabled(enabled: Boolean)
    private external fun nativeSetBeatsPerBar(beats: Int)
    private external fun nativeStartLoopRecord()
    // FIX LOOPER : version avec beats pour sync BPM
    private external fun nativeStartLoopRecordBeats(beats: Int)
    private external fun nativeStopLoopRecord()
    private external fun nativeStopLoop()
    private external fun nativeSetPadSpeed(padId: Int, speed: Float)
    private external fun nativeSetPadPitch(padId: Int, semitones: Float)

    fun create(): Boolean = nativeCreate()
    fun destroy() = nativeDestroy()

    fun setBpm(bpm: Double) = nativeSetBpm(bpm.coerceIn(40.0, 300.0))
    fun getBpm(): Double = nativeGetBpm()
    fun getBeatCount(): Long = nativeGetBeatCount()
    fun setMetronomeEnabled(enabled: Boolean) = nativeSetMetronomeEnabled(enabled)
    fun setBeatsPerBar(beats: Int) = nativeSetBeatsPerBar(beats)

    // FIX LATENCE : utilise triggerPadNow par défaut
    fun triggerPadNow(padId: Int) = nativeTriggerPadNow(padId)
    fun schedulePadOnNextBeat(padId: Int) = nativeSchedulePadOnNextBeat(padId)
    fun stopPad(padId: Int) = nativeStopPad(padId)
    fun setLooping(padId: Int, loop: Boolean) = nativeSetLooping(padId, loop)

    // FIX LOOPER : startLoopRecord(beats) synchronise sur le BPM
    fun startLoopRecord(beats: Int = 4) = nativeStartLoopRecordBeats(beats)
    fun stopLoopRecord() = nativeStopLoopRecord()
    fun stopLoop() = nativeStopLoop()

    fun setPadSpeed(padId: Int, speed: Float) =
        nativeSetPadSpeed(padId, speed.coerceIn(0.25f, 4.0f))
    fun setPadPitch(padId: Int, semitones: Float) =
        nativeSetPadPitch(padId, semitones.coerceIn(-24f, 24f))

    fun loadSampleFromFile(padId: Int, filePath: String): Boolean {
        return try {
            val pcmFloat = decodeAudioFile(filePath)
            if (pcmFloat.isEmpty()) { Log.e(TAG, "Décodage vide : $filePath"); return false }
            nativeLoadSample(padId, pcmFloat)
            Log.d(TAG, "Sample chargé pad $padId : ${pcmFloat.size} samples")
            true
        } catch (e: Exception) { Log.e(TAG, "Erreur : ${e.message}", e); false }
    }

    fun loadSampleFromResource(context: Context, padId: Int, resId: Int): Boolean {
        return try {
            val tempFile = File(context.cacheDir, "pad_${padId}_preset.tmp")
            context.resources.openRawResource(resId).use { i ->
                tempFile.outputStream().use { o -> i.copyTo(o) }
            }
            loadSampleFromFile(padId, tempFile.absolutePath)
        } catch (e: Exception) { Log.e(TAG, "Erreur ressource : ${e.message}", e); false }
    }

    private fun decodeAudioFile(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)
        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIndex = i; format = f; break
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("Aucune piste audio dans $filePath")
        }
        extractor.selectTrack(audioTrackIndex)
        val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmShorts = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000L)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val sb = buf.asShortBuffer()
                while (sb.hasRemaining()) pcmShorts.add(sb.get())
                codec.releaseOutputBuffer(outIdx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val monoShorts = if (channelCount > 1) {
            ShortArray(pcmShorts.size / channelCount) { i ->
                var sum = 0
                for (ch in 0 until channelCount) sum += pcmShorts[i * channelCount + ch]
                (sum / channelCount).toShort()
            }
        } else pcmShorts.toShortArray()

        return if (sourceSampleRate != OBOE_SAMPLE_RATE)
            resample(monoShorts, sourceSampleRate, OBOE_SAMPLE_RATE)
        else FloatArray(monoShorts.size) { i -> monoShorts[i] / 32768f }
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSize = (input.size / ratio).toInt()
        return FloatArray(outputSize) { i ->
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt().coerceAtMost(input.size - 2)
            val frac = (srcPos - srcIdx).toFloat()
            val s0 = input[srcIdx] / 32768f
            val s1 = input[srcIdx + 1] / 32768f
            s0 + frac * (s1 - s0)
        }
    }
}
