package com.beatlooper.model

/**
 * État d'une boucle en cours.
 */
data class LoopState(
    val isRecording: Boolean = false,   // Enregistrement de la boucle en cours
    val isPlaying: Boolean = false,     // Lecture de la boucle en cours
    val isOverdubbing: Boolean = false, // Overdub actif (ajout de son par-dessus)
    val durationMs: Long = 0L,          // Durée de la boucle en ms
    val pcmBuffer: ShortArray = ShortArray(0) // Buffer PCM de la boucle
)
