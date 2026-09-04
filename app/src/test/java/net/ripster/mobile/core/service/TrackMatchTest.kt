package net.ripster.mobile.core.service

import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подмена сервиса не имеет права заиграть ДРУГОЙ трек.
 *
 * Когда свой сервис не стримит (Tidal отдаёт сегменты), Ripster ищет ту же
 * запись у Deezer/Qobuz/Яндекса. Пока это был обычный текстовый поиск, он с
 * равным успехом возвращал ремикс, лайв, кавер или чужую песню с тем же
 * названием — и человек, нажавший ▶ на конкретном треке, слушал не его, не
 * получая об этом ни слова. Требование владельца 04.09.2026: «ноль промахов».
 *
 * Здесь проверяется именно строгость: сомнительное совпадение должно быть
 * отвергнуто, даже ценой честного «скачай, чтобы послушать».
 */
class TrackMatchTest {

    private fun t(
        title: String,
        artist: String,
        service: Service = Service.DEEZER,
        durMs: Long? = 300_000,
        isrc: String? = null,
    ) = Track(id = "1", title = title, artist = artist, service = service,
        durationMs = durMs, isrc = isrc)

    private val original = t("Teardrop", "Massive Attack", Service.TIDAL)

    @Test
    fun remixIsNotTheSameRecording() {
        assertFalse(
            TrackMatch.looksLikeSameRecording(original, t("Teardrop (Mad Professor Remix)", "Massive Attack")),
        )
    }

    @Test
    fun liveIsNotTheSameRecording() {
        assertFalse(TrackMatch.looksLikeSameRecording(original, t("Teardrop (Live)", "Massive Attack")))
    }

    @Test
    fun coverByAnotherArtistIsRejected() {
        assertFalse(TrackMatch.looksLikeSameRecording(original, t("Teardrop", "Newton Faulkner")))
    }

    @Test
    fun differentLengthIsRejected() {
        // Та же подпись, но на полторы минуты короче — это другая запись.
        assertFalse(
            TrackMatch.looksLikeSameRecording(original, t("Teardrop", "Massive Attack", durMs = 210_000)),
        )
    }

    @Test
    fun sameRecordingWithLongerCreditsIsAccepted() {
        // Сервисы пишут состав по-разному — это НЕ повод отвергать.
        assertTrue(
            TrackMatch.looksLikeSameRecording(
                original,
                t("Teardrop", "Massive Attack, Elizabeth Fraser"),
            ),
        )
    }

    @Test
    fun featuringInTitleDoesNotBreakTheMatch() {
        assertTrue(
            TrackMatch.looksLikeSameRecording(original, t("Teardrop (feat. Elizabeth Fraser)", "Massive Attack")),
        )
    }

    @Test
    fun punctuationAndCaseDoNotMatter() {
        assertTrue(
            TrackMatch.looksLikeSameRecording(t("Don't Stop!", "Foo"), t("dont stop", "foo")),
        )
    }

    @Test
    fun unknownDurationDoesNotBlockAnOtherwiseExactMatch() {
        // Длительность знаем не всегда; остальные признаки совпали.
        assertTrue(
            TrackMatch.looksLikeSameRecording(
                t("Teardrop", "Massive Attack", durMs = null),
                t("Teardrop", "Massive Attack", durMs = null),
            ),
        )
    }

    @Test
    fun spedUpAndSlowedEditsAreRejected() {
        assertFalse(TrackMatch.looksLikeSameRecording(original, t("Teardrop (Sped Up)", "Massive Attack")))
        assertFalse(TrackMatch.looksLikeSameRecording(original, t("Teardrop - Slowed + Reverb", "Massive Attack")))
    }
}
