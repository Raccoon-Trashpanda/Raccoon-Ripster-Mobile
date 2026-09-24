package net.ripster.mobile.player

/**
 * Честный сигнальный путь — модель без Android'а.
 *
 * Зачем отдельный файл и почему он ничего не знает про Context. Урок
 * референса (COMPETITOR_resonada.md:195-217): они повесили зелёное
 * «bit-perfect» над активным ресемплером и срезанной с 24 до 16 разрядностью.
 * Формально Each утверждение по отдельности верно, вместе — интерфейс спорит
 * сам с собой. Чтобы такого не повторялось у нас, ПРАВИЛО вердикта живёт здесь,
 * в чистой функции, и покрыт тестами: экран не может «позеленеть», пока
 * ресемплер активен, потому что этот случай запрещён редьюсером, а не
 * настроением вёрстки.
 *
 * Движок словами не говорит (то же, что [NativeAudioEngine.RateNote]).
 * Отсюда наружу уходят статусы-классы и ключи i18n, а перевод и цвет выбирает
 * UI. Числа и единицы (Hz, «24») — данные, они интернациональны и едут как
 * аргументы строк.
 *
 * Каждое поле [SignalSnapshot] — либо настоящее измеренное значение, либо
 * 0 / false / null со смыслом «не измерено на этом устройстве». Редьюсер обязан
 * не выдумывать там, где измерения нет: неизвестное — это UNKNOWN, а не OK.
 */

/** Чем именно играет звук. Влияет на то, что вообще можно померить. */
enum class PathEngine { NATIVE, SYSTEM }

/** Статус стадии — класс, не слово. Цвет и текст выбирает UI по ключу. */
enum class StageStatus { OK, INFO, WARN, LOSS, UNKNOWN }

/**
 * Снимок того, что реально происходит со звуком прямо сейчас.
 *
 * Поля сгруппированы по стадиям цепочки Source → Decoder → SRC → Output.
 * Значение `0` у частот/разрядностей и `null` у [resamplerActive] означают
 * ровно одно: «не измерено». Отличать «измерили и совпало» от «не измерили»
 * — вся суть экрана.
 */
data class SignalSnapshot(
    val engine: PathEngine = PathEngine.SYSTEM,
    // ── SOURCE: что доставлено (обещание сервиса/файла) ──
    /** ASCII-метка кодека из данных («FLAC», «MP3», «opus»); это не UI-текст. */
    val codec: String = "",
    val losslessSource: Boolean = false,
    val sourceBitDepth: Int = 0,
    val sourceRateHz: Int = 0,
    val channels: Int = 0,
    // ── DECODER: чем распаковано и во что ──
    /** Разрядность на выходе декодера; 0 — декодер её не сообщает (Exo+lossy). */
    val decoderBitDepth: Int = 0,
    val decoderRateHz: Int = 0,
    // ── SAMPLE RATE CONVERTER ──
    /** true — ресемпл подтверждён; false — подтверждённо НЕ активен; null — нечем мерить. */
    val resamplerActive: Boolean? = null,
    /** Родная частота вывода устройства (AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE); 0 — неизвестно. */
    val deviceNativeRateHz: Int = 0,
    /** Частота, реально выданная потоку; 0 — неизвестно. */
    val grantedRateHz: Int = 0,
    // ── OUTPUT ──
    /** Удалось ли вообще опросить устройство вывода. false → UNKNOWN про выход. */
    val outputKnown: Boolean = false,
    /** Прямой обход системного микшера (собственный USB-UAC выход). У нас его нет. */
    val directOutput: Boolean = false,
    // ── DSP / громкость: трогали ли поток после декодера ──
    val dspApplied: Boolean = false,
    /** Программная громкость ≠ 1 либо фейд активен в тракте. */
    val gainTouched: Boolean = false,
)

/** Одна стадия готового отчёта: ключ названия, статус, строки-факты. */
data class PathStage(
    val id: String,                 // "sp.stage.decoder"
    val status: StageStatus,
    /** Пары «ключ i18n → аргумент строки». Аргумент — число/единица (ASCII). */
    val facts: List<Pair<String, String>>,
)

/** Итог разбора пути звука. Все утверждения — только из измеренного. */
data class SignalPathReport(
    val stages: List<PathStage>,
    /** Строгий побитовый вывод. Ставится true ТОЛЬКО по всем правилам ниже. */
    val bitPerfect: Boolean,
    /** 0..100 — сколько правил пути удержано; UNKNOWN не даёт баллов. */
    val integrity: Int,
    /** Ключи-причины, почему НЕ бит-в-бит (пусто, если bitPerfect или не решаем). */
    val reasonsNotBitPerfect: List<String>,
)

object SignalPath {

    fun reduce(s: SignalSnapshot): SignalPathReport {
        val stages = listOf(source(s), decoder(s), resampler(s), output(s))
        val notBitReasons = whyNotBitPerfect(s)
        // бит-в-бит = нет ни одной причины, И путь измерим достаточно, чтобы это
        // утверждать: если ключевые значения неизвестны, молчим честно, а не
        // ставим галочку «предположечно perfect».
        val measurable = s.engine == PathEngine.NATIVE && s.resamplerActive == false &&
            s.grantedRateHz > 0 && s.sourceRateHz > 0 && s.outputKnown
        val bitPerfect = notBitReasons.isEmpty() && measurable

        var score = 100
        if (s.resamplerActive == true) score -= 30
        if (rateMismatch(s)) score -= 25
        if (bitTruncated(s)) score -= 35
        if (!s.directOutput) score -= 15
        if (s.dspApplied) score -= 20
        if (s.gainTouched) score -= 10
        // Незнакомое не награждаем и не штрафуем вдвое: просто снимаем credit,
        // когда стадия не смогла сказать правду.
        if (s.resamplerActive == null) score -= 10
        if (s.outputKnown.not()) score -= 10
        score = score.coerceIn(0, 100)

        return SignalPathReport(stages, bitPerfect, score, notBitReasons)
    }

    private fun source(s: SignalSnapshot): PathStage {
        val facts = buildList {
            if (s.codec.isNotBlank()) add("sp.f_codec" to s.codec.uppercase())
            add("sp.f_lossless" to if (s.losslessSource) "1" else "0")
            if (s.sourceRateHz > 0) add("sp.f_rate" to "%.1f kHz".format(s.sourceRateHz / 1000f))
            if (s.sourceBitDepth > 0) add("sp.f_bits" to "${s.sourceBitDepth}")
            if (s.channels > 0) add("sp.f_ch" to "${s.channels}")
        }
        val known = s.sourceRateHz > 0 || s.codec.isNotBlank()
        return PathStage("sp.stage.source", if (known) StageStatus.INFO else StageStatus.UNKNOWN, facts)
    }

    private fun decoder(s: SignalSnapshot): PathStage {
        val facts = buildList {
            add("sp.f_engine" to if (s.engine == PathEngine.NATIVE) "Ripster" else "Media3")
            if (s.decoderRateHz > 0) add("sp.f_out_rate" to "${s.decoderRateHz}")
            if (s.decoderBitDepth > 0) add("sp.f_bits_out" to "${s.decoderBitDepth}")
        }
        val status = when {
            bitTruncated(s) -> StageStatus.LOSS
            s.engine == PathEngine.NATIVE && s.decoderBitDepth > 0 -> StageStatus.OK
            // Системный декодер lossy-потока разрядность не отдаёт — это Unknown,
            // а не «всё хорошо»: заявить OK без измерения значит соврать.
            else -> StageStatus.UNKNOWN
        }
        return PathStage("sp.stage.decoder", status, facts)
    }

    private fun resampler(s: SignalSnapshot): PathStage {
        val facts = buildList {
            if (s.grantedRateHz > 0) add("sp.f_granted" to "${s.grantedRateHz}")
            if (s.deviceNativeRateHz > 0) add("sp.f_device_rate" to "${s.deviceNativeRateHz}")
        }
        val status = when {
            s.resamplerActive == true -> StageStatus.LOSS
            rateMismatch(s) -> StageStatus.LOSS
            s.resamplerActive == null && inferredResample(s) -> StageStatus.WARN
            s.resamplerActive == null -> StageStatus.UNKNOWN
            else -> StageStatus.OK
        }
        return PathStage("sp.stage.src", status, facts)
    }

    private fun output(s: SignalSnapshot): PathStage {
        val facts = buildList {
            add("sp.f_direct" to if (s.directOutput) "1" else "0")
        }
        val status = when {
            !s.outputKnown -> StageStatus.UNKNOWN
            s.directOutput -> StageStatus.OK
            else -> StageStatus.INFO // общий микшер — не поломка, но и не прямой путь
        }
        return PathStage("sp.stage.output", status, facts)
    }

    /** Разрядность срезана декодером (их кейс 24→16). Требует обоих измеренных чисел. */
    private fun bitTruncated(s: SignalSnapshot): Boolean =
        s.sourceBitDepth > 0 && s.decoderBitDepth > 0 && s.decoderBitDepth < s.sourceBitDepth

    /** Частота выданная ≠ частоте файла, когда обе измерены. */
    private fun rateMismatch(s: SignalSnapshot): Boolean =
        s.grantedRateHz > 0 && s.sourceRateHz > 0 && s.grantedRateHz != s.sourceRateHz

    /** Для системы, где `resamplerActive` нечем померить: файл ≠ родной частоте → вероятно SRC. */
    private fun inferredResample(s: SignalSnapshot): Boolean =
        s.deviceNativeRateHz > 0 && s.sourceRateHz > 0 && s.deviceNativeRateHz != s.sourceRateHz

    /**
     * Список причин, по которым путь НЕ может называться бит-в-бит. Пустой —
     * единственный честный случай сказать «bit-perfect». Порядок = важность.
     */
    private fun whyNotBitPerfect(s: SignalSnapshot): List<String> = buildList {
        if (s.engine != PathEngine.NATIVE) add("sp.r_engine_system")
        if (s.resamplerActive == true) add("sp.r_resampled")
        else if (rateMismatch(s)) add("sp.r_rate_mismatch")
        if (bitTruncated(s)) add("sp.r_truncated")
        if (s.dspApplied) add("sp.r_dsp")
        if (s.gainTouched) add("sp.r_gain")
        if (!s.directOutput) add("sp.r_shared")
        // Прямой выход, которого у нас нет: native-движок идёт через AAudio и
        // системный микшер, а не через собственный USB-UAC. Отдельная причина —
        // чтобы экран не обещал того, чего движок не делает.
        if (s.engine == PathEngine.NATIVE && !s.directOutput) add("sp.r_no_uac")
    }
}
