#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

AudioEngine::AudioEngine()  {}
AudioEngine::~AudioEngine() { stop(); }

// ─── Démarrage Oboe ──────────────────────────────────────────────────────────

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
           ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
           ->setSharingMode(oboe::SharingMode::Exclusive)
           ->setFormat(oboe::AudioFormat::Float)
           ->setChannelCount(oboe::ChannelCount::Stereo)
           ->setSampleRate(SAMPLE_RATE)
           ->setDataCallback(this);

    oboe::Result result = builder.openManagedStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Erreur ouverture stream: %s", oboe::convertToText(result));
        return false;
    }
    mSampleRate = mStream->getSampleRate();
    LOGI("Stream Oboe: %d Hz", mSampleRate);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) { LOGE("Erreur start: %s", oboe::convertToText(result)); return false; }
    mIsRunning = true;
    return true;
}

void AudioEngine::stop() {
    mIsRunning = false;
    if (mStream) { mStream->requestStop(); mStream->close(); mStream.reset(); }
}

// ─── Callback audio ──────────────────────────────────────────────────────────
// Appelé par Oboe depuis son thread dédié — règles strictes :
//   pas de new/malloc, pas de lock bloquant, pas d'I/O

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream*, void* audioData, int32_t numFrames) {

    auto* out = static_cast<float*>(audioData);
    memset(out, 0, numFrames * 2 * sizeof(float));

    tickClock(numFrames, out, numFrames * 2);
    mixActivePads(out, numFrames);
    mixLoop(out, numFrames);

    return oboe::DataCallbackResult::Continue;
}

// ─── Horloge BPM ─────────────────────────────────────────────────────────────

void AudioEngine::tickClock(int32_t numFrames, float* out, int32_t numSamples) {
    double bpm = mBpm.load();
    if (bpm <= 0) { mClockPosition += numFrames; return; }

    double samplesPerBeat = (double)mSampleRate * 60.0 / bpm;
    int32_t pos = 0;
    while (pos < numFrames) {
        double remaining = samplesPerBeat - std::fmod((double)(mClockPosition + pos), samplesPerBeat);
        int32_t toNext = (int32_t)std::ceil(remaining);
        if (pos + toNext <= numFrames) {
            pos += toNext;
            mClockPosition += toNext;
            mBeatCount++;
            triggerPendingPads(pos);
            if (mMetronomeEnabled.load()) renderClick(out, numSamples, pos);
        } else {
            mClockPosition += (numFrames - pos);
            break;
        }
    }
}

// ─── Quantisation ────────────────────────────────────────────────────────────

void AudioEngine::schedulePadOnNextBeat(int padId) {
    if (mPadMutex.try_lock()) {
        mPendingPads.push_back(padId);
        mPadMutex.unlock();
    }
}

void AudioEngine::triggerPendingPads(int32_t /*sampleOffset*/) {
    if (!mPadMutex.try_lock()) return;
    for (int id : mPendingPads) {
        if (id >= 0 && id < MAX_PADS && !mVoices[id].samples.empty())
            mVoices[id].trigger();
    }
    mPendingPads.clear();
    mPadMutex.unlock();
}

// ─── Mixage des pads — avec pitch shifting + time stretching ─────────────────

void AudioEngine::mixActivePads(float* out, int32_t numFrames) {
    // Buffer temporaire par pad (pour ne pas allouer dans le callback)
    static float padBuf[4096];

    for (auto& voice : mVoices) {
        if (!voice.isPlaying.load() || voice.samples.empty()) continue;

        int32_t frames = std::min(numFrames, (int32_t)(sizeof(padBuf) / sizeof(float)));
        float spd = voice.speed.load();
        float pch = voice.pitch.load();

        bool finished = false;

        if (std::abs(spd - 1.0f) < 0.001f && std::abs(pch) < 0.001f) {
            // ── Cas simple : pas de traitement, lecture directe (zéro coût CPU) ──
            for (int32_t i = 0; i < frames; ++i) {
                int ipos = (int)voice.playhead;
                float frac = (float)(voice.playhead - ipos);
                if (ipos >= (int)voice.samples.size() - 1) {
                    if (voice.isLooping.load()) {
                        voice.playhead = std::fmod(voice.playhead, (double)voice.samples.size());
                        ipos = (int)voice.playhead; frac = 0.0f;
                    } else { finished = true; padBuf[i] = 0.0f; break; }
                }
                float s0 = voice.samples[ipos];
                float s1 = (ipos + 1 < (int)voice.samples.size()) ? voice.samples[ipos + 1] : 0.0f;
                padBuf[i] = s0 + frac * (s1 - s0);
                voice.playhead += 1.0;
            }
        } else {
            // ── Phase vocoder : pitch + speed indépendants ──
            finished = voice.pitchShifter.process(
                voice.samples, voice.playhead,
                spd, pch,
                padBuf, frames,
                voice.isLooping.load()
            );
        }

        if (finished) voice.isPlaying.store(false);

        // Add to stereo output
        float vol = voice.volume.load();
        for (int32_t i = 0; i < frames; ++i) {
            out[i * 2]     += padBuf[i] * vol;
            out[i * 2 + 1] += padBuf[i] * vol;
        }
    }
}

// ─── Looper ──────────────────────────────────────────────────────────────────

void AudioEngine::mixLoop(float* out, int32_t numFrames) {
    if (!mLoopPlaying.load() || mLoopBuffer.empty()) return;
    for (int32_t i = 0; i < numFrames; ++i) {
        if (mLoopPlayhead >= (int32_t)mLoopBuffer.size()) mLoopPlayhead = 0;
        float s = mLoopBuffer[mLoopPlayhead++];
        out[i * 2]     += s;
        out[i * 2 + 1] += s;
    }
}

void AudioEngine::startLoopRecord() {
    mLoopBuffer.clear();
    mLoopRecording.store(true);
    mLoopPlaying.store(false);
}

void AudioEngine::stopLoopRecord() {
    mLoopRecording.store(false);
    if (!mLoopBuffer.empty()) { mLoopPlayhead = 0; mLoopPlaying.store(true); }
}

void AudioEngine::stopLoop() {
    mLoopRecording.store(false);
    mLoopPlaying.store(false);
    mLoopPlayhead = 0;
}

// ─── Métronome ───────────────────────────────────────────────────────────────

void AudioEngine::renderClick(float* out, int32_t numSamples, int32_t offsetFrames) {
    bool isDown = (mBeatCount.load() % mBeatsPerBar == 0);
    float freq = isDown ? 1000.0f : 800.0f;
    int32_t clickSamples = mSampleRate / 100;
    for (int32_t i = 0; i < clickSamples; ++i) {
        int32_t frame = offsetFrames + i;
        if (frame * 2 + 1 >= numSamples) break;
        float env = 1.0f - (float)i / clickSamples;
        float s = std::sin(2.0f * M_PI * freq * i / mSampleRate) * env * 0.25f;
        out[frame * 2]     += s;
        out[frame * 2 + 1] += s;
    }
}

// ─── Setters ─────────────────────────────────────────────────────────────────

void AudioEngine::loadSample(int padId, const float* data, int32_t n) {
    if (padId < 0 || padId >= MAX_PADS) return;
    auto& v = mVoices[padId];
    v.isPlaying.store(false);
    v.samples.assign(data, data + n);
    v.playhead = 0.0;
    v.pitchShifter.reset();
}

void AudioEngine::setLooping(int padId, bool loop) {
    if (padId < 0 || padId >= MAX_PADS) return;
    mVoices[padId].isLooping.store(loop);
}

void AudioEngine::stopPad(int padId) {
    if (padId < 0 || padId >= MAX_PADS) return;
    mVoices[padId].isPlaying.store(false);
}

void AudioEngine::setPadSpeed(int padId, float speed) {
    if (padId < 0 || padId >= MAX_PADS) return;
    // Clamp 0.25 – 4.0
    mVoices[padId].speed.store(std::max(0.25f, std::min(4.0f, speed)));
}

void AudioEngine::setPadPitch(int padId, float semitones) {
    if (padId < 0 || padId >= MAX_PADS) return;
    // Clamp −24 – +24
    mVoices[padId].pitch.store(std::max(-24.0f, std::min(24.0f, semitones)));
}

void AudioEngine::setBpm(double bpm)              { mBpm.store(bpm); }
void AudioEngine::setMetronomeEnabled(bool e)     { mMetronomeEnabled.store(e); }
void AudioEngine::setBeatsPerBar(int b)           { mBeatsPerBar = b; }
int64_t AudioEngine::getBeatCount()               { return mBeatCount.load(); }
double AudioEngine::getCurrentBpm()               { return mBpm.load(); }
