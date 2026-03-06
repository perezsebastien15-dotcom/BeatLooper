#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AudioEngine::AudioEngine()  {}
AudioEngine::~AudioEngine() { stop(); }

// ─── Démarrage Oboe ───────────────────────────────────────────────────────────

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
        LOGE("Erreur stream: %s", oboe::convertToText(result));
        return false;
    }
    mSampleRate = mStream->getSampleRate();
    LOGI("Stream Oboe: %d Hz, bufferSize=%d",
         mSampleRate, mStream->getBufferSizeInFrames());

    // Pré-alloue le buffer looper pour éviter malloc dans le callback
    mLoopBuffer.assign(MAX_LOOP_SAMPLES, 0.0f);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Erreur start: %s", oboe::convertToText(result));
        return false;
    }
    mIsRunning = true;
    return true;
}

void AudioEngine::stop() {
    mIsRunning = false;
    if (mStream) { mStream->requestStop(); mStream->close(); mStream.reset(); }
}

// ─── Callback audio ───────────────────────────────────────────────────────────

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream*, void* audioData, int32_t numFrames) {

    auto* out = static_cast<float*>(audioData);
    memset(out, 0, numFrames * 2 * sizeof(float));

    tickClock(numFrames, out, numFrames * 2);
    mixActivePads(out, numFrames);

    // FIX LOOPER : capture le mix AVANT d'y ajouter la boucle
    if (mLoopRecording.load()) {
        recordLoop(out, numFrames);
    }

    mixLoop(out, numFrames);

    return oboe::DataCallbackResult::Continue;
}

// ─── Horloge BPM ──────────────────────────────────────────────────────────────

void AudioEngine::tickClock(int32_t numFrames, float* out, int32_t numSamples) {
    double bpm = mBpm.load();
    if (bpm <= 0) { mClockPosition += numFrames; return; }

    double samplesPerBeat = (double)mSampleRate * 60.0 / bpm;
    int32_t pos = 0;
    while (pos < numFrames) {
        double remaining = samplesPerBeat
            - std::fmod((double)(mClockPosition + pos), samplesPerBeat);
        int32_t toNext = (int32_t)std::ceil(remaining);
        if (pos + toNext <= numFrames) {
            pos += toNext;
            mClockPosition += toNext;
            mBeatCount++;
            triggerPendingPads(pos);
            if (mMetronomeEnabled.load()) renderClick(out, numSamples, pos);

            // FIX LOOPER : arret auto après loopLength samples
            if (mLoopRecording.load() && mLoopLength > 0
                    && mLoopWritePos >= mLoopLength) {
                mLoopRecording.store(false);
                mLoopPlayhead = 0;
                mLoopPlaying.store(true);
                LOGI("Loop auto-stop : %d samples enregistrés", mLoopWritePos);
            }
        } else {
            mClockPosition += (numFrames - pos);
            break;
        }
    }
}

// ─── Déclenchement immédiat (FIX LATENCE) ────────────────────────────────────

void AudioEngine::triggerPadNow(int padId) {
    if (padId < 0 || padId >= MAX_PADS) return;
    if (mVoices[padId].samples.empty()) return;
    mVoices[padId].trigger();
}

// ─── Quantisation BPM ─────────────────────────────────────────────────────────

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

// ─── Mixage pads ──────────────────────────────────────────────────────────────

void AudioEngine::mixActivePads(float* out, int32_t numFrames) {
    static float padBuf[4096];

    for (auto& voice : mVoices) {
        if (!voice.isPlaying.load() || voice.samples.empty()) continue;

        int32_t frames = std::min(numFrames, (int32_t)(sizeof(padBuf)/sizeof(float)));
        float spd = voice.speed.load();
        float pch = voice.pitch.load();
        bool finished = false;

        if (std::abs(spd - 1.0f) < 0.001f && std::abs(pch) < 0.001f) {
            // Lecture directe sans DSP
            for (int32_t i = 0; i < frames; ++i) {
                int ipos = (int)voice.playhead;
                float frac = (float)(voice.playhead - ipos);
                if (ipos >= (int)voice.samples.size() - 1) {
                    if (voice.isLooping.load()) {
                        voice.playhead = std::fmod(voice.playhead,
                                                   (double)voice.samples.size());
                        ipos = (int)voice.playhead; frac = 0.0f;
                    } else { finished = true; padBuf[i] = 0.0f; break; }
                }
                float s0 = voice.samples[ipos];
                float s1 = (ipos+1 < (int)voice.samples.size())
                           ? voice.samples[ipos+1] : 0.0f;
                padBuf[i] = s0 + frac*(s1-s0);
                voice.playhead += 1.0;
            }
        } else {
            // FIX PITCH/SPEED : le PitchShifter a une latence de PS_FRAME_SIZE
            // On continue à jouer même si les premiers samples sont silencieux
            // (le buffer se remplit progressivement)
            finished = voice.pitchShifter.process(
                voice.samples, voice.playhead,
                spd, pch, padBuf, frames,
                voice.isLooping.load()
            );
        }

        if (finished) voice.isPlaying.store(false);

        float vol = voice.volume.load();
        for (int32_t i = 0; i < frames; ++i) {
            out[i*2]   += padBuf[i] * vol;
            out[i*2+1] += padBuf[i] * vol;
        }
    }
}

// ─── Looper : enregistrement ──────────────────────────────────────────────────

void AudioEngine::recordLoop(const float* mixed, int32_t numFrames) {
    if (mLoopLength <= 0) return;  // longueur non définie

    for (int32_t i = 0; i < numFrames; ++i) {
        if (mLoopWritePos >= mLoopLength) break;
        // Mixe L+R en mono pour le buffer de boucle
        float mono = (mixed[i*2] + mixed[i*2+1]) * 0.5f;
        mLoopBuffer[mLoopWritePos++] = mono;
    }
}

// ─── Looper : lecture ─────────────────────────────────────────────────────────

void AudioEngine::mixLoop(float* out, int32_t numFrames) {
    if (!mLoopPlaying.load() || mLoopLength <= 0) return;
    for (int32_t i = 0; i < numFrames; ++i) {
        if (mLoopPlayhead >= mLoopLength) mLoopPlayhead = 0;
        float s = mLoopBuffer[mLoopPlayhead++];
        out[i*2]   += s;
        out[i*2+1] += s;
    }
}

// FIX LOOPER : calcule la longueur en samples depuis les beats + BPM
void AudioEngine::startLoopRecord(int beats) {
    double bpm = mBpm.load();
    if (bpm <= 0) bpm = 120.0;

    // Longueur = beats × samples_par_beat, arrondie au buffer Oboe suivant
    double samplesPerBeat = (double)mSampleRate * 60.0 / bpm;
    int32_t length = (int32_t)(beats * samplesPerBeat);
    length = std::min(length, MAX_LOOP_SAMPLES);

    // Remet à zéro le buffer (déjà alloué)
    std::fill(mLoopBuffer.begin(), mLoopBuffer.begin() + length, 0.0f);

    mLoopLength    = length;
    mLoopWritePos  = 0;
    mLoopPlayhead  = 0;
    mLoopPlaying.store(false);
    mLoopRecording.store(true);

    LOGI("Loop record démarré : %d beats, %d samples (%.1f s)",
         beats, length, length / (double)mSampleRate);
}

void AudioEngine::stopLoopRecord() {
    if (!mLoopRecording.load()) return;
    mLoopRecording.store(false);
    // Coupe au point d'écriture réel si arrêt anticipé
    if (mLoopWritePos > 0) {
        mLoopLength   = mLoopWritePos;
        mLoopPlayhead = 0;
        mLoopPlaying.store(true);
        LOGI("Loop stop manuel : %d samples", mLoopLength);
    }
}

void AudioEngine::stopLoop() {
    mLoopRecording.store(false);
    mLoopPlaying.store(false);
    mLoopPlayhead  = 0;
    mLoopWritePos  = 0;
    mLoopLength    = 0;
}

// ─── Métronome ────────────────────────────────────────────────────────────────

void AudioEngine::renderClick(float* out, int32_t numSamples, int32_t offsetFrames) {
    bool isDown = (mBeatCount.load() % mBeatsPerBar == 0);
    float freq  = isDown ? 1000.0f : 800.0f;
    int32_t clickSamples = mSampleRate / 100;
    for (int32_t i = 0; i < clickSamples; ++i) {
        int32_t frame = offsetFrames + i;
        if (frame * 2 + 1 >= numSamples) break;
        float env = 1.0f - (float)i / clickSamples;
        float s = std::sin(2.0f * M_PI * freq * i / mSampleRate) * env * 0.25f;
        out[frame*2]   += s;
        out[frame*2+1] += s;
    }
}

// ─── Setters ──────────────────────────────────────────────────────────────────

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
    mVoices[padId].speed.store(std::max(0.25f, std::min(4.0f, speed)));
}

void AudioEngine::setPadPitch(int padId, float semitones) {
    if (padId < 0 || padId >= MAX_PADS) return;
    mVoices[padId].pitch.store(std::max(-24.0f, std::min(24.0f, semitones)));
}

void AudioEngine::setBpm(double bpm)           { mBpm.store(bpm); }
void AudioEngine::setMetronomeEnabled(bool e)  { mMetronomeEnabled.store(e); }
void AudioEngine::setBeatsPerBar(int b)        { mBeatsPerBar = b; }
int64_t AudioEngine::getBeatCount()            { return mBeatCount.load(); }
double AudioEngine::getCurrentBpm()            { return mBpm.load(); }
