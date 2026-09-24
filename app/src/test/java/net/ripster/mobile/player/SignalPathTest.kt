package net.ripster.mobile.player

import net.ripster.mobile.ui.i18n.AppLang
import net.ripster.mobile.ui.i18n.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Вердикт сигнального пути обязан говорить правду, а не приятное.
 *
 * Это тот же грех, на котором поймали референса: зелёное «bit-perfect» висело
 * над активным ресемплером и срезанной разрядностью
 * (COMPETITOR_resonada.md:195-217). У нас правило живёт в чистом
 * [SignalPath.reduce] и здесь закреплено так, что позеленеть не сможет:
 * любое «не измерено» — UNKNOWN, а не OK, и бит-в-бит не утверждается, пока
 * хоть одно условие не подтверждено числом.
 */
class SignalPathTest {

    private fun native(
        bits: Int = 16, rate: Int = 44100, granted: Int = 44100, resampled: Boolean? = false,
        direct: Boolean = false, dsp: Boolean = false,
    ) = SignalSnapshot(
        engine = PathEngine.NATIVE, codec = "flac", losslessSource = true,
        sourceBitDepth = bits, sourceRateHz = rate, channels = 2,
        decoderBitDepth = bits, decoderRateHz = rate,
        resamplerActive = resampled, grantedRateHz = granted,
        deviceNativeRateHz = 48000, outputKnown = true, directOutput = direct,
        dspApplied = dsp,
    )

    private fun system(bits: Int = 0, rate: Int = 44100, devRate: Int = 48000) = SignalSnapshot(
        engine = PathEngine.SYSTEM, codec = "mp3", losslessSource = false,
        sourceBitDepth = bits, sourceRateHz = rate, channels = 2,
        deviceNativeRateHz = devRate, outputKnown = devRate > 0,
    )

    // ── главное правило: никакого bit-perfect над ресемплером ───────────────

    @Test
    fun neverBitPerfectWhileResamplerActive() {
        val r = SignalPath.reduce(native(resampled = true, granted = 48000))
        assertFalse(r.bitPerfect)
        assertTrue("причина «ресемпл» обязана быть названа", r.reasonsNotBitPerfect.contains("sp.r_resampled"))
        // стадия SRC не имеет права выглядеть чистой при активном SRC
        assertEquals(
            StageStatus.LOSS,
            r.stages.first { it.id == "sp.stage.src" }.status,
        )
    }

    @Test
    fun neverBitPerfectWithoutMeasuredValues() {
        // Всё нулевое/неизвестное — это не «отлично», а «нечем утверждать».
        val r = SignalPath.reduce(SignalSnapshot(engine = PathEngine.NATIVE))
        assertFalse(r.bitPerfect)
    }

    @Test
    fun systemPathIsNeverBitPerfect() {
        assertFalse(SignalPath.reduce(system()).bitPerfect)
        assertTrue(SignalPath.reduce(system()).reasonsNotBitPerfect.contains("sp.r_engine_system"))
    }

    @Test
    fun ourSharedNativePathDoesNotClaimBitPerfect() {
        // Даже идеальный нативный тракт идёт через AAudio/общий микшер, прямого
        // USB-UAC у нас нет — значит и обещать бит-в-бит нельзя. Это то, чего
        // референс сам себе не позволил бы, но мы держим слово буквальнее.
        val r = SignalPath.reduce(native(resampled = false, granted = 44100, direct = false))
        assertFalse(r.bitPerfect)
        assertTrue(r.reasonsNotBitPerfect.contains("sp.r_shared"))
        assertTrue(r.reasonsNotBitPerfect.contains("sp.r_no_uac"))
    }

    @Test
    fun bitPerfectRequiresDirectOutputAndAllMeasurements() {
        val r = SignalPath.reduce(native(bits = 24, rate = 96000, granted = 96000, resampled = false, direct = true))
        assertTrue(r.bitPerfect)
        assertTrue(r.reasonsNotBitPerfect.isEmpty())
        assertEquals(100, r.integrity.coerceAtLeast(85))
    }

    // ── потеря разрядности (их кейс 24 → 16) ────────────────────────────────

    @Test
    fun detectsDecoderTruncation() {
        val s = native(bits = 24, rate = 44100, granted = 44100, direct = true)
            .copy(decoderBitDepth = 16)
        val r = SignalPath.reduce(s)
        assertFalse(r.bitPerfect)
        assertEquals(StageStatus.LOSS, r.stages.first { it.id == "sp.stage.decoder" }.status)
        assertTrue(r.reasonsNotBitPerfect.contains("sp.r_truncated"))
    }

    @Test
    fun rateMismatchIsLossNotOk() {
        val r = SignalPath.reduce(native(granted = 48000, rate = 44100, resampled = false))
        assertEquals(StageStatus.LOSS, r.stages.first { it.id == "sp.stage.src" }.status)
        assertTrue(r.reasonsNotBitPerfect.contains("sp.r_rate_mismatch"))
    }

    // ── честное «не измерить» вместо выдуманного OK ─────────────────────────

    @Test
    fun unknownResamplerOnSharedInferredAsWarn() {
        // Системный тракт не умеет сказать про SRC напрямую; если частота файла
        // ≠ родной частоте устройства — это правдоподобный ресемпл, WARN, но не OK.
        val r = SignalPath.reduce(system(rate = 44100, devRate = 48000))
        val src = r.stages.first { it.id == "sp.stage.src" }.status
        assertEquals(StageStatus.WARN, src)
    }

    @Test
    fun unknownResamplerWithoutDeviceRateIsUnknown() {
        val r = SignalPath.reduce(system(rate = 44100, devRate = 0))
        assertEquals(StageStatus.UNKNOWN, r.stages.first { it.id == "sp.stage.src" }.status)
    }

    @Test
    fun systemDecoderWithoutBitDepthIsUnknownNotClean() {
        val r = SignalPath.reduce(system(bits = 0))
        assertEquals(StageStatus.UNKNOWN, r.stages.first { it.id == "sp.stage.decoder" }.status)
    }

    // ── инварианты ──────────────────────────────────────────────────────────

    @Test
    fun integrityStaysInRange() {
        listOf(native(), native(resampled = true, granted = 48000, dsp = true), system(), SignalSnapshot())
            .forEach { s ->
                val v = SignalPath.reduce(s).integrity
                assertTrue("integrity=$v out of range", v in 0..100)
            }
    }

    @Test
    fun chainAlwaysHasFourStages() {
        assertEquals(4, SignalPath.reduce(native()).stages.size)
    }

    // ── ни один ключ не должен уехать на экран сырым ────────────────────────

    @Test
    fun everyEmittedKeyIsTranslatedInAllLanguages() {
        val snapshots = listOf(
            native(direct = true), native(resampled = true, granted = 48000),
            native(bits = 24, direct = true).let { it.copy(decoderBitDepth = 16) },
            native(granted = 48000), system(), system(bits = 24, devRate = 44100), SignalSnapshot(),
        )
        val keys = LinkedHashSet<String>()
        for (s in snapshots) {
            val r = SignalPath.reduce(s)
            keys += r.stages.map { it.id }
            r.stages.forEach { st -> keys += st.facts.map { it.first } }
            keys += r.reasonsNotBitPerfect
        }
        keys += StageStatus.entries.map { "sp.st_${it.name.lowercase()}" }
        keys += listOf(
            "sp.title", "sp.verdict_bitperfect", "sp.verdict_not", "sp.integrity",
            "sp.r_dsp", "sp.r_gain", "common.yes", "common.no",
        )
        val misses = ArrayList<String>()
        for (k in keys) for (lang in AppLang.entries) {
            val t = tr(k, lang)
            if (t == k || t.isBlank()) misses += "$k/${lang.tag}"
        }
        assertTrue("нет перевода (экран покажет технический ключ):\n${misses.joinToString("\n")}", misses.isEmpty())
    }
}
