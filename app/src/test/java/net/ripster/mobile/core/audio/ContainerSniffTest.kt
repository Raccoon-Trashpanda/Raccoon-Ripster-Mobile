package net.ripster.mobile.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Имя файла — обещание, содержимое — факт.
 *
 * Живой случай (05.09.2026, телефон владельца): `03 - Teardrop.flac` на 40 МБ,
 * внутри — фрагментированный MP4 (`ftyp … dash`) с `fLaC` в `stsd`. Звук
 * настоящий lossless, но упаковка не та, что обещает имя: расширение бралось
 * из запрошенного тира, а сервис отдал DASH. Нативный движок верил имени и
 * молча падал на ExoPlayer.
 */
class ContainerSniffTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ascii(s: String, pad: Int = 0): ByteArray {
        val head = ByteArray(pad) { 0 }
        return head + s.toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun aRealFlacIsFlac() {
        assertEquals("flac", ContainerSniff.of(ascii("fLaC") + bytes(0, 0, 0, 0x22)))
    }

    @Test
    fun theFileFromTheOwnersPhoneIsAnMp4Package() {
        """Ровно те байты, что прочитались с устройства. Внутри lossless FLAC,
        но упаковка — MP4, и имя обязано говорить именно про упаковку."""
        val head = bytes(0x00, 0x00, 0x00, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x38)
        assertEquals("m4a", ContainerSniff.of(head))
    }

    @Test
    fun wavNeedsBothRiffAndWave() {
        assertEquals("wav", ContainerSniff.of(ascii("RIFF") + ascii("size") + ascii("WAVE")))
        // RIFF без WAVE — это может быть AVI: не наше дело, и врать не надо.
        assertNull(ContainerSniff.of(ascii("RIFF") + ascii("size") + ascii("AVI ")))
    }

    @Test
    fun taggedAndUntaggedMp3AreBothMp3() {
        assertEquals("mp3", ContainerSniff.of(ascii("ID3") + bytes(3, 0, 0, 0)))
        assertEquals("mp3", ContainerSniff.of(bytes(0xFF, 0xFB, 0x90, 0x00)))
    }

    @Test
    fun oggAndDsdAreRecognised() {
        assertEquals("ogg", ContainerSniff.of(ascii("OggS") + bytes(0, 2, 0, 0)))
        assertEquals("dsf", ContainerSniff.of(ascii("DSD ") + bytes(28, 0, 0, 0)))
    }

    @Test
    fun aiffIsNotMistakenForWav() {
        assertEquals("aiff", ContainerSniff.of(ascii("FORM") + ascii("size") + ascii("AIFF")))
    }

    @Test
    fun nothingRecognisableGivesNoAnswer() {
        """Не опознали — молчим. Догадка здесь опаснее пустоты: по ней
        переименуют файл и покажут не тот кодек."""
        assertNull(ContainerSniff.of(bytes(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(ContainerSniff.of(ByteArray(0)))
        assertNull(ContainerSniff.of(bytes(0x66)))
    }

    @Test
    fun ftypIsCheckedAtOffsetFourNotZero() {
        """`ftyp` стоит после размера бокса — искать его с нуля бессмысленно."""
        assertNull(ContainerSniff.of(ascii("ftyp") + ascii("iso8")))
    }
}
