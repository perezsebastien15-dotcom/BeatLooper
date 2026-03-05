package com.beatlooper.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exporte le buffer PCM de la boucle en fichier WAV
 * dans le dossier Music/ du téléphone.
 */
object AudioExporter {

    private const val TAG = "AudioExporter"

    /**
     * Exporte vers MediaStore (Android 10+) ou stockage externe (< Android 10).
     * @param pcm       Buffer PCM 16 bits mono
     * @param sampleRate Fréquence d'échantillonnage (ex: 44100)
     * @param fileName  Nom du fichier sans extension
     * @return true si succès
     */
    fun exportWav(context: Context, pcm: ShortArray, sampleRate: Int, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, pcm, sampleRate, fileName)
            } else {
                exportToLegacyStorage(pcm, sampleRate, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur export : ${e.message}", e)
            false
        }
    }

    // Android 10+ : passe par MediaStore (pas besoin de WRITE_EXTERNAL_STORAGE)
    private fun exportViaMediaStore(
        context: Context, pcm: ShortArray, sampleRate: Int, fileName: String
    ): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$fileName.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BeatLooper")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        resolver.openOutputStream(uri)?.use { out ->
            out.write(buildWavBytes(pcm, sampleRate))
        }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Log.d(TAG, "Export MediaStore OK : $fileName.wav")
        return true
    }

    // Android < 10 : écriture directe (nécessite WRITE_EXTERNAL_STORAGE)
    private fun exportToLegacyStorage(pcm: ShortArray, sampleRate: Int, fileName: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "BeatLooper"
        ).also { it.mkdirs() }

        val file = File(dir, "$fileName.wav")
        FileOutputStream(file).use { it.write(buildWavBytes(pcm, sampleRate)) }

        Log.d(TAG, "Export OK : ${file.absolutePath}")
        return true
    }

    // ─────────────────────────────────────────────────
    // Construction du fichier WAV
    // ─────────────────────────────────────────────────

    private fun buildWavBytes(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val totalSize = dataSize + 44

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header RIFF
        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize - 8)       // Taille fichier - 8
        buf.put("WAVE".toByteArray())

        // Chunk fmt
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                  // Taille chunk fmt
        buf.putShort(1)                 // PCM
        buf.putShort(1)                 // Mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)      // ByteRate
        buf.putShort(2)                 // BlockAlign
        buf.putShort(16)                // BitsPerSample

        // Chunk data
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        pcm.forEach { buf.putShort(it) }

        return buf.array()
    }
}
