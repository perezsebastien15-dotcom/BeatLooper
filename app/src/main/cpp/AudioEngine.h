#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <vector>
#include <mutex>
#include <array>
#include "PitchShifter.h"

static constexpr int MAX_PADS    = 16;
static constexpr int SAMPLE_RATE = 48000;

struct PadVoice {
    std::vector<float>  samples;
    double              playhead   = 0.0;
    std::atomic<bool>   isPlaying{false};
    std::atomic<bool>   isLooping{false};
    std::atomic<float>  volume{1.0f};
    std::atomic<float>  speed{1.0f};    // 0.25 – 4.0
    std::atomic<float>  pitch{0.0f};    // demi-tons, -24 – +24
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

    void schedulePadOnNextBeat(int padId);
    void setBpm(double bpm);
    void setMetronomeEnabled(bool enabled);
    void setBeatsPerBar(int beats);
    int64_t getBeatCount();
    double getCurrentBpm();

    void startLoopRecord();
    void stopLoopRecord();
    void stopLoop();

    bool isRunning() const { return mIsRunning; }

private:
    oboe::ManagedStream          mStream;
    int32_t                      mSampleRate = SAMPLE_RATE;
    bool                         mIsRunning  = false;
    std::array<PadVoice, MAX_PADS> mVoices;

    std::atomic<double>   mBpm{120.0};
    std::atomic<bool>     mMetronomeEnabled{false};
    std::atomic<int64_t>  mBeatCount{0};
    int64_t               mClockPosition = 0;
    int                   mBeatsPerBar   = 4;

    std::vector<int>  mPendingPads;
    std::mutex        mPadMutex;

    std::vector<float>  mLoopBuffer;
    int32_t             mLoopPlayhead = 0;
    std::atomic<bool>   mLoopRecording{false};
    std::atomic<bool>   mLoopPlaying{false};

    void tickClock(int32_t numFrames, float* out, int32_t numSamples);
    void triggerPendingPads(int32_t sampleOffset);
    void mixActivePads(float* out, int32_t numFrames);
    void mixLoop(float* out, int32_t numFrames);
    void renderClick(float* out, int32_t numSamples, int32_t offsetFrames);
};
