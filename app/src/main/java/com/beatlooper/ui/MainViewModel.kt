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

    private val _isRecordingMic = MutableStateFlow(false)
    val isRecordingMic: StateFlow<Boolean> = _isRecordingMic

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

    // ── FIX BUG 1+2 : mapping label → nom de fichier res/raw ──────────────
    // Les clés correspondent exactement aux noms des fichiers dans res/raw/
    // (sans extension, tout en minuscules)
    private val presetSoundMap = mapOf(
        "Kick"      to "kick",
        "Snare"     to "snare",
        "Hi-Hat"    to "hihat",
        "Clap"      to "clap",
        "Bass"      to "bass",
        "Tom"       to "tom",
        "Piano Do"  to "piano_c",
        "Piano Ré"  to "piano_d"
    )

    init {
        oboeEngine.create()
        startBeatPoller()
        // ── FIX BUG 1 : charger les 8 premiers sons au démarrage ──
        loadDefaultSounds()
    }

    // Charge automatiquement kick, snare, hi-hat, clap sur les 4 premiers pads
    // et les autres sons sur les suivants
    private fun loadDefaultSounds() {
        viewModelScope.launch {
            val defaultAssignments = listOf(
                0 to "Kick",
                1 to "Snare",
                2 to "Hi-Hat",
                3 to "Clap",
                4 to "Bass",
                6 to "Piano Do",
                7 to "Piano Ré"
            )
            defaultAssignments.forEach { (padId, label) ->
                loadPresetByName(padId, label)
            }
        }
    }

    // ── Charge un preset par son label ──────────────────────────────────────
    private suspend fun loadPresetByName(padId: Int, label: String): Boolean {
        val resName = presetSoundMap[label] ?: return false
        // Résolution dynamique du R.raw.xxx sans dépendance à la classe R
        val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
        if (resId == 0) {
            // Fichier absent de res/raw/ — pad désactivé silencieusement
            return false
        }
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

    // ── FIX BUG 2 : assignPresetSound utilise maintenant le vrai mapping ──
    fun assignPresetSound(padId: Int, label: String, resId: Int = 0) {
        viewModelScope.launch {
            val success = loadPresetByName(padId, label)
            if (!success) {
                // resId passé directement (fallback si besoin)
                if (resId != 0) {
                    val ok = oboeEngine.loadSampleFromResource(context, padId, resId)
                    if (ok) {
                        val updated = _pads.value.toMutableList()
                        updated[padId] = updated[padId].copy(label = label, soundType = SoundType.PRESET)
                        _pads.value = updated
                    } else {
                        _message.value = "Fichier audio introuvable pour : $label"
                    }
                } else {
                    _message.value = "Fichier audio introuvable pour : $label\n(Ajoute ${ presetSoundMap[label] }.wav dans res/raw/)"
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
                    label = "Micro", soundType = SoundType.RECORDED, soundPath = filePath
                )
                _pads.value = updated
            }
            _selectedPadId.value = null
        }
    }

    // ── FIX BUG 3 : startMicRecord / stopMicRecord corrigés ───────────────
    fun startMicRecord() {
        if (_isRecordingMic.value) return
        micRecorder.startRecording()
        _isRecordingMic.value = true
    }

    fun stopMicRecord(targetPadId: Int) {
        if (!_isRecordingMic.value) return
        // Met l'état à false immédiatement → le bouton répond instantanément
        _isRecordingMic.value = false
        viewModelScope.launch {
            val outputFile = File(context.filesDir, "pad_${targetPadId}_${System.currentTimeMillis()}.wav")
            val result = micRecorder.stopRecording(outputFile)
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

    fun startLoopRecord() { oboeEngine.startLoopRecord(); _isLoopRecording.value = true }
    fun stopLoopRecord() { oboeEngine.stopLoopRecord(); _isLoopRecording.value = false; _isLoopPlaying.value = true }
    fun stopLoop() { oboeEngine.stopLoop(); _isLoopRecording.value = false; _isLoopPlaying.value = false }
    fun exportLoop() { _message.value = "Export en cours… (V2)" }

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
        // Labels par défaut — seront remplacés dès que les sons sont chargés
        val defaultLabels = listOf(
            "Kick", "Snare", "Hi-Hat", "Clap",
            "Bass", "Tom", "—", "Piano Do",
            "Piano Ré", "—", "—", "—",
            "—", "—", "—", "—"
        )
        return List(16) { i -> Pad(id = i, color = colors[i], label = defaultLabels[i]) }
    }

    override fun onCleared() { super.onCleared(); oboeEngine.destroy() }
}
