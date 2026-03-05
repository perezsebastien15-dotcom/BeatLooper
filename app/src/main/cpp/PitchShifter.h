#pragma once

#include <vector>
#include <cmath>
#include <complex>
#include <algorithm>
#include <cstring>

// ─────────────────────────────────────────────────────────────────────────────
//  PitchShifter — Pitch shifting + Time stretching indépendants
//
//  Algorithme : Phase Vocoder simplifié (OLA phase-aware)
//   • pitch  : modifie la hauteur sans changer la durée
//   • speed  : modifie la durée sans changer la hauteur
//   Les deux ensemble = combo complet
//
//  Appelé dans onAudioReady() pour chaque pad actif.
//  ⚠️  Pas d'allocation dans process() — tout est pré-alloué dans reset().
// ─────────────────────────────────────────────────────────────────────────────

static constexpr int PS_FRAME_SIZE  = 2048;   // Taille de la fenêtre FFT
static constexpr int PS_HOP_ANALYSIS = 512;   // Pas d'analyse (overlap 75%)
static constexpr int PS_HOP_SYNTH    = 512;   // Pas de synthèse (modifié par speed)

// Génère une fenêtre Hann de taille N
static inline std::vector<float> makeHannWindow(int n) {
    std::vector<float> w(n);
    for (int i = 0; i < n; ++i)
        w[i] = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / (n - 1)));
    return w;
}

// FFT/IFFT complexe simple (Cooley-Tukey iteratif)
static void fft(std::vector<std::complex<float>>& x, bool inverse) {
    int n = (int)x.size();
    // Bit reversal
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(x[i], x[j]);
    }
    // Butterflies
    for (int len = 2; len <= n; len <<= 1) {
        float ang = 2.0f * M_PI / len * (inverse ? 1 : -1);
        std::complex<float> wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            for (int j = 0; j < len / 2; ++j) {
                std::complex<float> u = x[i + j];
                std::complex<float> v = x[i + j + len/2] * w;
                x[i + j]         = u + v;
                x[i + j + len/2] = u - v;
                w *= wlen;
            }
        }
    }
    if (inverse) for (auto& c : x) c /= (float)n;
}

// ─────────────────────────────────────────────────────────────────────────────

class PitchShifter {
public:
    PitchShifter() {
        mWindow = makeHannWindow(PS_FRAME_SIZE);
        mInBuf.resize(PS_FRAME_SIZE, 0.0f);
        mOutBuf.resize(PS_FRAME_SIZE * 4, 0.0f);
        mLastPhase.resize(PS_FRAME_SIZE, 0.0f);
        mSumPhase.resize(PS_FRAME_SIZE, 0.0f);
        mAnalBuf.resize(PS_FRAME_SIZE);
        mSynthBuf.resize(PS_FRAME_SIZE);
        mOutputAccum.resize(PS_FRAME_SIZE * 8, 0.0f);
    }

    void reset() {
        std::fill(mInBuf.begin(), mInBuf.end(), 0.0f);
        std::fill(mOutBuf.begin(), mOutBuf.end(), 0.0f);
        std::fill(mLastPhase.begin(), mLastPhase.end(), 0.0f);
        std::fill(mSumPhase.begin(), mSumPhase.end(), 0.0f);
        std::fill(mOutputAccum.begin(), mOutputAccum.end(), 0.0f);
        mInputWritePos  = 0;
        mOutputReadPos  = 0;
        mOutputWritePos = 0;
        mLatency = PS_FRAME_SIZE;
    }

    // ── Traitement principal ──────────────────────────────────────────────────
    // Lit depuis `src` (position fractionnaire avec interpolation linéaire),
    // applique pitch shift + time stretch, écrit dans `dst`.
    //
    // @param src         Buffer source PCM float
    // @param srcLen      Longueur de src
    // @param playhead    Position fractionnaire courante dans src (modifiée in-place)
    // @param speedRatio  Vitesse de lecture (0.25–4.0), INDÉPENDANTE du pitch
    // @param pitchSemitones Décalage pitch en demi-tons (−24 à +24)
    // @param dst         Buffer de sortie
    // @param dstLen      Nombre de frames à produire
    // @returns           true si le sample est terminé (fin de src atteinte)
    bool process(
        const std::vector<float>& src,
        double& playhead,
        float speedRatio,
        float pitchSemitones,
        float* dst,
        int32_t dstLen,
        bool looping)
    {
        // Facteur de pitch : 2^(semitones/12)
        float pitchFactor = std::pow(2.0f, pitchSemitones / 12.0f);

        // Le time-stretch rate combine speed ET la correction nécessaire pour
        // que le pitch-shifting reste indépendant.
        // En phase vocoder : on lit les grains à (pitchFactor × speedRatio)
        // mais on les resynthétise à (speedRatio) → pitch = pitchFactor, durée = 1/speedRatio
        float readRate = pitchFactor * speedRatio;

        bool finished = false;

        for (int32_t outIdx = 0; outIdx < dstLen; ++outIdx) {
            // Rempli le buffer d'entrée OLA
            while (mOutputAccumCount < 1) {
                // Collecte PS_HOP_ANALYSIS samples depuis src (avec interpolation)
                for (int i = 0; i < PS_HOP_ANALYSIS; ++i) {
                    int ipos = (int)playhead;
                    float frac = (float)(playhead - ipos);

                    if (ipos >= (int)src.size() - 1) {
                        if (looping) {
                            playhead = std::fmod(playhead, (double)src.size());
                            ipos = (int)playhead;
                            frac = (float)(playhead - ipos);
                        } else {
                            finished = true;
                            // Rembourrage avec des zéros
                            for (int j = i; j < PS_FRAME_SIZE; ++j)
                                mInBuf[j] = 0.0f;
                            goto done_filling;
                        }
                    }

                    // Interpolation linéaire
                    float s0 = src[ipos];
                    float s1 = (ipos + 1 < (int)src.size()) ? src[ipos + 1] : 0.0f;
                    mInBuf[(mInputWritePos + i) % PS_FRAME_SIZE] = s0 + frac * (s1 - s0);
                    playhead += readRate;
                }
                done_filling:

                mInputWritePos = (mInputWritePos + PS_HOP_ANALYSIS) % PS_FRAME_SIZE;

                // Phase vocoder sur la fenêtre courante
                processFrame();
                mOutputAccumCount += PS_HOP_SYNTH;
            }

            // Lit un sample depuis l'accumulateur de sortie
            dst[outIdx] = mOutputAccum[mOutputReadPos % mOutputAccum.size()];
            mOutputAccum[mOutputReadPos % mOutputAccum.size()] = 0.0f;
            mOutputReadPos++;
            mOutputAccumCount--;
        }

        return finished;
    }

private:
    // ── Phase vocoder — traite une fenêtre ──────────────────────────────────
    void processFrame() {
        // Fenêtrage + FFT
        for (int i = 0; i < PS_FRAME_SIZE; ++i) {
            int idx = (mInputWritePos + i) % PS_FRAME_SIZE;
            mAnalBuf[i] = std::complex<float>(mInBuf[idx] * mWindow[i], 0.0f);
        }
        fft(mAnalBuf, false);

        // Analyse de phase — calcule les fréquences vraies
        for (int k = 0; k < PS_FRAME_SIZE; ++k) {
            float mag = std::abs(mAnalBuf[k]);
            float phase = std::arg(mAnalBuf[k]);

            // Différence de phase
            float delta = phase - mLastPhase[k];
            mLastPhase[k] = phase;

            // Soustrait la phase attendue
            float expectedDelta = 2.0f * M_PI * k * PS_HOP_ANALYSIS / PS_FRAME_SIZE;
            delta -= expectedDelta;

            // Ramène dans [−π, π]
            delta = delta - 2.0f * M_PI * std::round(delta / (2.0f * M_PI));

            // Fréquence vraie
            float trueFreq = (2.0f * M_PI * k / PS_FRAME_SIZE) + delta / PS_HOP_ANALYSIS;

            // Accumule la phase de synthèse
            mSumPhase[k] += PS_HOP_SYNTH * trueFreq;

            // Reconstruit le bin de synthèse
            mSynthBuf[k] = std::polar(mag, mSumPhase[k]);
        }

        // IFFT + overlap-add
        fft(mSynthBuf, true);
        for (int i = 0; i < PS_FRAME_SIZE; ++i) {
            int pos = (mOutputWritePos + i) % (int)mOutputAccum.size();
            mOutputAccum[pos] += mSynthBuf[i].real() * mWindow[i] * (2.0f / PS_FRAME_SIZE);
        }
        mOutputWritePos = (mOutputWritePos + PS_HOP_SYNTH) % (int)mOutputAccum.size();
    }

    std::vector<float>               mWindow;
    std::vector<float>               mInBuf;
    std::vector<float>               mOutBuf;
    std::vector<float>               mLastPhase;
    std::vector<float>               mSumPhase;
    std::vector<std::complex<float>> mAnalBuf;
    std::vector<std::complex<float>> mSynthBuf;
    std::vector<float>               mOutputAccum;

    int   mInputWritePos     = 0;
    int   mOutputReadPos     = 0;
    int   mOutputWritePos    = 0;
    int   mOutputAccumCount  = 0;
    int   mLatency           = PS_FRAME_SIZE;
};
