package com.beatlooper.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatlooper.audio.AudioExporter
import com.beatlooper.audio.BpmClock
import com.beatlooper.audio.MicRecorder
import com.beatlooper.audio.OboeEngine
import com.beatlooper.model.Pad
import com.beatlooper.model.SoundType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    // ─── Moteurs ───
    val oboeEngine = OboeEngine()
    val bpmClock = BpmClock()
    private val micRecorder = MicRecorder()

    // ─── BPM exposé à l'UI ───
    val bpm: StateFlow<Double> = bpmClock.bpm

    // ─── Pads ───
    private val _pads = MutableStateFlow(createDefaultPads())
    val pads: StateFlow<List<Pad>> = _pads

    // ─── État looper ───
    private val _isLoopRecording = MutableStateFlow(false)
    val isLoopRecording: StateFlow<Boolean> = _isLoopRecording

    private val _isLoopPlaying = MutableStateFlow(false)
    val isLoopPlaying: StateFlow<Boolean> = _isLoopPlaying

    // ─── Enregistrement micro ───
    private val _isRecordingMic = MutableStateFlow(false)
    val isRecordingMic: StateFlow<Boolean> = _isRecordingMic

    // ─── Dialog de config du pad ───
    private val _selectedPadId = MutableStateFlow<Int?>(null)
    val selectedPadId: StateFlow<Int?> = _selectedPadId

    // ─── Métronome ───
    private val _metronomeEnabled = MutableStateFlow(false)
    val metronomeEnabled: StateFlow<Boolean> = _metronomeEnabled

    // ─── Beat courant (pour animation UI) ───
    private val _currentBeat = MutableStateFlow(0L)
    val currentBeat: StateFlow<Long> = _currentBeat

    // ─── Messages snackbar ───
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // ─── Tap tempo : nb de taps effectués ───
    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount

    init {
        oboeEngine.create()
        startBeatPoller()
    }

    // Polling du beat C++ vers l'UI (~120fps)
    private fun startBeatPoller() {
        viewModelScope.launch {
            while (true) {
                val beat = oboeEngine.getBeatCount()
                if (beat != _currentBeat.value) _currentBeat.value = beat
                delay(8)
            }
        }
    }

    // ── Pad tap → quantisé au prochain beat ──
    fun onPadTap(padId: Int) {
        val pad = _pads.value[padId]
        if (pad.soundType == SoundType.NONE) {
            _message.value = "Appuie longuement pour assigner un son"
            return
        }
        oboeEngine.schedulePadOnNextBeat(padId)
    }

    fun onPadLongPress(padId: Int) { _selectedPadId.value = padId }

    fun togglePadLoop(padId: Int) {
        val updated = _pads.value.toMutableList()
        val newLoop = !updated[padId].isLooping
        updated[padId] = updated[padId].copy(isLooping = newLoop)
        _pads.value = updated
        oboeEngine.setLooping(padId, newLoop)
    }

    // ── Sons ──
    fun assignPresetSound(padId: Int, label: String, resId: Int) {
        viewModelScope.launch {
            val success = oboeEngine.loadSampleFromResource(context, padId, resId)
            if (success) {
                val updated = _pads.value.toMutableList()
                updated[padId] = updated[padId].copy(label = label, soundType = SoundType.PRESET)
                _pads.value = updated
            } else _message.value = "Erreur chargement son"
            _selectedPadId.value = null
        }
    }

    fun assignRecordedSound(padId: Int, filePath: String) {
        viewModelScope.launch {
            val success = oboeEngine.loadSampleFromFile(padId, filePath)
            if (success) {
                val updated = _pads.value.toMutableList()
                updated[padId] = updated[padId].copy(
                    label = "Micro", soundType = SoundType.RECORDED, soundPath = filePath
                )
                _pads.value = updated
            }
            _selectedPadId.value = null
        }
    }

    // ── Micro ──
    fun startMicRecord() { micRecorder.startRecording(); _isRecordingMic.value = true }
    fun stopMicRecord(targetPadId: Int) {
        viewModelScope.launch {
            val outputFile = File(context.filesDir, "pad_${targetPadId}_${System.currentTimeMillis()}.wav")
            val result = micRecorder.stopRecording(outputFile)
            _isRecordingMic.value = false
            if (result != null) { assignRecordedSound(targetPadId, result.absolutePath)
                _message.value = "Son enregistré sur le pad ${targetPadId + 1}"
            } else _message.value = "Erreur d'enregistrement"
        }
    }

    // ── BPM manuel ──
    fun setBpm(bpm: Double) { bpmClock.setBpm(bpm); oboeEngine.setBpm(bpm); _tapCount.value = 0 }
    fun incrementBpm() { bpmClock.increment(); oboeEngine.setBpm(bpmClock.bpm.value) }
    fun decrementBpm() { bpmClock.decrement(); oboeEngine.setBpm(bpmClock.bpm.value) }

    // ── Tap Tempo ──
    fun onTapTempo() {
        val newBpm = bpmClock.tap()
        _tapCount.value = bpmClock.tapCount()
        newBpm?.let { oboeEngine.setBpm(it) }
    }

    // ── Métronome ──
    fun toggleMetronome() {
        val newState = !_metronomeEnabled.value
        _metronomeEnabled.value = newState
        oboeEngine.setMetronomeEnabled(newState)
    }

    // ── Looper ──
    fun startLoopRecord() { oboeEngine.startLoopRecord(); _isLoopRecording.value = true }
    fun stopLoopRecord() { oboeEngine.stopLoopRecord(); _isLoopRecording.value = false; _isLoopPlaying.value = true }
    fun setPadSpeed(padId: Int, speed: Float) {
        oboeEngine.setPadSpeed(padId, speed)
        val updated = _pads.value.toMutableList()
        updated[padId] = updated[padId].copy(speed = speed)
        _pads.value = updated
    }

    fun setPadPitch(padId: Int, semitones: Float) {
        oboeEngine.setPadPitch(padId, semitones)
        val updated = _pads.value.toMutableList()
        updated[padId] = updated[padId].copy(pitch = semitones)
        _pads.value = updated
    }

    fun stopLoop() { oboeEngine.stopLoop(); _isLoopRecording.value = false; _isLoopPlaying.value = false }
    fun exportLoop() { _message.value = "Export → V2" }

    fun clearMessage() { _message.value = null }
    fun closeDialog() { _selectedPadId.value = null }

    private fun createDefaultPads(): List<Pad> {
        val colors = listOf(
            Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
            Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1), Color(0xFF00897B),
            Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835),
            Color(0xFFFFB300), Color(0xFFFB8C00), Color(0xFFE53935), Color(0xFF6D4C41)
        )
        return List(16) { i -> Pad(id = i, color = colors[i], label = "Pad ${i + 1}") }
    }

    override fun onCleared() { super.onCleared(); oboeEngine.destroy() }
}
