package net.ripster.mobile.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Модуль выучивает синонимы жанров сам, из того, что и так через него идёт.
 *
 * Зачем: посевная таблица стареет молча и не переживает смену локали — Deezer
 * 05.09.2026 отдал словарь целиком на болгарском. Руками за этим не успеть.
 *
 * Проверяются не «умные» сценарии, а границы, которые легко потерять: что
 * модуль НЕ учит с одного раза, НЕ учит на разнобое, НЕ портит посев и что
 * недоказанное остаётся «не знаю», а не превращается в ответ.
 */
class GenreLearnerTest {

    private fun learner() = GenreLearner()

    @Test
    fun anUnknownLabelIsLearnedFromAnAnchorOnTheSameArtist() {
        """Связь даёт артист: «Techno» модуль знает из посева, «τεχνο» — нет.
        Но подтвердить её должны РАЗНЫЕ артисты, а не одна и та же пара,
        встреченная несколько раз."""
        val l = learner()
        listOf("Nina Kraviz", "Jeff Mills", "Robert Hood").forEach { a ->
            l.observe(a, "Techno")
            l.observe(a, "τεχνο")
        }
        assertEquals(GenreKey.TECHNO, l.of("τεχνο"))
    }

    @Test
    fun oneArtistSeenManyTimesDoesNotProveAnything() {
        """Свидетельство — это разные артисты, а не повторные встречи одного.
        Иначе достаточно прокрутить станцию трижды, чтобы «доказать» что
        угодно."""
        val l = learner()
        repeat(50) {
            l.observe("Nina Kraviz", "Techno")
            l.observe("Nina Kraviz", "только-у-неё")
        }
        assertNull(l.of("только-у-неё"))
    }

    @Test
    fun oneSightingIsNotEnough() {
        """У артиста бывает разовый случайный ярлык — он не должен становиться
        правилом для всех."""
        val l = learner()
        l.observe("Nina Kraviz", "Techno")
        l.observe("Nina Kraviz", "какой-то-ярлык")
        assertNull(l.of("какой-то-ярлык"))
    }

    @Test
    fun aDisagreeingArtistTeachesNothing() {
        """Сборники и артисты на стыке жанров дают разнобой. Любая догадка
        тут — подмена, а не сведение."""
        val l = learner()
        repeat(10) {
            l.observe("Сборник", "Techno")
            l.observe("Сборник", "Jazz")
            l.observe("Сборник", "непонятно-что")
        }
        assertNull(l.of("непонятно-что"))
    }

    @Test
    fun theSeedIsNeverOverwritten() {
        """Выученное только ДОПОЛНЯЕТ посев, поэтому ошибка обучения не может
        испортить то, что и так работало."""
        val l = learner()
        repeat(20) {
            l.observe("Странный артист", "Jazz")
            l.observe("Странный артист", "Rock")   // оба из посева → разнобой
        }
        assertEquals(GenreKey.ROCK, l.of("Rock"))
        assertEquals(GenreKey.JAZZ, l.of("Jazz"))
    }

    @Test
    fun halfProvenIsStillUnknown() {
        """Половина доказательства хуже отсутствия: она выглядит как знание."""
        val l = learner()
        repeat(GenreLearner.MIN_EVIDENCE - 1) {
            l.observe("Nina Kraviz", "Techno")
            l.observe("Nina Kraviz", "новый-ярлык")
        }
        assertNull(l.of("новый-ярлык"))
        assertEquals(0, l.learnedCount())
    }

    @Test
    fun evidenceFromDifferentArtistsAddsUp() {
        val l = learner()
        listOf("Nina Kraviz", "Jeff Mills", "Robert Hood").forEach { a ->
            l.observe(a, "Techno")
            l.observe(a, "Техно-по-своему")
        }
        assertEquals(GenreKey.TECHNO, l.of("Техно-по-своему"))
        assertEquals(1, l.learnedCount())
    }

    @Test
    fun theBulgarianDeezerVocabularyIsAcquired() {
        """Ровно тот случай, ради которого модуль и существует. Строки взяты
        из живого замера 05.09.2026: Deezer по гео-локали отдал словарь
        целиком на болгарском, и посев его намеренно не знает.

        Здесь видно, как связь возникает сама: у трёх артистов один сервис
        назвал жанр по-английски, другой — по-болгарски. Ни одной строки в
        таблице для этого писать не пришлось."""
        val l = learner()
        assertNull("посев его знать не должен", GenreKey.of("Блус"))
        listOf("B.B. King", "Muddy Waters", "Howlin Wolf").forEach { a ->
            l.observe(a, "Blues")     // Apple
            l.observe(a, "Блус")      // Deezer, болгарская локаль
        }
        assertEquals(GenreKey.BLUES, l.of("Блус"))
    }

    @Test
    fun emptyInputIsIgnored() {
        val l = learner()
        l.observe("", "Techno")
        l.observe("Nina Kraviz", null)
        l.observe("Nina Kraviz", "")
        assertEquals(0, l.learnedCount())
        assertNull(l.of(null))
    }

    @Test
    fun whatIsLearnedSurvivesARestart() {
        val l = learner()
        listOf("A", "B", "C").forEach {
            l.observe(it, "Techno"); l.observe(it, "своё-слово")
        }
        val saved = l.snapshot()
        val fresh = learner()
        assertNull("до восстановления знать неоткуда", fresh.of("своё-слово"))
        fresh.restore(saved)
        assertEquals(GenreKey.TECHNO, fresh.of("своё-слово"))
    }

    @Test
    fun theSerialisedFormSurvivesARoundTrip() {
        val l = learner()
        listOf("A", "B", "C").forEach { l.observe(it, "Techno"); l.observe(it, "свой-ярлык") }
        val text = l.serialize()
        val fresh = learner()
        fresh.restoreFrom(text)
        assertEquals(GenreKey.TECHNO, fresh.of("свой-ярлык"))
    }

    @Test
    fun aBrokenStoreDoesNotLoseEverythingElse() {
        """Битая строка хранилища — не повод терять всё выученное: половину,
        которая читается, оставляем."""
        val l = learner()
        listOf("A", "B", "C").forEach { l.observe(it, "Techno"); l.observe(it, "целое") }
        val good = l.serialize()
        val fresh = learner()
        fresh.restoreFrom("мусор-без-равно;$good;ещё=мусор:непонятно")
        assertEquals(GenreKey.TECHNO, fresh.of("целое"))
    }

    @Test
    fun anEmptyStoreIsNotACrash() {
        val l = learner()
        l.restoreFrom(null)
        l.restoreFrom("")
        l.restoreFrom(";;;")
        assertEquals(0, l.learnedCount())
    }

    @Test
    fun featuringsCountAsOneArtistNotThree() {
        """Три написания одного имени — это ОДИН свидетель, а не три. Иначе
        достаточно совместных треков, чтобы набрать вес из ниоткуда."""
        val l = learner()
        l.observe("Nina Kraviz", "Techno")
        l.observe("Nina Kraviz, Someone", "новое")
        l.observe("Nina Kraviz feat. Someone", "новое")
        l.observe("nina kraviz", "новое")
        assertNull(l.of("новое"))

        // А вот два ДРУГИХ артиста добирают вес до порога.
        listOf("Jeff Mills", "Robert Hood").forEach {
            l.observe(it, "Techno")
            l.observe(it, "новое")
        }
        assertTrue(l.of("новое") == GenreKey.TECHNO)
    }
}
