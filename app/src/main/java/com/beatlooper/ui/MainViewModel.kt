package com.beatlooper.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    val oboeEngine = OboeEngine()
    val bpmClock = BpmClock()
    private val micRecorder = MicRecorder()

    val bpm: StateFlow<Double> = bpmClock.bpm

    private val _pads = MutableStateFlow(createDefaultPads())
    val pads: StateFlow<List<Pad>> = _pads

    private val _isLoopRecording = MutableStateFlow(false)
    val isLoopRecording: StateFlow<Boolean> = _isLoopRecording

    private val _isLoopPlaying = MutableStateFlow(false)
    val isLoopPlaying: StateFlow<Boolean> = _isLoopPlaying

    // Nombre de beats sélectionné pour le looper (1, 2, 4, 8)
    private val _loopBeats = MutableStateFlow(4)
    val loopBeats: StateFlow<Int> = _loopBeats

    private val _isRecordingMic = MutableStateFlow(false)
    val isRecordingMic: StateFlow<Boolean> = _isRecordingMic

    // Pad cible de l'enregistrement micro (pour le bouton flottant stop)
    private val _micTargetPadId = MutableStateFlow<Int?>(null)
    val micTargetPadId: StateFlow<Int?> = _micTargetPadId

    private val _selectedPadId = MutableStateFlow<Int?>(null)
    val selectedPadId: StateFlow<Int?> = _selectedPadId

    private val _metronomeEnabled = MutableStateFlow(false)
    val metronomeEnabled: StateFlow<Boolean> = _metronomeEnabled

    private val _currentBeat = MutableStateFlow(0L)
    val currentBeat: StateFlow<Long> = _currentBeat

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount

    private val presetSoundMap = mapOf(
        "Kick"     to "kick",
        "Snare"    to "snare",
        "Hi-Hat"   to "hihat",
        "Clap"     to "clap",
        "Bass"     to "bass",
        "Tom"      to "tom",
        "Piano Do" to "piano_c",
        "Piano Ré" to "piano_d"
    )

    init {
        oboeEngine.create()
        startBeatPoller()
        loadDefaultSounds()
    }

    private fun loadDefaultSounds() {
        viewModelScope.launch {
            listOf(0 to "Kick", 1 to "Snare", 2 to "Hi-Hat", 3 to "Clap",
                   4 to "Bass", 6 to "Piano Do", 7 to "Piano Ré")
                .forEach { (padId, label) -> loadPresetByName(padId, label) }
        }
    }

    private suspend fun loadPresetByName(padId: Int, label: String): Boolean {
        val resName = presetSoundMap[label] ?: return false
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) return false
        val success = oboeEngine.loadSampleFromResource(context, padId, resId)
        if (success) {
            val updated = _pads.value.toMutableList()
            updated[padId] = updated[padId].copy(label = label, soundType = SoundType.PRESET)
            _pads.value = updated
        }
        return success
    }

    private fun startBeatPoller() {
        viewModelScope.launch {
            while (true) {
                val beat = oboeEngine.getBeatCount()
                if (beat != _currentBeat.value) _currentBeat.value = beat
                delay(8)
            }
        }
    }

    // FIX LATENCE : triggerPadNow — immédiat, sans attendre le beat
    fun onPadTap(padId: Int) {
        val pad = _pads.value[padId]
        if (pad.soundType == SoundType.NONE) {
            _message.value = "Appuie longuement pour assigner un son"
            return
        }
        oboeEngine.triggerPadNow(padId)
    }

    fun onPadLongPress(padId: Int) { _selectedPadId.value = padId }

    fun togglePadLoop(padId: Int) {
        val updated = _pads.value.toMutableList()
        val newLoop = !updated[padId].isLooping
        updated[padId] = updated[padId].copy(isLooping = newLoop)
        _pads.value = updated
        oboeEngine.setLooping(padId, newLoop)
    }

    fun assignPresetSound(padId: Int, label: String, resId: Int = 0) {
        viewModelScope.launch {
            val success = loadPresetByName(padId, label)
            if (!success) {
                if (resId != 0) {
                    val ok = oboeEngine.loadSampleFromResource(context, padId, resId)
                    if (ok) {
                        val updated = _pads.value.toMutableList()
                        updated[padId] = updated[padId].copy(label = label, soundType = SoundType.PRESET)
                        _pads.value = updated
                    } else _message.value = "Fichier audio introuvable : $label"
                } else {
                    _message.value = "Ajoute ${presetSoundMap[label]}.wav dans res/raw/"
                }
            } else {
                _message.value = "\"$label\" assigné au pad ${padId + 1}"
            }
            _selectedPadId.value = null
        }
    }

    fun assignRecordedSound(padId: Int, filePath: String) {
        viewModelScope.launch {
            val success = oboeEngine.loadSampleFromFile(padId, filePath)
            if (success) {
                val updated = _pads.value.toMutableList()
                updated[padId] = updated[padId].copy(
                    label = "Micro", soundType = SoundType.RECORDED, soundPath = filePath)
                _pads.value = updated
            }
            _selectedPadId.value = null
        }
    }

    // FIX MICRO : startMicRecord mémorise le pad cible → bouton flottant visible
    fun startMicRecord(targetPadId: Int) {
        if (_isRecordingMic.value) return
        _micTargetPadId.value = targetPadId
        _selectedPadId.value = null   // ferme le dialog → retour écran principal
        micRecorder.startRecording()
        _isRecordingMic.value = true
    }

    // Peut être appelé depuis le bouton flottant ou depuis le dialog
    fun stopMicRecord() {
        val targetPadId = _micTargetPadId.value ?: return
        if (!_isRecordingMic.value) return
        _isRecordingMic.value = false
        viewModelScope.launch {
            val outputFile = File(context.filesDir,
                "pad_${targetPadId}_${System.currentTimeMillis()}.wav")
            val result = micRecorder.stopRecording(outputFile)
            _micTargetPadId.value = null
            if (result != null) {
                assignRecordedSound(targetPadId, result.absolutePath)
                _message.value = "Son enregistré sur le pad ${targetPadId + 1}"
            } else {
                _message.value = "Erreur d'enregistrement micro"
            }
        }
    }

    fun setBpm(bpm: Double) { bpmClock.setBpm(bpm); oboeEngine.setBpm(bpm); _tapCount.value = 0 }
    fun incrementBpm() { bpmClock.increment(); oboeEngine.setBpm(bpmClock.bpm.value) }
    fun decrementBpm() { bpmClock.decrement(); oboeEngine.setBpm(bpmClock.bpm.value) }

    fun onTapTempo() {
        val newBpm = bpmClock.tap()
        _tapCount.value = bpmClock.tapCount()
        newBpm?.let { oboeEngine.setBpm(it) }
    }

    fun toggleMetronome() {
        val newState = !_metronomeEnabled.value
        _metronomeEnabled.value = newState
        oboeEngine.setMetronomeEnabled(newState)
    }

    // FIX LOOPER : startLoopRecord(beats) synchronise sur le tempo
    fun setLoopBeats(beats: Int) { _loopBeats.value = beats }

    fun startLoopRecord() {
        val beats = _loopBeats.value
        oboeEngine.startLoopRecord(beats)
        _isLoopRecording.value = true
        _isLoopPlaying.value = false
        // Auto-stop après la durée en ms
        val bpm = bpmClock.bpm.value
        val durationMs = ((beats * 60_000.0) / bpm).toLong()
        viewModelScope.launch {
            delay(durationMs + 50) // +50ms marge
            if (_isLoopRecording.value) {
                oboeEngine.stopLoopRecord()
                _isLoopRecording.value = false
                _isLoopPlaying.value = true
            }
        }
    }

    fun stopLoopRecord() {
        oboeEngine.stopLoopRecord()
        _isLoopRecording.value = false
        _isLoopPlaying.value = true
    }

    fun stopLoop() {
        oboeEngine.stopLoop()
        _isLoopRecording.value = false
        _isLoopPlaying.value = false
    }

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

    fun clearMessage() { _message.value = null }
    fun closeDialog() { _selectedPadId.value = null }

    private fun createDefaultPads(): List<Pad> {
        val colors = listOf(
            Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
            Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1), Color(0xFF00897B),
            Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835),
            Color(0xFFFFB300), Color(0xFFFB8C00), Color(0xFFE53935), Color(0xFF6D4C41)
        )
        val labels = listOf("Kick","Snare","Hi-Hat","Clap","Bass","Tom","—","Piano Do",
                            "Piano Ré","—","—","—","—","—","—","—")
        return List(16) { i -> Pad(id = i, color = colors[i], label = labels[i]) }
    }

    override fun onCleared() { super.onCleared(); oboeEngine.destroy() }
}
