package net.ripster.mobile.player

import net.ripster.mobile.core.db.LibraryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бейдж качества обязан говорить ровно то, что известно.
 *
 * Живой баг 05.09.2026: в плеере стоял `else -> Match`, то есть знак
 * «обещанное совпало» выдавался ЛЮБОМУ треку с непустой строкой формата —
 * включая файлы из папки и потоки, где обещания вообще не было. Причём в
 * бейдже, вся суть которого — не врать; его же комментарий предупреждает, что
 * награда за норму неизбежно ведёт к инфляции нормы.
 *
 * Здесь закреплены три разных утверждения, которые нельзя смешивать:
 *   · обещание было и сдержано        → Match
 *   · обещание было и нарушено        → Mismatch
 *   · обещания не было                → не Match, что бы ни лежало в файле
 */
class QualityVerdictTest {

    private fun e(
        requested: String? = null,
        container: String = "flac",
        lossless: Boolean = true,
        fake: Boolean = false,
    ) = LibraryEntity(
        id = "x", title = "t", artist = "a", album = null, serviceId = "qobuz",
        container = container, bitrateKbps = null, filePath = "/x.flac",
        sizeBytes = 0L, artworkUrl = null, addedAt = 0L,
        lossless = lossless, fakeLossless = fake, requestedQualityId = requested,
    )

    // ── обещание было и сдержано ───────────────────────────────────────────

    @Test
    fun losslessAskedAndLosslessDelivered() {
        assertTrue(PlayerController.isQualityVerified(e(requested = "flac_16")))
        assertTrue(PlayerController.isQualityVerified(e(requested = "hires_24")))
    }

    @Test
    fun lossyAskedAndLossyDelivered() {
        """Обещание «mp3 320» сдержано, если пришло lossy. Это тоже сдержанное
        обещание, а не второй сорт."""
        assertTrue(PlayerController.isQualityVerified(
            e(requested = "mp3_320", container = "mp3", lossless = false)))
    }

    // ── обещания не было — значит и подтверждать нечего ────────────────────

    @Test
    fun withoutAPromiseNothingIsVerified() {
        """Файл из папки, поток, старая запись без сохранённого тира. Раньше
        всё это получало «как обещано»."""
        assertFalse(PlayerController.isQualityVerified(e(requested = null)))
        assertFalse(PlayerController.isQualityVerified(e(requested = "")))
    }

    @Test
    fun aRealFlacWithoutAPromiseIsStillNotVerified() {
        """Файл честный и хороший — но сверять его не с чем, и знак сверки на
        нём был бы выдумкой."""
        assertFalse(PlayerController.isQualityVerified(
            e(requested = null, container = "flac", lossless = true)))
    }

    // ── обещание нарушено ──────────────────────────────────────────────────

    @Test
    fun losslessAskedButLossyDelivered() {
        val row = e(requested = "flac_16", container = "mp3", lossless = false)
        assertTrue(PlayerController.isQualityMismatch(row))
        assertFalse(PlayerController.isQualityVerified(row))
    }

    @Test
    fun aFakeLosslessIsNeverVerified() {
        """Подделка — отдельный разговор и отдельный знак; «сдержано» тут не
        стоит ни при каких условиях."""
        assertFalse(PlayerController.isQualityVerified(
            e(requested = "flac_16", lossless = false, fake = true)))
    }

    // ── не измеряли ────────────────────────────────────────────────────────

    @Test
    fun anUnmeasuredFileIsNotVerifiedEvenWithAPromise() {
        """Пустой контейнер означает, что файл не разбирали. Сверять нечем —
        и это не то же самое, что «сверили и не сошлось»."""
        val row = e(requested = "flac_16", container = "")
        assertFalse(PlayerController.isQualityVerified(row))
        assertFalse(PlayerController.isQualityMismatch(row))
    }
}
