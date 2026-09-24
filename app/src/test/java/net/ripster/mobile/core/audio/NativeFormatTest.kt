package net.ripster.mobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Декодер для спектра выбирается по содержимому, а не по имени.
 *
 * Живой случай (LIVE-кампания 23.09.2026, BUG-5): на играющем треке панель
 * «Спектр» отвечала «системный декодер устройства не читает этот формат» —
 * при том что этот же файл играл. Причина оказалась не в декодере, а в
 * выборе: `Spectrogram.nativeFmt` брал формат из расширения файла, тогда как
 * проигрывание (`NativeAudioEngine.detectFormat`) берёт его из первых байтов.
 * Имя при этом врёт в обе стороны: lossless из DASH-выдачи лежит под `.flac`,
 * а внутри — MP4-упаковка (`ContainerSniffTest`), а `SpectrumSource` подписывает
 * скачанный кусок потока по Content-Type и при молчании сервиса ставит `.m4a`
 * поверх честного FLAC. В первом случае файл уходил к dr_flac, во втором — к
 * ALAC-декодеру; оба отказа складывались в одно «декодера нет».
 *
 * Здесь — чистое правило выбора, чтобы оно проверялось тестом, а не
 * наблюдением за телефоном.
 */
class NativeFormatTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ascii(s: String, pad: Int = 0): ByteArray =
        ByteArray(pad) { 0 } + s.toByteArray(Charsets.US_ASCII)

    private val flacHead = ascii("fLaC") + bytes(0, 0, 0, 0x22)
    private val mp4Head = bytes(0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70) + ascii("iso8")
    private val wavHead = ascii("RIFF") + ascii("size") + ascii("WAVE")
    private val mp3Head = ascii("ID3") + bytes(3, 0, 0, 0)
    private val wvHead = ascii("wvpk") + bytes(0, 0, 0, 0)
    private val dsfHead = ascii("DSD ") + bytes(28, 0, 0, 0)

    // ── 1. байты важнее имени ───────────────────────────────────────────────

    @Test
    fun nameIsNotTrustedWhenBytesSpeakFlac() {
        assertEquals(NativeFormat.FLAC, NativeFormat.decoderFor(flacHead, "spec_-1234567890.m4a"))
        assertEquals(NativeFormat.FLAC, NativeFormat.decoderFor(flacHead, "stream.aac"))
    }

    @Test
    fun dashFlacUnderFlacNameGoesToTheMp4DecoderNotToDrFlac() {
        // Ровно файл с телефона владельца: `NN - Название.flac`, внутри `ftyp`.
        // По имени его отдавали dr_flac, и `open` возвращал null.
        assertEquals(NativeFormat.ALAC, NativeFormat.decoderFor(mp4Head, "03 - Teardrop.flac"))
    }

    @Test
    fun wavpackAndDsdComeFromBytesToo() {
        // Имя «.dat» ничего не обещает — решает содержимое.
        assertEquals(NativeFormat.WAVPACK, NativeFormat.decoderFor(wvHead, "track.dat"))
        assertEquals(NativeFormat.DSD, NativeFormat.decoderFor(dsfHead, "track.dat"))
    }

    @Test
    fun containersOurNativeTrackDoesNotEatAreNotInvented() {
        // Настоящий mp3/ogg со своим именем: системный путь их разберёт сам, а
        // врать нативному декодеру незачем.
        assertNull(NativeFormat.decoderFor(mp3Head, "01 - track.mp3"))
        assertNull(NativeFormat.decoderFor(ascii("OggS") + bytes(0, 2, 0, 0), "01 - track.ogg"))
        assertNull(NativeFormat.decoderFor(ascii("FORM") + ascii("size") + ascii("AIFF"), "a.aiff"))
    }

    // ── 2. имя работает только там, где байты молчат ────────────────────────

    @Test
    fun extensionIsTheFallbackWhenHeadIsUnreadable() {
        assertEquals(NativeFormat.FLAC, NativeFormat.decoderFor(null, "/sdcard/Music/a.flac"))
        assertEquals(NativeFormat.WAV, NativeFormat.decoderFor(null, "/sdcard/Music/a.wav"))
        assertEquals(NativeFormat.ALAC, NativeFormat.decoderFor(null, "/sdcard/Music/a.m4a"))
        assertEquals(NativeFormat.WAVPACK, NativeFormat.decoderFor(null, "/sdcard/Music/a.wv"))
        assertEquals(NativeFormat.DSD, NativeFormat.decoderFor(null, "/sdcard/Music/a.dff"))
        assertNull(NativeFormat.decoderFor(null, "/sdcard/Music/a.mp3"))
    }

    @Test
    fun unidentifiedBytesLeaveTheLastHintToTheName() {
        // Байты прочитаны, но не опознаны — это не «чужое», а «молчим». Имя
        // остаётся последней подсказкой: цена промаха — один отказ `open`, а не
        // ложный приговор формату, из-за которого человек идёт искать кодек.
        assertEquals(NativeFormat.FLAC, NativeFormat.decoderFor(ByteArray(16) { 7 }, "a.flac"))
        assertNull(NativeFormat.decoderFor(ByteArray(16) { 7 }, "a.dat"))
    }

    // ── 3. расширение из имени ──────────────────────────────────────────────

    @Test
    fun queryAndFragmentAreNotPartOfTheExtension() {
        /** Путь стрима: `https://host/a.flac?token=1e9.2&sig=…` — «расширение»
            наивного `substringAfterLast('.')` здесь отдаёт кусок подписи. */
        assertEquals("flac", NativeFormat.extensionOf("https://host/a.flac?sig=1.2.3"))
        assertEquals("flac", NativeFormat.extensionOf("https://host/a.flac#track=2"))
    }

    @Test
    fun dotInDirectoryIsNotAnExtension() {
        /** Библиотечный путь целиком: в каталоге есть точки (`net.ripster.mobile`),
            расширения нет — раньше `substringAfterLast('.')` выдал бы
            «ripster.mobile/files/music/05 - kuumba». */
        assertEquals("", NativeFormat.extensionOf("/sdcard/net.ripster.mobile/files/Music/05 - Kuumba"))
        assertEquals("", NativeFormat.extensionOf("content://media/external/audio/media/123"))
        assertEquals("", NativeFormat.extensionOf(null))
        assertEquals("", NativeFormat.extensionOf(""))
    }

    @Test
    fun theRealLibraryPathReadsAsFlac() {
        assertEquals(
            "flac",
            NativeFormat.extensionOf(
                "/sdcard/Android/data/net.ripster.mobile/files/Music/" +
                    "Clarence Wheeler/The New Chicago Blues/05 - Kuumba.flac"
            ),
        )
    }

    @Test
    fun tooLongExtensionIsNotAnExtension() {
        assertEquals("", NativeFormat.extensionOf("backup.20260101"))
        assertNull(NativeFormat.decoderFor(null, "backup.20260101"))
    }

    @Test
    fun caseOfNameDoesNotMatter() {
        assertEquals(NativeFormat.FLAC, NativeFormat.decoderFor(null, "/Music/KUUMBA.FLAC"))
    }
}
