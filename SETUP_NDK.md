# BeatLooper — Guide de démarrage NDK + Oboe

## Prérequis : installer le NDK dans Android Studio

### Étape 1 — Installer le NDK

1. Ouvre Android Studio
2. **File → Settings → Appearance & Behavior → System Settings → Android SDK**
3. Onglet **SDK Tools**
4. Coche **NDK (Side by side)** et **CMake**
5. Clique **OK** et laisse tout télécharger (~1 Go)

> **Version recommandée** : NDK 25+ (LTS). Si Android Studio te propose plusieurs versions, prends la dernière stable.

---

### Étape 2 — Ouvrir le projet

1. **File → Open** → sélectionne le dossier `BeatLooper/`
2. Android Studio va détecter le `CMakeLists.txt` automatiquement
3. Attends que la sync Gradle se termine (peut prendre 2–3 minutes la 1ère fois)

---

### Étape 3 — Vérifier la config NDK

Dans `File → Project Structure → SDK Location`, tu dois voir :
- **Android SDK location** : rempli
- **NDK location** : rempli (ex: `/Users/toi/Library/Android/sdk/ndk/25.x.x`)

Si NDK location est vide : clique **...** et sélectionne le dossier NDK installé.

---

### Étape 4 — Ajouter tes fichiers audio

Place tes samples dans `app/src/main/res/raw/` :
```
res/raw/
├── kick.wav
├── snare.wav
├── hihat.wav
├── clap.wav
├── bass.wav
├── piano_c.wav
├── piano_d.wav
└── piano_e.wav
```

Puis dans `PadConfigDialog.kt`, remplace :
```kotlin
// onAssignPreset(label, R.raw.xxx)
```
par :
```kotlin
onAssignPreset(label, when(label) {
    "Kick" -> R.raw.kick
    "Snare" -> R.raw.snare
    "Hi-Hat" -> R.raw.hihat
    else -> R.raw.kick
})
```

---

### Étape 5 — Lancer sur device réel

**⚠️ Ne PAS utiliser l'émulateur pour tester l'audio Oboe.**
L'émulateur ne supporte pas AAudio correctement → latence désastreuse.

1. Active le **mode développeur** sur ton Android (paramètres → à propos → tape 7x sur "Numéro de build")
2. Active **Débogage USB**
3. Branche le téléphone
4. Clique **Run ▶** dans Android Studio

---

## Architecture BPM + Quantisation

```
┌─────────────────┐    setBpm()      ┌──────────────────────┐
│   BpmClock.kt   │ ───────────────► │   OboeEngine.kt      │
│  (tap tempo /   │                  │  (wrapper JNI)       │
│   saisie)       │                  └──────────┬───────────┘
└─────────────────┘                             │ nativeSetBpm()
                                                ▼
                                    ┌──────────────────────┐
                                    │   AudioEngine.cpp    │
                                    │                      │
                                    │  onAudioReady()      │◄── Thread Oboe (temps réel)
                                    │   └─ tickClock()     │    Appelé ~toutes les 5ms
                                    │       └─ beat ?      │
                                    │           └─ trigger │
                                    │              pending │
                                    │              pads    │
                                    └──────────────────────┘
```

### Flux de la quantisation

1. Utilisateur tape un pad → `onPadTap()` dans ViewModel
2. → `oboeEngine.schedulePadOnNextBeat(padId)` (Kotlin)
3. → `nativeSchedulePadOnNextBeat()` (JNI)
4. → `mPendingPads.push_back(padId)` (C++, thread-safe via mutex)
5. Au prochain beat détecté dans `onAudioReady()` → son déclenché **exactement** au bon sample

### Précision temporelle

Avec Oboe en mode `LowLatency + Exclusive` :
- Buffer typique : **96–256 samples** à 48kHz = **2–5ms**
- La quantisation est précise à ±1 buffer près, soit **±2–5ms** max
- C'est suffisant pour une utilisation musicale (seuil de perception : ~10ms)

---

## Troubleshooting

| Problème | Solution |
|----------|----------|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Vérifie que `abiFilters` inclut l'archi de ton device |
| Pas de son | Vérifie `RECORD_AUDIO` permission acceptée |
| App crash au démarrage | Check Logcat tag `BeatLooperJNI` |
| CMake introuvable | SDK Tools → install CMake 3.22+ |
| NDK build error | Vérifie que Oboe 1.8.0 est bien téléchargé (sync Gradle) |
