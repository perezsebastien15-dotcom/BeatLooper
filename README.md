# BeatLooper — Squelette Android

Application Android combinant une soundboard 4×4 et un looper audio avec overdub.

## Structure du projet

```
app/src/main/java/com/beatlooper/
├── model/
│   ├── Pad.kt           → Données d'un pad (couleur, son, mode boucle)
│   └── LoopState.kt     → État de la boucle en cours
├── audio/
│   ├── SoundEngine.kt   → Lecture des sons via SoundPool
│   ├── MicRecorder.kt   → Enregistrement micro → fichier WAV
│   ├── LoopEngine.kt    → Moteur de boucle (record / play / overdub)
│   └── AudioExporter.kt → Export WAV vers le stockage du téléphone
└── ui/
    ├── MainActivity.kt  → Point d'entrée + UI Jetpack Compose
    └── MainViewModel.kt → ViewModel central (état + logique)
```

## Fonctionnalités implémentées

- [x] Grille 4×4 de pads colorés
- [x] Tap = jouer le son, Long press = configurer
- [x] Sons préchargés (res/raw)
- [x] Enregistrement micro → assignation à un pad
- [x] Mode boucle par pad (SoundPool loop infini)
- [x] Looper : enregistrement → lecture automatique en boucle
- [x] Overdub : ajout de son par-dessus la boucle (mixage PCM)
- [x] Export WAV dans Music/BeatLooper/

## Étapes pour démarrer

### 1. Ajouter des sons dans `res/raw/`

Remplace les noms dans `SoundEngine.kt` (`R.raw.kick`, etc.) par tes vrais fichiers `.wav` ou `.ogg`.

### 2. Lancer sur un device réel

L'émulateur Android ne gère pas bien l'audio bas niveau. **Teste sur un vrai téléphone.**

### 3. Permissions

Demandées automatiquement au lancement : `RECORD_AUDIO` + stockage.

## Roadmap V2

- [ ] BPM + grille temporelle pour synchroniser les boucles
- [ ] Visualiseur de forme d'onde
- [ ] Effets audio (reverb, delay) via AudioEffect
- [ ] Export MP3 (via MediaCodec)
- [ ] Migration Oboe (C++/NDK) pour latence < 10ms
- [ ] Sauvegarde/chargement de sessions

## Points techniques importants

**Latence** : SoundPool donne ~40–80ms de latence selon le device. Pour une latence
professionnelle (< 10ms), migrer vers la lib Oboe de Google (C++ via NDK).

**Mixage PCM** : Le mixage overdub est une simple somme clampée des samples. Pour
un meilleur mixage, normaliser par le nombre de pistes.

**Thread audio** : Ne jamais faire d'UI ou d'I/O dans le thread audio (LoopEngine
utilise Dispatchers.IO, ce qui est acceptable pour ce niveau).
