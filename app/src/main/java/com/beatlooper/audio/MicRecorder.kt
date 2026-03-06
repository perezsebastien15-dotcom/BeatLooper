package com.beatlooper.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MicRecorder {

    private val TAG = "MicRecorder"

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private val pcmData = mutableListOf<Short>()

    // ── FIX BUG 3 : volatile pour visibilité inter-thread ──
    @Volatile private var isRecording = false

    fun startRecording() {
        if (isRecording) return
        pcmData.clear()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord non initialisé")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        audioRecord?.startRecording()

        // ── FIX : thread dédié au lieu d'une coroutine ──
        // Évite le problème de join() bloquant sur Dispatchers.IO
        Thread {
            val buffer = ShortArray(bufferSize / 2)
            Log.d(TAG, "Thread micro démarré")
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) synchronized(pcmData) {
                    buffer.take(read).forEach { pcmData.add(it) }
                }
            }
            Log.d(TAG, "Thread micro terminé — ${pcmData.size} samples capturés")
        }.apply { isDaemon = true; start() }
    }

    // ── FIX BUG 3 : stopRecording n'est plus suspend, pas de join() ──
    // On arrête le flag → le thread s'arrête seul au prochain cycle
    // On attend max 500ms que les derniers samples arrivent
    suspend fun stopRecording(outputFile: File): File? {
        if (!isRecording) {
            Log.w(TAG, "stopRecording appelé sans enregistrement actif")
            return null
        }

        // Signale l'arrêt
        isRecording = false

        // Laisse le thread terminer son dernier buffer (max 200ms)
        withContext(Dispatchers.IO) {
            Thread.sleep(200)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }

        val pcmSnapshot = synchronized(pcmData) { pcmData.toShortArray() }
        if (pcmSnapshot.isEmpty()) {
            Log.w(TAG, "Aucun sample enregistré")
            return null
        }

        return withContext(Dispatchers.IO) {
            writeWavFile(outputFile, pcmSnapshot)
        }
    }

    private fun writeWavFile(file: File, pcm: ShortArray): File {
        val dataSize = pcm.size * 2
        FileOutputStream(file).use { fos ->
            fos.write(riffHeader(dataSize))
            val byteBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { byteBuffer.putShort(it) }
            fos.write(byteBuffer.array())
        }
        Log.d(TAG, "WAV : ${file.absolutePath} (${dataSize / 1024} Ko)")
        return file
    }

    private fun riffHeader(dataSize: Int): ByteArray {
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(dataSize + 36)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        return buf.array()
    }
}
