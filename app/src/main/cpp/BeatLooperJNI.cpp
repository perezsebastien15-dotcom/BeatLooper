#include <jni.h>
#include <memory>
#include "AudioEngine.h"
#include <android/log.h>

#define LOG_TAG "BeatLooperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Instance globale du moteur (singleton)
static std::unique_ptr<AudioEngine> gEngine;

// ─────────────────────────────────────────────────────────────
// Cycle de vie du moteur
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeCreate(JNIEnv*, jobject) {
    gEngine = std::make_unique<AudioEngine>();
    bool ok = gEngine->start();
    LOGI("AudioEngine créé : %s", ok ? "OK" : "ERREUR");
    return ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeDestroy(JNIEnv*, jobject) {
    if (gEngine) {
        gEngine->stop();
        gEngine.reset();
    }
}

// ─────────────────────────────────────────────────────────────
// Chargement d'un sample (depuis Kotlin, en float[])
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeLoadSample(
        JNIEnv* env, jobject,
        jint padId,
        jfloatArray samples) {

    jsize length = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);

    if (gEngine && data) {
        gEngine->loadSample(padId, data, length);
        LOGI("Sample chargé sur pad %d (%d samples)", padId, length);
    }

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

// ─────────────────────────────────────────────────────────────
// Quantisation — déclenche un pad au prochain beat
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSchedulePadOnNextBeat(
        JNIEnv*, jobject, jint padId) {
    if (gEngine) gEngine->schedulePadOnNextBeat(padId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeStopPad(
        JNIEnv*, jobject, jint padId) {
    if (gEngine) gEngine->stopPad(padId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetLooping(
        JNIEnv*, jobject, jint padId, jboolean loop) {
    if (gEngine) gEngine->setLooping(padId, loop);
}

// ─────────────────────────────────────────────────────────────
// BPM
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetBpm(
        JNIEnv*, jobject, jdouble bpm) {
    if (gEngine) gEngine->setBpm(bpm);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeGetBpm(JNIEnv*, jobject) {
    return gEngine ? gEngine->getCurrentBpm() : 120.0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeGetBeatCount(JNIEnv*, jobject) {
    return gEngine ? gEngine->getBeatCount() : 0L;
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetMetronomeEnabled(
        JNIEnv*, jobject, jboolean enabled) {
    if (gEngine) gEngine->setMetronomeEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetBeatsPerBar(
        JNIEnv*, jobject, jint beats) {
    if (gEngine) gEngine->setBeatsPerBar(beats);
}

// ─────────────────────────────────────────────────────────────
// Looper
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeStartLoopRecord(JNIEnv*, jobject) {
    if (gEngine) gEngine->startLoopRecord();
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeStopLoopRecord(JNIEnv*, jobject) {
    if (gEngine) gEngine->stopLoopRecord();
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeStopLoop(JNIEnv*, jobject) {
    if (gEngine) gEngine->stopLoop();
}

// ─── Pitch & Speed (ajouts) ───────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetPadSpeed(
        JNIEnv*, jobject, jint padId, jfloat speed) {
    if (gEngine) gEngine->setPadSpeed(padId, speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_beatlooper_audio_OboeEngine_nativeSetPadPitch(
        JNIEnv*, jobject, jint padId, jfloat semitones) {
    if (gEngine) gEngine->setPadPitch(padId, semitones);
}
