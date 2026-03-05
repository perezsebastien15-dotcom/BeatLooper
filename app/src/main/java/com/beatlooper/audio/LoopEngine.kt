package com.beatlooper.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.beatlooper.model.LoopState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Moteur de boucle audio.
 *
 * Fonctionnement :
 *  1. startLoopRecord()  → enregistre la première prise (détermine la durée de la boucle)
 *  2. stopLoopRecord()   → lance la lecture en boucle automatiquement
 *  3. startOverdub()     → enregistre le micro EN MÊME TEMPS que la lecture et mixe
 *  4. stopOverdub()      → fusionne l'overdub dans le buffer principal
 *  5. stopLoop()         → arrête tout
 *
 * Le buffer PCM est en mémoire (ShortArray). Pour de longues sessions,
 * envisager d'écrire sur disque.
 */
class LoopEngine {

    private val TAG = "LoopEngine"

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNELS_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    // État exposé à l'UI via StateFlow
    private val _loopState = MutableStateFlow(LoopState())
    val loopState: StateFlow<LoopState> = _loopState

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS_IN, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    private var loopBuffer: ShortArray = ShortArray(0)   // Buffer principal de la boucle
    private var overdubBuffer: ShortArray = ShortArray(0) // Buffer de l'overdub en cours

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var playJob: Job? = null
    private var overdubJob: Job? = null

    // ─────────────────────────────────────────────────
    // ENREGISTREMENT DE LA BOUCLE INITIALE
    // ─────────────────────────────────────────────────

    fun startLoopRecord() {
        if (_loopState.value.isRecording) return
        loopBuffer = ShortArray(0)
        val tempBuffer = mutableListOf<Short>()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNELS_IN, AUDIO_FORMAT, bufferSize
        ).also { it.startRecording() }

        _loopState.value = _loopState.value.copy(isRecording = true)

        recordJob = scope.launch {
            val chunk = ShortArray(bufferSize / 2)
            while (_loopState.value.isRecording) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: 0
                if (read > 0) tempBuffer.addAll(chunk.take(read))
            }
            loopBuffer = tempBuffer.toShortArray()
            Log.d(TAG, "Boucle enregistrée : ${loopBuffer.size} samples")
        }
    }

    fun stopLoopRecord() {
        _loopState.value = _loopState.value.copy(isRecording = false)
        recordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val durationMs = (loopBuffer.size.toLong() * 1000L) / SAMPLE_RATE
        _loopState.value = _loopState.value.copy(durationMs = durationMs, pcmBuffer = loopBuffer)

        startLoopPlayback() // Lance la lecture automatiquement
    }

    // ─────────────────────────────────────────────────
    // LECTURE EN BOUCLE
    // ─────────────────────────────────────────────────

    private fun startLoopPlayback() {
        if (loopBuffer.isEmpty()) return
        stopPlayback()

        audioTrack = buildAudioTrack()
        audioTrack?.play()
        _loopState.value = _loopState.value.copy(isPlaying = true)

        playJob = scope.launch {
            while (_loopState.value.isPlaying) {
                // Écriture du buffer en boucle infinie
                audioTrack?.write(loopBuffer, 0, loopBuffer.size)
            }
        }
    }

    private fun stopPlayback() {
        _loopState.value = _loopState.value.copy(isPlaying = false)
        playJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    // ─────────────────────────────────────────────────
    // OVERDUB : enregistrement par-dessus la boucle
    // ─────────────────────────────────────────────────

    fun startOverdub() {
        if (_loopState.value.isOverdubbing || loopBuffer.isEmpty()) return
        overdubBuffer = ShortArray(loopBuffer.size) // Même taille que la boucle
        var writePos = 0

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNELS_IN, AUDIO_FORMAT, bufferSize
        ).also { it.startRecording() }

        _loopState.value = _loopState.value.copy(isOverdubbing = true)

        overdubJob = scope.launch {
            val chunk = ShortArray(bufferSize / 2)
            while (_loopState.value.isOverdubbing) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: 0
                if (read > 0) {
                    for (i in 0 until read) {
                        if (writePos < overdubBuffer.size) {
                            overdubBuffer[writePos++] = chunk[i]
                        } else {
                            // Boucle complète : merge et recommencer
                            mergeOverdub()
                            writePos = 0
                        }
                    }
                }
            }
        }
    }

    fun stopOverdub() {
        _loopState.value = _loopState.value.copy(isOverdubbing = false)
        overdubJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mergeOverdub() // Fusionne ce qui a été enregistré
    }

    /**
     * Mixage PCM simple : somme clampée des deux buffers.
     * Pour un mixage plus propre, diviser par 2 ou utiliser un vrai mixer.
     */
    private fun mergeOverdub() {
        for (i in loopBuffer.indices) {
            val mixed = loopBuffer[i].toInt() + overdubBuffer.getOrElse(i) { 0 }.toInt()
            loopBuffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        _loopState.value = _loopState.value.copy(pcmBuffer = loopBuffer)
        Log.d(TAG, "Overdub fusionné dans la boucle")
    }

    // ─────────────────────────────────────────────────
    // ARRÊT COMPLET
    // ─────────────────────────────────────────────────

    fun stopLoop() {
        stopOverdub()
        stopPlayback()
        _loopState.value = LoopState()
    }

    /**
     * Retourne le buffer PCM final (pour export).
     */
    fun getFinalBuffer(): ShortArray = loopBuffer.copyOf()

    fun release() {
        stopLoop()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────

    private fun buildAudioTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS_OUT, AUDIO_FORMAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNELS_OUT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
