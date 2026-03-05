package com.beatlooper.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.beatlooper.R
import com.beatlooper.model.Pad
import com.beatlooper.model.SoundType

/**
 * Gère le chargement et la lecture des sons via SoundPool.
 * SoundPool est idéal pour les sons courts (< 5s) avec faible latence.
 *
 * Pour une latence encore plus faible (pro), migrer vers Oboe (C++/JNI).
 */
class SoundEngine(private val context: Context) {

    private val TAG = "SoundEngine"

    // SoundPool configuré pour l'audio musical
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(16) // 16 streams simultanés (1 par pad)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)        // Latence optimisée jeu
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // Map padId -> soundPoolId (id retourné par soundPool.load)
    private val loadedSounds = mutableMapOf<Int, Int>()

    // Map padId -> streamId (id du stream en cours de lecture)
    private val activeStreams = mutableMapOf<Int, Int>()

    // Sons préchargés disponibles : label -> ressource R.raw.*
    // ⚠️ Remplacer par de vrais fichiers dans res/raw/
    val presetSounds: Map<String, Int> = mapOf(
        "Kick"      to R.raw.kick,
        "Snare"     to R.raw.snare,
        "Hi-Hat"    to R.raw.hihat,
        "Clap"      to R.raw.clap,
        "Bass"      to R.raw.bass,
        "Piano Do"  to R.raw.piano_c,
        "Piano Ré"  to R.raw.piano_d,
        "Piano Mi"  to R.raw.piano_e,
    )

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) Log.e(TAG, "Erreur chargement son $sampleId")
        }
    }

    /**
     * Charge un son préchargé (res/raw) pour un pad.
     */
    fun loadPreset(padId: Int, resId: Int) {
        unloadPad(padId)
        val soundId = soundPool.load(context, resId, 1)
        loadedSounds[padId] = soundId
    }

    /**
     * Charge un son depuis un fichier (enregistrement micro).
     */
    fun loadFromFile(padId: Int, filePath: String) {
        unloadPad(padId)
        val soundId = soundPool.load(filePath, 1)
        loadedSounds[padId] = soundId
    }

    /**
     * Déclenche la lecture du son associé au pad.
     * @param loop true = boucle infinie, false = lecture unique
     */
    fun playPad(pad: Pad) {
        val soundId = loadedSounds[pad.id] ?: run {
            Log.w(TAG, "Aucun son chargé pour le pad ${pad.id}")
            return
        }
        // Arrêter le stream précédent si boucle active
        stopPad(pad.id)

        val loopCount = if (pad.isLooping) -1 else 0  // -1 = boucle infinie
        val streamId = soundPool.play(
            soundId,
            1f, 1f,   // volume gauche / droite
            1,        // priorité
            loopCount,
            1f        // vitesse de lecture (1.0 = normale)
        )
        activeStreams[pad.id] = streamId
    }

    /**
     * Arrête la lecture d'un pad.
     */
    fun stopPad(padId: Int) {
        activeStreams[padId]?.let { soundPool.stop(it) }
        activeStreams.remove(padId)
    }

    /**
     * Libère les ressources d'un pad.
     */
    private fun unloadPad(padId: Int) {
        stopPad(padId)
        loadedSounds[padId]?.let { soundPool.unload(it) }
        loadedSounds.remove(padId)
    }

    /**
     * Libère toutes les ressources SoundPool.
     */
    fun release() {
        soundPool.release()
        loadedSounds.clear()
        activeStreams.clear()
    }
}
