package com.beatlooper.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * Gère la logique BPM côté Kotlin :
 *  - Saisie manuelle
 *  - Tap tempo (calcul de la moyenne des intervalles)
 *
 * Le BPM calculé est ensuite envoyé au moteur Oboe via OboeEngine.setBpm().
 */
class BpmClock {

    companion object {
        const val BPM_MIN = 40.0
        const val BPM_MAX = 300.0
        const val BPM_DEFAULT = 120.0

        // Nombre de taps à mémoriser pour la moyenne tap tempo
        private const val TAP_HISTORY_SIZE = 8
        // Délai max entre deux taps (au-delà, on repart à zéro)
        private const val TAP_RESET_MS = 2500L
    }

    private val _bpm = MutableStateFlow(BPM_DEFAULT)
    val bpm: StateFlow<Double> = _bpm

    // Historique des timestamps de tap (en ms)
    private val tapTimestamps = ArrayDeque<Long>(TAP_HISTORY_SIZE)

    // ─────────────────────────────────────────────────────────
    // SAISIE MANUELLE
    // ─────────────────────────────────────────────────────────

    /**
     * Définit le BPM manuellement (saisie clavier ou slider).
     */
    fun setBpm(bpm: Double) {
        _bpm.value = bpm.coerceIn(BPM_MIN, BPM_MAX)
        tapTimestamps.clear() // Reset tap tempo quand on change manuellement
    }

    fun increment(step: Double = 1.0) = setBpm(_bpm.value + step)
    fun decrement(step: Double = 1.0) = setBpm(_bpm.value - step)

    // ─────────────────────────────────────────────────────────
    // TAP TEMPO
    // Algorithme : moyenne des intervalles entre les N derniers taps.
    // On ignore le premier tap (pas d'intervalle encore).
    // ─────────────────────────────────────────────────────────

    /**
     * Appelé à chaque tap de l'utilisateur.
     * Retourne le BPM calculé (ou null si pas encore assez de taps).
     */
    fun tap(): Double? {
        val now = System.currentTimeMillis()

        // Réinitialise si trop long depuis le dernier tap
        if (tapTimestamps.isNotEmpty() && now - tapTimestamps.last() > TAP_RESET_MS) {
            tapTimestamps.clear()
        }

        tapTimestamps.addLast(now)

        // Garde seulement les N derniers taps
        while (tapTimestamps.size > TAP_HISTORY_SIZE) {
            tapTimestamps.removeFirst()
        }

        // Besoin d'au moins 2 taps pour calculer un intervalle
        if (tapTimestamps.size < 2) return null

        // Calcule la moyenne des intervalles entre taps consécutifs
        var totalInterval = 0L
        for (i in 1 until tapTimestamps.size) {
            totalInterval += tapTimestamps[i] - tapTimestamps[i - 1]
        }
        val avgIntervalMs = totalInterval.toDouble() / (tapTimestamps.size - 1)

        // Convertit l'intervalle en BPM : BPM = 60000ms / intervalle_ms
        val calculatedBpm = (60000.0 / avgIntervalMs)
            .coerceIn(BPM_MIN, BPM_MAX)
            .let { (it * 10).roundToInt() / 10.0 } // Arrondi à 0.1

        _bpm.value = calculatedBpm
        return calculatedBpm
    }

    /**
     * Nombre de taps enregistrés (pour afficher l'avancement dans l'UI).
     */
    fun tapCount(): Int = tapTimestamps.size

    /**
     * Remet le tap tempo à zéro sans changer le BPM.
     */
    fun resetTap() = tapTimestamps.clear()

    // ─────────────────────────────────────────────────────────
    // Calculs utiles
    // ─────────────────────────────────────────────────────────

    /** Durée d'un beat en millisecondes */
    fun beatDurationMs(): Long = (60_000.0 / _bpm.value).toLong()

    /** Durée d'une mesure en millisecondes (4/4) */
    fun barDurationMs(beatsPerBar: Int = 4): Long = beatDurationMs() * beatsPerBar
}
