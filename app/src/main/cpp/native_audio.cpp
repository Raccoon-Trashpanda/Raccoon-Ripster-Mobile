// Ripster native audio — фаза 1 + очередь/гэплесс/DSP-зачатки.
//
// Архитектура: рабочий поток декодирует в кольцевой буфер, аудио-callback
// Oboe только сливает из буфера. Даёт:
//  · гэплесс: worker переходит через границу трека, не прерывая поток;
//  · очередь: loadQueue([fd…]) + native next/prev/seek;
//  · развязку: подвисание декодера не рвёт звук (underrun → тишина, догонит);
//  · DSP-зачаток: программная громкость с TPDF-дизером; линейный ресемпл для
//    треков с частотой ≠ частоте потока (честно помечаем «не bit-perfect»).
//
// Декод: dr_flac / dr_wav по fd (SAF отдаёт content:// → fd). ALAC/APE/WavPack,
// нормальный полифазный SRC, USB-ЦАП — следующие фазы (трекер s-au).

#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <vector>
#include <deque>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <memory>

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

#include "dr_flac.h"
#include "dr_wav.h"
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

#define LOG_TAG "RipsterAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ── источник: сырой fd, позиционируемое чтение ─────────────────────────────
struct FdSource {
    int     fd  = -1;
    int64_t pos = 0;
};
size_t fd_read(void* user, void* out, size_t bytes) {
    auto* s = static_cast<FdSource*>(user);
    ssize_t n = ::pread(s->fd, out, bytes, s->pos);
    if (n <= 0) return 0;
    s->pos += n;
    return static_cast<size_t>(n);
}
drflac_bool32 flac_seek(void* user, int off, drflac_seek_origin o) {
    auto* s = static_cast<FdSource*>(user);
    if (o == DRFLAC_SEEK_CUR) s->pos += off; else if (o == DRFLAC_SEEK_END) s->pos = -1; else s->pos = off;
    if (s->pos < 0) s->pos = 0;
    return DRFLAC_TRUE;
}
drflac_bool32 flac_tell(void* user, drflac_int64* c) { *c = static_cast<FdSource*>(user)->pos; return DRFLAC_TRUE; }
drwav_bool32 wav_seek(void* user, int off, drwav_seek_origin o) {
    auto* s = static_cast<FdSource*>(user);
    if (o == DRWAV_SEEK_CUR) s->pos += off; else if (o == DRWAV_SEEK_END) s->pos = -1; else s->pos = off;
    if (s->pos < 0) s->pos = 0;
    return DRWAV_TRUE;
}
drwav_bool32 wav_tell(void* user, drwav_int64* c) { *c = static_cast<FdSource*>(user)->pos; return DRWAV_TRUE; }

// ── один декодер (FLAC | WAV), интерливнутый f32 наружу ────────────────────
struct Decoder {
    FdSource src{};
    int      fmt      = -1;   // 0 flac, 1 wav, 2 alac(m4a)
    drflac*  flac     = nullptr;
    drwav    wav{};
    bool     wavOpen  = false;
    int      channels = 2, sampleRate = 44100, bits = 16;
    int64_t  totalFrames = 0;

    // ── ALAC через AMediaExtractor (демукс) + Apple ALACDecoder (декод) ──
    AMediaExtractor* ex     = nullptr;
    ALACDecoder*     alac   = nullptr;
    uint32_t         aFrameLen = 4096;
    std::vector<uint8_t> aPkt, aPcm;
    std::vector<float>   aStage;   // декодированное, ещё не отданное (f32 интерливнуто)
    size_t           aStagePos = 0;
    bool             aEos   = false;

    bool open(int fd, int f) {
        close();
        fmt = f;
        if (f == 2) return openAlac(fd);
        src.fd = ::dup(fd);
        src.pos = 0;
        if (f == 0) {
            flac = drflac_open(fd_read, flac_seek, flac_tell, &src, nullptr);
            if (!flac) { close(); return false; }
            channels = (int) flac->channels;
            sampleRate = (int) flac->sampleRate;
            bits = (int) flac->bitsPerSample;
            totalFrames = (int64_t) flac->totalPCMFrameCount;
        } else if (f == 1) {
            if (!drwav_init(&wav, fd_read, wav_seek, wav_tell, &src, nullptr)) { close(); return false; }
            wavOpen = true;
            channels = (int) wav.channels;
            sampleRate = (int) wav.sampleRate;
            bits = (int) wav.bitsPerSample;
            totalFrames = (int64_t) wav.totalPCMFrameCount;
        } else return false;
        return channels > 0 && sampleRate > 0;
    }

    bool openAlac(int fd) {
        struct stat st{};
        if (::fstat(fd, &st) != 0 || st.st_size <= 0) return false;
        int dfd = ::dup(fd);
        ex = AMediaExtractor_new();
        if (AMediaExtractor_setDataSourceFd(ex, dfd, 0, (off64_t) st.st_size) != AMEDIA_OK) {
            ::close(dfd); close(); return false;
        }
        ::close(dfd);
        size_t nTracks = AMediaExtractor_getTrackCount(ex);
        int track = -1;
        AMediaFormat* tf = nullptr;
        for (size_t i = 0; i < nTracks; ++i) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(ex, i);
            const char* mime = nullptr;
            if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &mime) && mime &&
                (std::strstr(mime, "alac") || std::strstr(mime, "ALAC"))) {
                track = (int) i; tf = f; break;
            }
            AMediaFormat_delete(f);
        }
        if (track < 0 || !tf) { close(); return false; }
        AMediaExtractor_selectTrack(ex, track);

        void* cookie = nullptr; size_t cookieSz = 0;
        if (!AMediaFormat_getBuffer(tf, "csd-0", &cookie, &cookieSz) || !cookie || cookieSz < 24) {
            AMediaFormat_delete(tf); close(); return false;
        }
        alac = new ALACDecoder();
        if (alac->Init(cookie, (uint32_t) cookieSz) != 0) { AMediaFormat_delete(tf); close(); return false; }
        channels   = (int) alac->mConfig.numChannels;
        sampleRate = (int) alac->mConfig.sampleRate;
        bits       = (int) alac->mConfig.bitDepth;
        aFrameLen  = alac->mConfig.frameLength ? alac->mConfig.frameLength : 4096;

        int64_t durUs = 0;
        if (__builtin_available(android 28, *)) {
            if (AMediaFormat_getInt64(tf, AMEDIAFORMAT_KEY_DURATION, &durUs) && durUs > 0)
                totalFrames = durUs * sampleRate / 1000000;
        }
        AMediaFormat_delete(tf);

        aPkt.assign(1 << 18, 0);                          // 256 КБ на пакет с запасом
        aPcm.assign((size_t) aFrameLen * channels * 4, 0);
        aStage.clear(); aStagePos = 0; aEos = false;
        return channels > 0 && sampleRate > 0 && bits >= 16 && bits <= 32;
    }

    void close() {
        if (flac) { drflac_close(flac); flac = nullptr; }
        if (wavOpen) { drwav_uninit(&wav); wavOpen = false; }
        if (alac) { delete alac; alac = nullptr; }
        if (ex) { AMediaExtractor_delete(ex); ex = nullptr; }
        if (src.fd >= 0) { ::close(src.fd); src.fd = -1; }
        aStage.clear(); aStagePos = 0; aEos = false;
        fmt = -1;
    }

    // распаковать один ALAC-пакет в aStage (f32)
    bool alacFillStage() {
        if (aEos) return false;
        ssize_t n = AMediaExtractor_readSampleData(ex, aPkt.data(), aPkt.size());
        if (n <= 0) { aEos = true; return false; }
        BitBuffer bb;
        BitBufferInit(&bb, aPkt.data(), (uint32_t) n);
        uint32_t outN = 0;
        if (alac->Decode(&bb, aPcm.data(), aFrameLen, (uint32_t) channels, &outN) != 0 || outN == 0) {
            AMediaExtractor_advance(ex);
            return true;   // битый пакет — пропускаем, но не EOS
        }
        size_t total = (size_t) outN * channels;
        aStage.resize(total);
        aStagePos = 0;
        if (bits == 16) {
            const int16_t* p = reinterpret_cast<const int16_t*>(aPcm.data());
            for (size_t i = 0; i < total; ++i) aStage[i] = p[i] * (1.0f / 32768.0f);
        } else if (bits == 24) {
            const uint8_t* p = aPcm.data();
            for (size_t i = 0; i < total; ++i) {
                int32_t v = (int32_t) ((p[3*i] ) | (p[3*i+1] << 8) | (p[3*i+2] << 16));
                if (v & 0x800000) v |= ~0xFFFFFF;
                aStage[i] = v * (1.0f / 8388608.0f);
            }
        } else { // 32
            const int32_t* p = reinterpret_cast<const int32_t*>(aPcm.data());
            for (size_t i = 0; i < total; ++i) aStage[i] = p[i] * (1.0f / 2147483648.0f);
        }
        AMediaExtractor_advance(ex);
        return true;
    }

    // читает до `frames` интерливнутых кадров, вернёт сколько реально
    int64_t read(float* out, int64_t frames) {
        if (fmt == 0 && flac) return (int64_t) drflac_read_pcm_frames_f32(flac, (drflac_uint64) frames, out);
        if (fmt == 1 && wavOpen) return (int64_t) drwav_read_pcm_frames_f32(&wav, (drwav_uint64) frames, out);
        if (fmt == 2 && alac) {
            int64_t need = frames * channels, done = 0;
            while (done < need) {
                if (aStagePos >= aStage.size()) {
                    if (!alacFillStage()) break;
                    if (aStage.empty()) continue;
                }
                size_t avail = aStage.size() - aStagePos;
                size_t take = (size_t) (need - done) < avail ? (size_t) (need - done) : avail;
                std::memcpy(out + done, aStage.data() + aStagePos, take * sizeof(float));
                aStagePos += take;
                done += (int64_t) take;
            }
            return done / channels;
        }
        return 0;
    }
    bool seek(int64_t frame) {
        if (fmt == 0 && flac) return drflac_seek_to_pcm_frame(flac, (drflac_uint64) frame) == DRFLAC_TRUE;
        if (fmt == 1 && wavOpen) return drwav_seek_to_pcm_frame(&wav, (drwav_uint64) frame) == DRWAV_TRUE;
        if (fmt == 2 && ex) {
            int64_t us = sampleRate > 0 ? frame * 1000000 / sampleRate : 0;
            AMediaExtractor_seekTo(ex, us, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
            aStage.clear(); aStagePos = 0; aEos = false;
            return true;
        }
        return false;
    }
    ~Decoder() { close(); }
};

// ── кольцевой буфер float (SPSC) ──────────────────────────────────────────
class Ring {
public:
    void reset(size_t floats) {
        buf_.assign(floats, 0.0f);
        cap_ = floats;
        r_.store(0); w_.store(0);
    }
    size_t writable() const {
        size_t w = w_.load(std::memory_order_relaxed), r = r_.load(std::memory_order_acquire);
        return cap_ - 1 - ((w - r) % cap_);
    }
    size_t readable() const {
        size_t w = w_.load(std::memory_order_acquire), r = r_.load(std::memory_order_relaxed);
        return (w - r) % cap_;
    }
    void push(const float* src, size_t n) {
        size_t w = w_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < n; ++i) { buf_[w] = src[i]; w = (w + 1) % cap_; }
        w_.store(w, std::memory_order_release);
    }
    // пишет тишину если данных мало
    size_t pull(float* dst, size_t n) {
        size_t r = r_.load(std::memory_order_relaxed);
        size_t avail = readable();
        size_t k = n < avail ? n : avail;
        for (size_t i = 0; i < k; ++i) { dst[i] = buf_[r]; r = (r + 1) % cap_; }
        r_.store(r, std::memory_order_release);
        for (size_t i = k; i < n; ++i) dst[i] = 0.0f;
        return k;
    }
    void clear() { r_.store(w_.load()); }
private:
    std::vector<float> buf_;
    size_t cap_ = 1;
    std::atomic<size_t> r_{0}, w_{0};
};

// ── движок ───────────────────────────────────────────────────────────────
class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    ~Engine() { unload(); }

    bool loadQueue(const std::vector<int>& fds, const std::vector<int>& fmts, int startIdx);
    void unload();
    bool start();
    void pause();
    void stop() { unload(); }
    void seekFrames(int64_t frame);
    void nextTrack();
    void prevTrack();
    void setIndex(int i);
    void setGain(float g) { gain_.store(g < 0 ? 0.f : (g > 4.f ? 4.f : g)); }

    int64_t positionFrames() const { return outPos_.load() - trackStartOut_.load(); }
    int64_t durationFrames() const { return curTotal_.load(); }
    int32_t index()      const { return idx_.load(); }
    int32_t count()      const { return (int32_t) fds_.size(); }
    int32_t sampleRate() const { return srcRate_.load(); }
    int32_t grantedRate()const { return grantedRate_; }
    int32_t channels()   const { return channels_; }
    int32_t bitDepth()   const { return bits_.load(); }
    bool    resampled()  const { return resampled_.load(); }
    bool    playing()    const { return playing_.load(); }
    bool    ended()      const { return endedAll_.load(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result r) override {
        LOGW("stream error: %s", oboe::convertToText(r)); playing_.store(false);
    }

private:
    void worker();
    bool openAt(int i, Decoder& d);
    void rebuildStreamFor(int rate, int ch);

    std::mutex ctlMtx_;                 // защищает fds_/idx switch на стороне worker/JNI
    std::vector<int> fds_, fmts_;       // dup'нутые fd всей очереди — владеем
    std::atomic<int>  idx_{-1};         // индекс ИГРАЮЩЕГО (по выходу), не декодируемого
    std::atomic<int>  decIdx_{-1};      // индекс, который сейчас тянет worker

    std::shared_ptr<oboe::AudioStream> stream_;
    int channels_   = 2;
    int streamRate_ = 44100;
    int grantedRate_ = 0;

    Ring ring_;
    std::thread workerTh_;
    std::atomic<bool> workerRun_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> endedAll_{false};
    std::atomic<float> gain_{1.0f};

    // «что играет прямо сейчас» — очередь маркеров {startOutFrame, idx, total, rate, bits}
    struct Mark { int64_t startOut; int idx; int64_t total; int rate; int bits; bool resamp; };
    std::mutex markMtx_;
    std::deque<Mark> marks_;
    std::atomic<int64_t> outPos_{0};          // сколько кадров ушло в callback
    std::atomic<int64_t> trackStartOut_{0};   // startOut текущего трека
    std::atomic<int64_t> curTotal_{0};
    std::atomic<int>  srcRate_{44100};
    std::atomic<int>  bits_{16};
    std::atomic<bool> resampled_{false};

    // команда воркеру: перейти к треку (относительный шаг) / seek
    std::atomic<int>  jump_{0};
    std::atomic<int64_t> seekTo_{-1};
    // TPDF-дизер state
    uint32_t rng_ = 0x1234567u;
    float tpdf() {
        rng_ = rng_ * 1664525u + 1013904223u; float a = (rng_ >> 9) * (1.0f / 8388608.0f) - 0.5f;
        rng_ = rng_ * 1664525u + 1013904223u; float b = (rng_ >> 9) * (1.0f / 8388608.0f) - 0.5f;
        return (a + b) * (1.0f / 32768.0f);   // ~1 LSB @16bit peak-to-peak
    }
};

bool Engine::openAt(int i, Decoder& d) {
    if (i < 0 || i >= (int) fds_.size()) return false;
    return d.open(fds_[i], fmts_[i]);
}

void Engine::rebuildStreamFor(int rate, int ch) {
    if (stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
    channels_ = ch;
    streamRate_ = rate;
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
     ->setPerformanceMode(oboe::PerformanceMode::None)
     ->setSharingMode(oboe::SharingMode::Exclusive)
     ->setFormat(oboe::AudioFormat::Float)
     ->setChannelCount(ch)
     ->setSampleRate(rate)
     ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
     ->setUsage(oboe::Usage::Media)
     ->setContentType(oboe::ContentType::Music)
     ->setDataCallback(this)
     ->setErrorCallback(this);
    auto r = b.openStream(stream_);
    if (r != oboe::Result::OK || !stream_) { LOGW("openStream: %s", oboe::convertToText(r)); stream_.reset(); return; }
    grantedRate_ = stream_->getSampleRate();
    LOGI("stream %dHz %dch  granted=%dHz", rate, ch, grantedRate_);
}

bool Engine::loadQueue(const std::vector<int>& fds, const std::vector<int>& fmts, int startIdx) {
    std::lock_guard<std::mutex> lk(ctlMtx_);
    unload();
    if (fds.empty() || fds.size() != fmts.size()) return false;

    for (int fd : fds) fds_.push_back(::dup(fd));   // владеем своими копиями
    fmts_ = fmts;
    int i = startIdx < 0 ? 0 : (startIdx >= (int) fds_.size() ? 0 : startIdx);

    Decoder probe;
    if (!openAt(i, probe)) { unload(); return false; }
    int rate = probe.sampleRate, ch = probe.channels;
    probe.close();

    ring_.reset((size_t) rate * ch * 2);           // ~2 сек буфер
    rebuildStreamFor(rate, ch);
    if (!stream_) { unload(); return false; }

    idx_.store(i);
    decIdx_.store(i);
    outPos_.store(0);
    trackStartOut_.store(0);
    endedAll_.store(false);
    { std::lock_guard<std::mutex> m(markMtx_); marks_.clear(); }

    workerRun_.store(true);
    workerTh_ = std::thread([this] { worker(); });
    return true;
}

void Engine::unload() {
    workerRun_.store(false);
    if (workerTh_.joinable()) workerTh_.join();
    playing_.store(false);
    if (stream_) { stream_->stop(); stream_->close(); stream_.reset(); }
    for (int fd : fds_) if (fd >= 0) ::close(fd);
    fds_.clear(); fmts_.clear();
    idx_.store(-1); decIdx_.store(-1);
    outPos_.store(0); trackStartOut_.store(0); curTotal_.store(0);
    endedAll_.store(false); resampled_.store(false);
    jump_.store(0); seekTo_.store(-1);
    grantedRate_ = 0;
    { std::lock_guard<std::mutex> m(markMtx_); marks_.clear(); }
}

// worker: держит ring полным, переходя через границы треков
void Engine::worker() {
    Decoder dec;
    int di = decIdx_.load();
    if (!openAt(di, dec)) { endedAll_.store(true); return; }

    // маркер для стартового трека
    {
        std::lock_guard<std::mutex> m(markMtx_);
        marks_.push_back({0, di, dec.totalFrames, dec.sampleRate, dec.bits,
                          dec.sampleRate != streamRate_});
    }
    int64_t producedOut = 0;   // сколько кадров выхода уже отдано в ring для ЭТОГО трека
    std::vector<float> tmp(4096 * channels_);
    std::vector<float> conv(8192 * channels_);

    while (workerRun_.load()) {
        // команда: явный jump (next/prev) или seek
        int jmp = jump_.exchange(0);
        int64_t sk = seekTo_.exchange(-1);
        if (jmp != 0) {
            int ni = di + jmp;
            if (ni < 0) ni = 0;
            if (ni >= (int) fds_.size()) { endedAll_.store(true); ni = (int) fds_.size() - 1; }
            if (ni != di) {
                di = ni;
                dec.close();
                if (!openAt(di, dec)) { endedAll_.store(true); break; }
                ring_.clear();
                // новый маркер: играть начнём с текущего outPos_
                std::lock_guard<std::mutex> m(markMtx_);
                marks_.clear();
                marks_.push_back({outPos_.load(), di, dec.totalFrames, dec.sampleRate, dec.bits,
                                  dec.sampleRate != streamRate_});
                producedOut = 0;
            }
        } else if (sk >= 0) {
            dec.seek(sk);
            ring_.clear();
            std::lock_guard<std::mutex> m(markMtx_);
            marks_.clear();
            int64_t skOut = (int64_t) ((double) sk * streamRate_ / dec.sampleRate);
            marks_.push_back({outPos_.load(), di, dec.totalFrames, dec.sampleRate, dec.bits,
                              dec.sampleRate != streamRate_});
            producedOut = skOut;
        }

        if (ring_.writable() < (size_t) (1024 * channels_)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        int64_t got = dec.read(tmp.data(), 1024);
        if (got <= 0) {
            // конец трека → следующий (гэплесс: маркер с точным startOut)
            int ni = di + 1;
            if (ni >= (int) fds_.size()) {
                // очередь кончилась — дать ring доиграть, потом стоп
                if (ring_.readable() == 0) { endedAll_.store(true); playing_.store(false); }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            di = ni;
            dec.close();
            if (!openAt(di, dec)) { endedAll_.store(true); break; }
            decIdx_.store(di);
            int64_t markStart = outPos_.load() + (int64_t) ring_.readable() / channels_;
            std::lock_guard<std::mutex> m(markMtx_);
            marks_.push_back({markStart, di, dec.totalFrames, dec.sampleRate, dec.bits,
                              dec.sampleRate != streamRate_});
            producedOut = 0;
            continue;
        }

        // ресемпл в частоту потока, если надо (линейный — зачаток a5)
        if (dec.sampleRate == streamRate_) {
            ring_.push(tmp.data(), (size_t) got * channels_);
            producedOut += got;
        } else {
            double ratio = (double) streamRate_ / dec.sampleRate;
            int outN = (int) (got * ratio);
            if (outN > (int) conv.size() / channels_) outN = (int) conv.size() / channels_;
            for (int o = 0; o < outN; ++o) {
                double srcPos = o / ratio;
                int i0 = (int) srcPos;
                double f = srcPos - i0;
                int i1 = i0 + 1 < got ? i0 + 1 : (int) got - 1;
                for (int ch = 0; ch < channels_; ++ch) {
                    float a = tmp[i0 * channels_ + ch], b = tmp[i1 * channels_ + ch];
                    conv[o * channels_ + ch] = a + (float) f * (b - a);
                }
            }
            ring_.push(conv.data(), (size_t) outN * channels_);
            producedOut += outN;
        }
    }
    dec.close();
}

oboe::DataCallbackResult Engine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int ch = channels_;
    const size_t need = (size_t) numFrames * ch;

    if (!playing_.load()) { std::memset(out, 0, sizeof(float) * need); return oboe::DataCallbackResult::Continue; }

    ring_.pull(out, need);

    // громкость + TPDF-дизер (к 16-битной сетке — мягкий, снимает квантование)
    float g = gain_.load();
    if (g != 1.0f || true) {
        for (size_t i = 0; i < need; ++i) {
            float v = out[i] * g + tpdf();
            out[i] = v > 1.f ? 1.f : (v < -1.f ? -1.f : v);
        }
    }

    int64_t prev = outPos_.fetch_add(numFrames);
    int64_t now = prev + numFrames;

    // обновить «текущий трек» по маркерам
    {
        std::lock_guard<std::mutex> m(markMtx_);
        while (marks_.size() > 1 && marks_[1].startOut <= now) marks_.pop_front();
        if (!marks_.empty()) {
            const Mark& mk = marks_.front();
            idx_.store(mk.idx);
            trackStartOut_.store(mk.startOut);
            curTotal_.store(mk.total);
            srcRate_.store(mk.rate);
            bits_.store(mk.bits);
            resampled_.store(mk.resamp);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

bool Engine::start() {
    if (!stream_) return false;
    endedAll_.store(false);
    auto r = stream_->requestStart();
    if (r != oboe::Result::OK) { LOGW("requestStart: %s", oboe::convertToText(r)); return false; }
    playing_.store(true);
    return true;
}
void Engine::pause() {
    playing_.store(false);
    if (stream_) stream_->requestPause();
}
void Engine::seekFrames(int64_t frame) {
    if (frame < 0) frame = 0;
    seekTo_.store(frame);
}
void Engine::nextTrack() { jump_.fetch_add(+1); }
void Engine::prevTrack() {
    // <3с от начала — предыдущий, иначе в начало текущего
    if (positionFrames() > (int64_t) srcRate_.load() * 3) seekTo_.store(0);
    else jump_.fetch_add(-1);
}
void Engine::setIndex(int i) { jump_.fetch_add(i - idx_.load()); }

Engine g_engine;

} // namespace

// ── JNI ──────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jboolean JNICALL
Java_net_ripster_mobile_player_NativeAudioEngine_nLoadQueue(JNIEnv* env, jobject,
        jintArray jfds, jintArray jfmts, jint startIdx) {
    jsize n = env->GetArrayLength(jfds);
    std::vector<int> fds(n), fmts(n);
    env->GetIntArrayRegion(jfds, 0, n, fds.data());
    env->GetIntArrayRegion(jfmts, 0, n, fmts.data());
    return g_engine.loadQueue(fds, fmts, startIdx) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nStart(JNIEnv*, jobject) {
    return g_engine.start() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nPause(JNIEnv*, jobject) { g_engine.pause(); }
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nStop(JNIEnv*, jobject)  { g_engine.stop(); }
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nNext(JNIEnv*, jobject)  { g_engine.nextTrack(); }
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nPrev(JNIEnv*, jobject)  { g_engine.prevTrack(); }
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nSetIndex(JNIEnv*, jobject, jint i) { g_engine.setIndex(i); }
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nSeek(JNIEnv*, jobject, jlong frame) {
    g_engine.seekFrames(frame);
}
JNIEXPORT void JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nSetGain(JNIEnv*, jobject, jfloat g) {
    g_engine.setGain(g);
}
JNIEXPORT jlong JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nPositionFrames(JNIEnv*, jobject) { return g_engine.positionFrames(); }
JNIEXPORT jlong JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nDurationFrames(JNIEnv*, jobject) { return g_engine.durationFrames(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nIndex(JNIEnv*, jobject)       { return g_engine.index(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nCount(JNIEnv*, jobject)       { return g_engine.count(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nSampleRate(JNIEnv*, jobject)  { return g_engine.sampleRate(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nGrantedRate(JNIEnv*, jobject) { return g_engine.grantedRate(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nChannels(JNIEnv*, jobject)    { return g_engine.channels(); }
JNIEXPORT jint  JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nBitDepth(JNIEnv*, jobject)    { return g_engine.bitDepth(); }
JNIEXPORT jboolean JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nResampled(JNIEnv*, jobject){ return g_engine.resampled() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nIsPlaying(JNIEnv*, jobject){ return g_engine.playing() ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jboolean JNICALL Java_net_ripster_mobile_player_NativeAudioEngine_nIsEnded(JNIEnv*, jobject)  { return g_engine.ended() ? JNI_TRUE : JNI_FALSE; }

} // extern "C"
