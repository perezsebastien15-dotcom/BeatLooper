package com.beatlooper.model

import androidx.compose.ui.graphics.Color

/**
 * Représente un pad de la soundboard.
 *
 * @param speed     Vitesse de lecture  — 0.25× à 4.0× (1.0 = normal)
 * @param pitch     Décalage de pitch   — −24 à +24 demi-tons (0 = normal)
 */
data class Pad(
    val id: Int,
    val color: Color,
    val label: String,
    val soundType: SoundType = SoundType.NONE,
    val soundPath: String? = null,
    val isLooping: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 0.0f        // en demi-tons
)

enum class SoundType {
    NONE,
    PRESET,
    RECORDED
}
