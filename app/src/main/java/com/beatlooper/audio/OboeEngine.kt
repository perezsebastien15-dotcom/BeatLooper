package com.beatlooper.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrapper Kotlin du moteur C++ Oboe.
 *
 * Chaque méthode publique correspond à une fonction native JNI
 * déclarée dans BeatLooperJNI.cpp.
 *
 * Utilisation :
 *   val engine = OboeEngine()
 *   engine.create()
 *   engine.setBpm(120.0)
 *   engine.loadSampleFromFile(0, "/path/to/kick.wav")
 *   engine.schedulePadOnNextBeat(0)  // quantisé au prochain beat
 */
class OboeEngine {

    companion object {
        private const val TAG = "OboeEngine"
        private const val OBOE_SAMPLE_RATE = 48000 // Doit correspondre à AudioEngine.h

        init {
            // Charge la lib native compilée par le NDK
            System.loadLibrary("beatlooper")
        }
    }

    // ─────────────────────────────────────────────────────────
    // Déclarations natives (implémentées dans BeatLooperJNI.cpp)
    // ─────────────────────────────────────────────────────────
    private external fun nativeCreate(): Boolean
    private external fun nativeDestroy()
    private external fun nativeLoadSample(padId: Int, samples: FloatArray)
    private external fun nativeSchedulePadOnNextBeat(padId: Int)
    private external fun nativeStopPad(padId: Int)
    private external fun nativeSetLooping(padId: Int, loop: Boolean)
    private external fun nativeSetBpm(bpm: Double)
    private external fun nativeGetBpm(): Double
    private external fun nativeGetBeatCount(): Long
    private external fun nativeSetMetronomeEnabled(enabled: Boolean)
    private external fun nativeSetBeatsPerBar(beats: Int)
    private external fun nativeStartLoopRecord()
    private external fun nativeStopLoopRecord()
    private external fun nativeStopLoop()
    private external fun nativeSetPadSpeed(padId: Int, speed: Float)
    private external fun nativeSetPadPitch(padId: Int, semitones: Float)

    // ─────────────────────────────────────────────────────────
    // API publique
    // ─────────────────────────────────────────────────────────

    fun create(): Boolean = nativeCreate()
    fun destroy() = nativeDestroy()

    // ── BPM ──
    fun setBpm(bpm: Double) = nativeSetBpm(bpm.coerceIn(40.0, 300.0))
    fun getBpm(): Double = nativeGetBpm()
    fun getBeatCount(): Long = nativeGetBeatCount()
    fun setMetronomeEnabled(enabled: Boolean) = nativeSetMetronomeEnabled(enabled)
    fun setBeatsPerBar(beats: Int) = nativeSetBeatsPerBar(beats)

    // ── Pads ──
    fun schedulePadOnNextBeat(padId: Int) = nativeSchedulePadOnNextBeat(padId)
    fun stopPad(padId: Int) = nativeStopPad(padId)
    fun setLooping(padId: Int, loop: Boolean) = nativeSetLooping(padId, loop)

    // ── Looper ──
    fun startLoopRecord() = nativeStartLoopRecord()
    fun stopLoopRecord() = nativeStopLoopRecord()
    fun stopLoop() = nativeStopLoop()

    fun setPadSpeed(padId: Int, speed: Float) = nativeSetPadSpeed(padId, speed.coerceIn(0.25f, 4.0f))
    fun setPadPitch(padId: Int, semitones: Float) = nativeSetPadPitch(padId, semitones.coerceIn(-24f, 24f))

    // ─────────────────────────────────────────────────────────
    // Chargement d'un fichier audio → FloatArray → C++
    //
    // On utilise MediaCodec pour décoder n'importe quel format
    // (WAV, MP3, OGG, AAC...) en PCM float, puis on rééchantillonne
    // si nécessaire vers 48kHz (fréquence du moteur Oboe).
    // ─────────────────────────────────────────────────────────

    fun loadSampleFromFile(padId: Int, filePath: String): Boolean {
        return try {
            val pcmFloat = decodeAudioFile(filePath)
            if (pcmFloat.isEmpty()) {
                Log.e(TAG, "Décodage vide : $filePath")
                return false
            }
            nativeLoadSample(padId, pcmFloat)
            Log.d(TAG, "Sample chargé pad $padId : ${pcmFloat.size} samples (${pcmFloat.size / OBOE_SAMPLE_RATE}s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur chargement sample : ${e.message}", e)
            false
        }
    }

    fun loadSampleFromResource(context: Context, padId: Int, resId: Int): Boolean {
        return try {
            // Copie la ressource dans un fichier temporaire pour MediaExtractor
            val tempFile = File(context.cacheDir, "pad_${padId}_preset.tmp")
            context.resources.openRawResource(resId).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            loadSampleFromFile(padId, tempFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur ressource : ${e.message}", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────
    // Décodage audio avec MediaCodec + MediaExtractor
    // ─────────────────────────────────────────────────────────

    private fun decodeAudioFile(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)

        // Trouve la piste audio
        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("Aucune piste audio trouvée dans $filePath")
        }

        extractor.selectTrack(audioTrackIndex)
        val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // Décode en PCM 16 bits
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmShorts = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            // Envoie les données compressées au décodeur
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Récupère les données PCM décodées
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = outputBuffer.asShortBuffer()
                while (shortBuffer.hasRemaining()) {
                    pcmShorts.add(shortBuffer.get())
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        // Convertit Short → Float [-1, 1] et mixe en mono si stéréo
        val monoShorts = if (channelCount > 1) {
            // Moyenne des canaux pour obtenir le mono
            ShortArray(pcmShorts.size / channelCount) { i ->
                var sum = 0
                for (ch in 0 until channelCount) sum += pcmShorts[i * channelCount + ch]
                (sum / channelCount).toShort()
            }
        } else {
            pcmShorts.toShortArray()
        }

        // Rééchantillonnage simple vers OBOE_SAMPLE_RATE si nécessaire
        return if (sourceSampleRate != OBOE_SAMPLE_RATE) {
            resample(monoShorts, sourceSampleRate, OBOE_SAMPLE_RATE)
        } else {
            FloatArray(monoShorts.size) { i -> monoShorts[i] / 32768f }
        }
    }

    /**
     * Rééchantillonnage linéaire simple.
     * Pour une qualité pro, utiliser une lib dédiée (ex: libsamplerate).
     */
    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): FloatArray {
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSize = (input.size / ratio).toInt()
        return FloatArray(outputSize) { i ->
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt().coerceAtMost(input.size - 2)
            val frac = (srcPos - srcIdx).toFloat()
            val s0 = input[srcIdx] / 32768f
            val s1 = input[srcIdx + 1] / 32768f
            s0 + frac * (s1 - s0) // Interpolation linéaire
        }
    }
}
