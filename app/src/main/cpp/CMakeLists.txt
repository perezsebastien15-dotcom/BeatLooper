cmake_minimum_required(VERSION 3.22.1)
project("beatlooper")

# ─────────────────────────────────────────────────────────────
# Intégration de la lib Oboe via Prefab (recommandé par Google)
# Oboe sera téléchargée automatiquement via la dépendance Gradle.
# ─────────────────────────────────────────────────────────────
find_package(oboe REQUIRED CONFIG)

# ── Bibliothèque native de l'app ──
add_library(
    beatlooper           # Nom de la lib (.so)
    SHARED               # Compilée en .so dynamique
    AudioEngine.cpp
    BeatLooperJNI.cpp
)

# ── Includes ──
target_include_directories(beatlooper PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ── Linkage ──
target_link_libraries(
    beatlooper
    oboe::oboe           # La lib Oboe
    android              # API Android
    log                  # __android_log_print
    atomic               # std::atomic
)

# ── Options de compilation ──
target_compile_options(beatlooper PRIVATE
    -O3                  # Optimisation maximale pour l'audio temps réel
    -ffast-math          # Math flottant plus rapide (OK pour audio)
    -Wall
)
