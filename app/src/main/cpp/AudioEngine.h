#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <vector>
#include <mutex>
#include <array>
#include "PitchShifter.h"

static constexpr int MAX_PADS    = 16;
static constexpr int SAMPLE_RATE = 48000;
// Looper : max 30 secondes mono à 48kHz
static constexpr int MAX_LOOP_SAMPLES = SAMPLE_RATE * 30;

struct PadVoice {
    std::vector<float>  samples;
    double              playhead   = 0.0;
    std::atomic<bool>   isPlaying{false};
    std::atomic<bool>   isLooping{false};
    std::atomic<float>  volume{1.0f};
    std::atomic<float>  speed{1.0f};
    std::atomic<float>  pitch{0.0f};
    PitchShifter        pitchShifter;

    void trigger() {
        playhead = 0.0;
        pitchShifter.reset();
        isPlaying.store(true);
    }
};

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start();
    void stop();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

    void loadSample(int padId, const float* data, int32_t numSamples);
    void setLooping(int padId, bool loop);
    void stopPad(int padId);
    void setPadSpeed(int padId, float speed);
    void setPadPitch(int padId, float semitones);

    // FIX LATENCE : triggerPadNow joue immédiatement sans attendre le beat
    void triggerPadNow(int padId);
    // Quantisé au prochain beat (garde pour le mode rythmique)
    void schedulePadOnNextBeat(int padId);

    void setBpm(double bpm);
    void setMetronomeEnabled(bool enabled);
    void setBeatsPerBar(int beats);
    int64_t getBeatCount();
    double getCurrentBpm();

    // FIX LOOPER : startLoopRecord prend une longueur en beats pour sync BPM
    void startLoopRecord(int beats = 4);
    void stopLoopRecord();
    void stopLoop();

    bool isRunning() const { return mIsRunning; }

private:
    oboe::ManagedStream            mStream;
    int32_t                        mSampleRate = SAMPLE_RATE;
    bool                           mIsRunning  = false;
    std::array<PadVoice, MAX_PADS> mVoices;

    std::atomic<double>   mBpm{120.0};
    std::atomic<bool>     mMetronomeEnabled{false};
    std::atomic<int64_t>  mBeatCount{0};
    int64_t               mClockPosition = 0;
    int                   mBeatsPerBar   = 4;

    std::vector<int>  mPendingPads;
    std::mutex        mPadMutex;

    // FIX LOOPER : buffer pré-alloué + longueur fixée au démarrage
    std::vector<float>  mLoopBuffer;
    int32_t             mLoopPlayhead   = 0;
    int32_t             mLoopLength     = 0;   // longueur en samples, 0 = non défini
    int32_t             mLoopWritePos   = 0;   // position d'écriture pendant rec
    std::atomic<bool>   mLoopRecording{false};
    std::atomic<bool>   mLoopPlaying{false};

    void tickClock(int32_t numFrames, float* out, int32_t numSamples);
    void triggerPendingPads(int32_t sampleOffset);
    void mixActivePads(float* out, int32_t numFrames);
    void recordLoop(const float* mixed, int32_t numFrames);   // FIX : capture le mix
    void mixLoop(float* out, int32_t numFrames);
    void renderClick(float* out, int32_t numSamples, int32_t offsetFrames);
};
