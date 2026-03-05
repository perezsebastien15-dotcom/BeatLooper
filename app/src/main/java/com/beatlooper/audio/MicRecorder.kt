package com.beatlooper.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enregistre le micro et sauvegarde en fichier WAV.
 *
 * Utilisation :
 *   micRecorder.startRecording()
 *   ...
 *   val wavFile = micRecorder.stopRecording()
 */
class MicRecorder {

    private val TAG = "MicRecorder"

    companion object {
        const val SAMPLE_RATE = 44100       // Hz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val pcmData = mutableListOf<Short>()
    private var isRecording = false

    /**
     * Démarre l'enregistrement micro en coroutine.
     * Permission RECORD_AUDIO requise avant l'appel.
     */
    fun startRecording() {
        if (isRecording) return
        pcmData.clear()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non initialisé")
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    pcmData.addAll(buffer.take(read))
                }
            }
        }
    }

    /**
     * Arrête l'enregistrement et retourne le fichier WAV généré.
     * @param outputFile Fichier de destination
     */
    suspend fun stopRecording(outputFile: File): File? {
        if (!isRecording) return null
        isRecording = false
        recordingJob?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        return writeWavFile(outputFile, pcmData.toShortArray())
    }

    /**
     * Écrit un fichier WAV à partir des samples PCM.
     * Format WAV = header RIFF 44 octets + data PCM
     */
    private fun writeWavFile(file: File, pcm: ShortArray): File {
        val dataSize = pcm.size * 2 // 2 octets par sample (16 bits)
        FileOutputStream(file).use { fos ->
            // --- Header RIFF ---
            fos.write(riffHeader(dataSize))
            // --- Données PCM ---
            val byteBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { byteBuffer.putShort(it) }
            fos.write(byteBuffer.array())
        }
        Log.d(TAG, "WAV écrit : ${file.absolutePath} (${dataSize / 1024} Ko)")
        return file
    }

    private fun riffHeader(dataSize: Int): ByteArray {
        val totalSize = dataSize + 36
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)           // Taille du chunk fmt
        buf.putShort(1)          // PCM = 1
        buf.putShort(1)          // Mono = 1
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)  // ByteRate = SampleRate * NumChannels * BitsPerSample/8
        buf.putShort(2)          // BlockAlign
        buf.putShort(16)         // BitsPerSample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        return buf.array()
    }
}
