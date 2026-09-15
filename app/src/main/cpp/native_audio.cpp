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
#include "wavpack.h"
#include "dsd.h"
#include "resampler.h"
#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

#define LOG_TAG "RipsterAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ── источник: сырой fd, позиционируемое чтение ─────────────────────────────
struct FdSource {
    int     fd   = -1;
    int64_t pos  = 0;
    // Размер файла нужен для отсчёта «от конца». Без него seek(END) уходил
    // в 0, то есть «конец файла» = его начало, и tell() возвращал нулевую
    // длину потока (см. flac_seek/wav_seek ниже).
    int64_t size = 0;
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
    // ОТСЧЁТ ОТ КОНЦА — это size + off, а не ноль. Раньше здесь стояло
    // `pos = -1` с последующим прижатием к нулю: декодер, спрашивая «где
    // конец», получал начало файла и считал поток пустым.
    if (o == DRFLAC_SEEK_CUR) s->pos += off;
    else if (o == DRFLAC_SEEK_END) s->pos = s->size + off;
    else s->pos = off;
    if (s->pos < 0) s->pos = 0;
    return DRFLAC_TRUE;
}
drflac_bool32 flac_tell(void* user, drflac_int64* c) { *c = static_cast<FdSource*>(user)->pos; return DRFLAC_TRUE; }
drwav_bool32 wav_seek(void* user, int off, drwav_seek_origin o) {
    auto* s = static_cast<FdSource*>(user);
    if (o == DRWAV_SEEK_CUR) s->pos += off;
    else if (o == DRWAV_SEEK_END) s->pos = s->size + off;
    else s->pos = off;
    if (s->pos < 0) s->pos = 0;
    return DRWAV_TRUE;
}
drwav_bool32 wav_tell(void* user, drwav_int64* c) { *c = static_cast<FdSource*>(user)->pos; return DRWAV_TRUE; }

// ── WavPack поверх того же fd ──────────────────────────────────────────────
//
// Библиотека умеет работать через свой набор колбэков, поэтому файл ей не
// нужен — отдаём тот же дескриптор, что и остальным декодерам. Это важно:
// путь к файлу у нас есть не всегда (SAF отдаёт только fd), и открытие по
// имени сломало бы импорт из чужих папок.
int32_t wv_read(void* id, void* data, int32_t bcount) {
    auto* s = static_cast<FdSource*>(id);
    ssize_t n = ::pread(s->fd, data, (size_t) bcount, s->pos);
    if (n <= 0) return 0;
    s->pos += n;
    return (int32_t) n;
}
int wv_push_back(void* id, int c) {
    auto* s = static_cast<FdSource*>(id);
    if (s->pos > 0) s->pos--;
    return c;
}
int64_t wv_get_pos(void* id) { return static_cast<FdSource*>(id)->pos; }
int wv_set_pos_abs(void* id, int64_t pos) {
    auto* s = static_cast<FdSource*>(id);
    s->pos = pos < 0 ? 0 : pos;
    return 0;
}
int wv_set_pos_rel(void* id, int64_t delta, int mode) {
    auto* s = static_cast<FdSource*>(id);
    int64_t base = (mode == SEEK_SET) ? 0 : (mode == SEEK_CUR) ? s->pos : s->size;
    int64_t p = base + delta;
    s->pos = p < 0 ? 0 : p;
    return 0;
}
int64_t wv_get_length(void* id) { return static_cast<FdSource*>(id)->size; }
int wv_can_seek(void* id) { (void) id; return 1; }
int32_t wv_write(void* id, void* data, int32_t bcount) {
    // Только чтение: упаковка нам не нужна и вендорить её не за чем.
    (void) id; (void) data; (void) bcount;
    return 0;
}

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
    // ── WavPack ──
    WavpackContext*  wv     = nullptr;
    FdSource         wvSrc{};
    WavpackStreamReader64 wvReader{};
    std::vector<int32_t>  wvBuf;      // WavPack отдаёт int32 на семпл
    float            wvScale = 1.0f;  // делитель к float по разрядности файла
    char             wvErr[80] = {0};

    // ── DSD (.dsf/.dff) ──
    dsd::Ctx  dsdCtx{};
    FdSource  dsdSrc{};
    bool      dsdOpen = false;
    char      dsdErr[96] = {0};

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
        if (f == 3) return openWavPack(fd);
        if (f == 4) return openDsd(fd);
        src.fd = ::dup(fd);
        src.pos = 0;
        { struct stat st{}; src.size = (::fstat(src.fd, &st) == 0) ? (int64_t) st.st_size : 0; }
        if (f == 0) {
            flac = drflac_open(fd_read, flac_seek, flac_tell, &src, nullptr);
            if (!flac) {
                // Отказ без единой подробности не отличить от «файла нет».
                // Читаем сигнатуру прямо с дескриптора: сразу видно, живой ли
                // он и правда ли это FLAC.
                unsigned char h[4] = {0, 0, 0, 0};
                ssize_t n = ::pread(src.fd, h, 4, 0);
                LOGW("drflac_open failed: size=%lld read=%zd magic=%02x%02x%02x%02x",
                     (long long) src.size, n, h[0], h[1], h[2], h[3]);
                close();
                return false;
            }
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

    // WavPack (.wv). Пункт трекера a4: «ALAC готов · APE/WavPack/DSD дальше».
    //
    // Отдельный FdSource: библиотека держит своё положение в потоке, и делить
    // его с dr_flac/dr_wav нельзя — второй декодер сбивал бы первому позицию.
    bool openWavPack(int fd) {
        wvSrc.fd = ::dup(fd);
        wvSrc.pos = 0;
        { struct stat st{}; wvSrc.size = (::fstat(wvSrc.fd, &st) == 0) ? (int64_t) st.st_size : 0; }
        wvReader.read_bytes    = wv_read;
        wvReader.write_bytes   = wv_write;
        wvReader.get_pos       = wv_get_pos;
        wvReader.set_pos_abs   = wv_set_pos_abs;
        wvReader.set_pos_rel   = wv_set_pos_rel;
        wvReader.push_back_byte = wv_push_back;
        wvReader.get_length    = wv_get_length;
        wvReader.can_seek      = wv_can_seek;
        wvErr[0] = 0;
        // OPEN_DSD_NATIVE: файлы с DSD внутри WavPack читаем как есть, а не
        // отказываемся от них. OPEN_NORMALIZE даёт единый масштаб для float-
        // потоков. Второго (correction) файла у нас нет — отдаём nullptr.
        wv = WavpackOpenFileInputEx64(
                &wvReader, &wvSrc, nullptr, wvErr,
                OPEN_NORMALIZE | OPEN_DSD_NATIVE, 0);
        if (!wv) {
            LOGW("WavpackOpenFileInputEx64 failed: %s (size=%lld)",
                 wvErr[0] ? wvErr : "no reason given", (long long) wvSrc.size);
            close();
            return false;
        }
        channels    = WavpackGetNumChannels(wv);
        sampleRate  = (int) WavpackGetSampleRate(wv);
        bits        = WavpackGetBitsPerSample(wv);
        totalFrames = (int64_t) WavpackGetNumSamples64(wv);
        // WavPack всегда отдаёт int32 в семпле; во float приводим по РАЗРЯДНОСТИ
        // ФАЙЛА, а не по 32 битам, иначе 16-битная запись звучала бы на 48 дБ
        // тише положенного.
        const int shift = WavpackGetBytesPerSample(wv) * 8;
        wvScale = 1.0f / (float) (1LL << (shift - 1));
        if ((WavpackGetMode(wv) & MODE_FLOAT) != 0) wvScale = 1.0f;  // уже float
        LOGI("wavpack ok: %dHz %dch %dbit frames=%lld%s",
             sampleRate, channels, bits, (long long) totalFrames,
             (WavpackGetQualifyMode(wv) & QMODE_DSD_AUDIO) ? " (DSD)" : "");
        return channels > 0 && sampleRate > 0;
    }

    // DSD. Разбор и децимация — в dsd.h, здесь только дескриптор и отчёт.
    //
    // Файл читается тем же приёмом, что и остальными декодерами (pread по
    // своему fd), поэтому dsd.h ничего не знает ни про Android, ни про наш
    // проигрыватель: тот же заголовок собирается и в ПК-версии.
    bool openDsd(int fd) {
        dsdSrc.fd = ::dup(fd);
        dsdSrc.pos = 0;
        { struct stat st{}; dsdSrc.size = (::fstat(dsdSrc.fd, &st) == 0) ? (int64_t) st.st_size : 0; }
        dsdErr[0] = 0;
        auto readAt = [](void* user, int64_t off, void* out, size_t bytes) -> bool {
            auto* s2 = static_cast<FdSource*>(user);
            return ::pread(s2->fd, out, bytes, off) == (ssize_t) bytes;
        };
        if (!dsd::parse(dsdCtx, readAt, &dsdSrc, dsdSrc.size, dsdErr, sizeof dsdErr)) {
            LOGW("dsd: %s (size=%lld)", dsdErr[0] ? dsdErr : "unknown", (long long) dsdSrc.size);
            close();
            return false;
        }
        channels    = dsdCtx.channels;
        sampleRate  = dsd::kOutRate;
        // Разрядность у DSD одна — один бит. Наружу отдаём разрядность ТОГО,
        // что реально уходит в звук, иначе строка «1-bit» обещала бы
        // невозможное: однобитного тракта у нас нет.
        bits        = 24;
        totalFrames = dsdCtx.totalFrames;
        dsdOpen = true;
        LOGI("dsd ok: %lldHz 1-bit %dch → %dHz PCM (децимация %d), кадров=%lld",
             (long long) dsdCtx.dsdRate, channels, sampleRate, dsdCtx.decim,
             (long long) totalFrames);
        return channels > 0;
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
        // Свой дескриптор WavPack закрываем ОТДЕЛЬНО: он дублировался под
        // отдельный источник, и утечка тут копилась бы по треку на каждый
        // переход в очереди.
        if (wv) { WavpackCloseFile(wv); wv = nullptr; }
        if (wvSrc.fd >= 0) { ::close(wvSrc.fd); wvSrc.fd = -1; }
        if (dsdSrc.fd >= 0) { ::close(dsdSrc.fd); dsdSrc.fd = -1; }
        dsdCtx = dsd::Ctx{};
        dsdOpen = false;
        wvBuf.clear();
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
        if (fmt == 4 && dsdOpen) {
            auto readAt = [](void* user, int64_t off, void* out, size_t bytes) -> bool {
                auto* s2 = static_cast<FdSource*>(user);
                return ::pread(s2->fd, out, bytes, off) == (ssize_t) bytes;
            };
            return dsd::decode(dsdCtx, readAt, &dsdSrc, out, frames);
        }
        if (fmt == 3 && wv) {
            const size_t need = (size_t) frames * (size_t) channels;
            if (wvBuf.size() < need) wvBuf.resize(need);
            uint32_t got = WavpackUnpackSamples(wv, wvBuf.data(), (uint32_t) frames);
            if (got == 0) return 0;
            const size_t n = (size_t) got * (size_t) channels;
            for (size_t i = 0; i < n; ++i) out[i] = (float) wvBuf[i] * wvScale;
            return (int64_t) got;
        }
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
        if (fmt == 4 && dsdOpen) return dsd::seek(dsdCtx, frame);
        if (fmt == 3 && wv) return WavpackSeekSample64(wv, (int64_t) frame) != 0;
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
/**
 * Кольцо между декодером и аудио-колбэком.
 *
 * Счётчики СКВОЗНЫЕ (никогда не сбрасываются по модулю), а по модулю берётся
 * только адрес в буфере. Так было не всегда, и это стоило нам движка: раньше
 * `r_`/`w_` хранили уже свёрнутые индексы, а свободное место считалось как
 * `cap - 1 - ((w - r) % cap)`. На беззнаковых, когда `w` уходил за `r`,
 * разность переполнялась, а `% cap` спасает только если `cap` — степень
 * двойки. Ёмкость здесь `частота * каналы * 2` (176400 для 44.1/стерео),
 * степенью двойки не является — и `writable()` возвращал произвольное число.
 *
 * Как это выглядело снаружи (A31, 05.09.2026): звук играл ровно две секунды и
 * замолкал. Декодер, которому кольцо всё время сообщало «место есть»,
 * проглатывал весь трек за пару секунд — в логе «decoder returned 0 after
 * 8101333 frames» через 2.2 с после старта, — упирался в конец файла, движок
 * поднимал «очередь кончилась», и плеер честно останавливался.
 */
class Ring {
public:
    void reset(size_t floats) {
        buf_.assign(floats ? floats : 1, 0.0f);
        cap_ = floats ? floats : 1;
        r_.store(0); w_.store(0);
    }
    /** Свободно под запись. Одну ячейку держим пустой, чтобы полное кольцо не
     *  было неотличимо от пустого. */
    size_t writable() const {
        uint64_t w = w_.load(std::memory_order_relaxed), r = r_.load(std::memory_order_acquire);
        size_t used = (size_t) (w - r);
        return used >= cap_ - 1 ? 0 : cap_ - 1 - used;
    }
    size_t readable() const {
        uint64_t w = w_.load(std::memory_order_acquire), r = r_.load(std::memory_order_relaxed);
        return (size_t) (w - r);
    }
    /** Пишет не больше, чем есть места: ошибка производителя не должна
     *  затирать ещё не сыгранное. */
    void push(const float* src, size_t n) {
        size_t room = writable();
        if (n > room) n = room;
        if (n == 0) return;
        uint64_t w = w_.load(std::memory_order_relaxed);
        size_t idx = (size_t) (w % cap_);
        for (size_t i = 0; i < n; ++i) {
            buf_[idx] = src[i];
            if (++idx == cap_) idx = 0;
        }
        w_.store(w + n, std::memory_order_release);
    }
    /** Пишет тишину, если данных не хватило: щелчок лучше мусора. */
    size_t pull(float* dst, size_t n) {
        size_t avail = readable();
        size_t k = n < avail ? n : avail;
        uint64_t r = r_.load(std::memory_order_relaxed);
        size_t idx = (size_t) (r % cap_);
        for (size_t i = 0; i < k; ++i) {
            dst[i] = buf_[idx];
            if (++idx == cap_) idx = 0;
        }
        r_.store(r + k, std::memory_order_release);
        for (size_t i = k; i < n; ++i) dst[i] = 0.0f;
        return k;
    }
    void clear() { r_.store(w_.load()); }
private:
    std::vector<float> buf_;
    size_t cap_ = 1;
    std::atomic<uint64_t> r_{0}, w_{0};
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
    // Ресемплер живёт МЕЖДУ блоками: в нём хвост входа и дробная позиция.
    // Пересоздаём только при смене частоты или числа каналов — пересоздание
    // посреди дорожки обнулило бы хвост и вернуло бы тот самый щелчок.
    rsmp::Resampler rs_;
    int  rsIn_ = 0;
    int  rsCh_ = 0;
    bool rsReady_ = false;
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
    // Раньше оба отказа — декодера и Oboe — выходили наружу одним и тем же
    // «false», и в Kotlin превращались в общее «декодер/Oboe не открылись».
    // Отличить их было нечем, а это две совершенно разные поломки.
    if (!openAt(i, probe)) {
        LOGW("decoder open failed: idx=%d fmt=%d", i, i < (int) fmts_.size() ? fmts_[i] : -1);
        unload();
        return false;
    }
    int rate = probe.sampleRate, ch = probe.channels;
    LOGI("decoder ok: %dHz %dch %dbit frames=%lld", rate, ch, probe.bits, (long long) probe.totalFrames);
    probe.close();

    ring_.reset((size_t) rate * ch * 2);           // ~2 сек буфер
    rebuildStreamFor(rate, ch);
    if (!stream_) { LOGW("no output stream for %dHz %dch", rate, ch); unload(); return false; }

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
    rsReady_ = false;
    jump_.store(0); seekTo_.store(-1);
    grantedRate_ = 0;
    { std::lock_guard<std::mutex> m(markMtx_); marks_.clear(); }
}

// worker: держит ring полным, переходя через границы треков
void Engine::worker() {
    Decoder dec;
    int di = decIdx_.load();
    if (!openAt(di, dec)) { LOGW("ended: worker cannot open idx=%d", di); endedAll_.store(true); return; }

    // маркер для стартового трека
    {
        std::lock_guard<std::mutex> m(markMtx_);
        marks_.push_back({0, di, dec.totalFrames, dec.sampleRate, dec.bits,
                          dec.sampleRate != streamRate_});
    }
    int64_t producedOut = 0;   // сколько кадров выхода уже отдано в ring для ЭТОГО трека
    bool eofLogged = false;    // «трек кончился» пишем один раз, а не на каждом обороте
    std::vector<float> tmp(4096 * channels_);
    std::vector<float> conv(8192 * channels_);

    while (workerRun_.load()) {
        // команда: явный jump (next/prev) или seek
        int jmp = jump_.exchange(0);
        int64_t sk = seekTo_.exchange(-1);
        if (jmp != 0) {
            int ni = di + jmp;
            if (ni < 0) ni = 0;
            if (ni >= (int) fds_.size()) { LOGW("ended: jump past end"); endedAll_.store(true); ni = (int) fds_.size() - 1; }
            if (ni != di) {
                di = ni;
                dec.close();
                if (!openAt(di, dec)) { LOGW("ended: cannot open next idx=%d", di); endedAll_.store(true); break; }
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
            // Конец трека объявляем ОДИН раз: этот блок крутится, пока кольцо
            // доигрывает, и без флага он заливал журнал сотней одинаковых строк.
            if (!eofLogged) {
                eofLogged = true;
                LOGI("track done: %lld of %lld frames", (long long) producedOut, (long long) dec.totalFrames);
            }
            // конец трека → следующий (гэплесс: маркер с точным startOut)
            int ni = di + 1;
            if (ni >= (int) fds_.size()) {
                // очередь кончилась — дать ring доиграть, потом стоп
                if (ring_.readable() == 0) { LOGW("ended: queue done, ring drained (produced=%lld)", (long long) producedOut); endedAll_.store(true); playing_.store(false); }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }
            di = ni;
            dec.close();
            if (!openAt(di, dec)) { LOGW("ended: cannot open next idx=%d", di); endedAll_.store(true); break; }
            decIdx_.store(di);
            eofLogged = false;
            int64_t markStart = outPos_.load() + (int64_t) ring_.readable() / channels_;
            std::lock_guard<std::mutex> m(markMtx_);
            marks_.push_back({markStart, di, dec.totalFrames, dec.sampleRate, dec.bits,
                              dec.sampleRate != streamRate_});
            producedOut = 0;
            continue;
        }

        // Пересчёт частоты — полифазный windowed-sinc (пункт a5).
        //
        // Здесь стоял линейный, и он был плох дважды. Во-первых качеством: при
        // 96 → 48 всё, что выше новой границы, заворачивалось обратно в
        // слышимую полосу. Во-вторых — и это хуже — он НЕ ПЕРЕНОСИЛ ПОЗИЦИЮ
        // МЕЖДУ БЛОКАМИ: `srcPos` начинался с нуля на каждом блоке декодера, а
        // последний отсчёт дублировался, то есть на каждой границе был разрыв.
        // Ровно «щелчки на границах» из каталога чужих ошибок, только свои.
        //
        // Ресемплер держит хвост входа и позицию сам, поэтому границ блоков в
        // звуке нет вовсе — проверено `resampler_selftest.cpp`: тот же сигнал,
        // поданный одним куском и блоками по 137 кадров, совпадает до 2e-6.
        if (dec.sampleRate == streamRate_) {
            ring_.push(tmp.data(), (size_t) got * channels_);
            producedOut += got;
        } else {
            if (!rsReady_ || rsIn_ != dec.sampleRate || rsCh_ != channels_) {
                rs_.reset(channels_, (double) dec.sampleRate, (double) streamRate_);
                rsIn_ = dec.sampleRate; rsCh_ = channels_; rsReady_ = true;
            }
            const int64_t cap = (int64_t) conv.size() / channels_;
            const int64_t outN = rs_.process(tmp.data(), got, conv.data(), cap);
            if (outN > 0) {
                ring_.push(conv.data(), (size_t) outN * channels_);
                producedOut += outN;
            }
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

// Декод ВСЕГО файла (до cap кадров) в МОНО float-PCM нашими нативными
// декодерами (FLAC/WAV/ALAC/WavPack/DSD) — для спектрограммы/паспорта, когда
// системный MediaCodec формат не тянет (напр. ALAC-эмулятор). fmt: 0 flac,
// 1 wav, 2 alac(m4a), 3 wavpack, 4 dsd. rateOut[0] ← sampleRate. null при отказе.
JNIEXPORT jfloatArray JNICALL
Java_net_ripster_mobile_player_NativeAudioEngine_nDecodeMono(
        JNIEnv* env, jobject, jint fd, jint fmt, jint capFrames, jintArray rateOut) {
    Decoder d;
    if (!d.open((int) fd, (int) fmt)) return nullptr;
    const int ch = d.channels > 0 ? d.channels : 1;
    const int64_t cap = capFrames > 0 ? (int64_t) capFrames : (int64_t) 2600000;
    std::vector<float> mono;
    mono.reserve((size_t) std::min<int64_t>(cap, 1 << 20));
    const int64_t CHUNK = 8192;
    std::vector<float> buf((size_t) CHUNK * ch);
    while ((int64_t) mono.size() < cap) {
        int64_t got = d.read(buf.data(), CHUNK);
        if (got <= 0) break;
        for (int64_t i = 0; i < got && (int64_t) mono.size() < cap; ++i) {
            float acc = 0.f;
            for (int c = 0; c < ch; ++c) acc += buf[(size_t) i * ch + c];
            mono.push_back(acc / (float) ch);
        }
    }
    if (mono.empty()) return nullptr;
    if (rateOut && env->GetArrayLength(rateOut) > 0) {
        jint sr = (jint) d.sampleRate;
        env->SetIntArrayRegion(rateOut, 0, 1, &sr);
    }
    jfloatArray arr = env->NewFloatArray((jsize) mono.size());
    if (!arr) return nullptr;
    env->SetFloatArrayRegion(arr, 0, (jsize) mono.size(), mono.data());
    return arr;
}

} // extern "C"
