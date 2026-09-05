package net.ripster.mobile.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Плитка «Волны» обязана играть то, что на ней написано.
 *
 * Жалоба владельца 05.09.2026: «синтвейв нажал в эмуляторе на главном экране,
 * открыл ликвид фанк». У плитки в источниках стояла станция Яндекса
 * `genre:electronics` — синтвейва там нет, есть «электроника вообще», а
 * [StationBuilder] отдаёт ПЕРВЫЙ источник, каким бы широким тот ни был.
 *
 * Проверяется здесь не «работает ли сеть», а сама таблица: подмена жанра
 * заводится в неё данными и на устройстве выглядит как случайная музыка.
 */
class WaveStationsTest {

    /** Станции ротора, которые называют ровно один жанр. */
    /**
     * Станции ротора, чьё имя РОВНО совпадает с обещанием плитки.
     *
     * Каждая строка проверена живым запросом к `rotor/stations/list`
     * (05.09.2026, 161 жанровая станция), а не взята из головы. Ровно на этом
     * сторож меня и поймал: в первой версии новых плиток я подставил
     * `genre:rock`, `genre:metal` и прочие, не спросив, существуют ли они.
     *
     * Пары вроде `genre:rap` → «hip hop» и `genre:latinfolk` → «latin» — это
     * не подмена, а разные названия одного жанра у разных сервисов; здесь они
     * и записаны, чтобы решение было видно, а не растворено в коде.
     */
    private val exactGenre = mapOf(
        "genre:techno" to "techno",
        "genre:trance" to "trance",
        "genre:ambient" to "ambient",
        "genre:dnb" to "drum and bass",
        "genre:jazz" to "jazz",
        "genre:classical" to "classical",
        "genre:rap" to "hip hop",
        "genre:rock" to "rock",
        "genre:metal" to "metal",
        "genre:blues" to "blues",
        "genre:country" to "country",
        "genre:pop" to "pop",
        "genre:indie" to "indie rock",
        "genre:soul" to "soul",
        "genre:disco" to "disco",
        "genre:funk" to "funk",
        "genre:reggae" to "reggae",
        "genre:dub" to "dub",
        "genre:rnb" to "r&b",
        "genre:postpunk" to "post-punk",
        "genre:newwave" to "new wave",
        "genre:latinfolk" to "latin",
        "genre:house" to "house",
        "genre:dubstep" to "dubstep",
    )

    @Test
    fun noGenreTileBorrowsABroaderStation() {
        """`genre:electronics` под «Синтвейвом» — это и есть та самая подмена."""
        val offenders = WAVE_STATIONS.filter { st ->
            val ya = st.ya ?: return@filter false
            ya.startsWith("genre:") && exactGenre[ya] != st.query
        }.map { "${it.id} -> ${it.ya} (обещает «${it.query}»)" }

        assertTrue(
            "Плитка просит станцию шире собственного названия — человек получит " +
                "не то, на что нажал:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun synthwaveHasNoStationAtAll() {
        """У Яндекс-ротора нет синтвейва. Пустой источник честнее похожего:
        станция соберётся поиском по точному запросу."""
        val st = WAVE_STATIONS.first { it.id == "synthwave" }
        assertNull(st.ya)
        assertEquals("", st.scSlug)
        assertEquals("synthwave", st.query)
    }

    @Test
    fun subGenresDoNotFallBackToTheParentGenre() {
        for (id in listOf("meltech", "dubtechno", "proghouse", "idm", "downtempo", "lofi")) {
            val st = WAVE_STATIONS.first { it.id == id }
            assertNull("$id тянет чужую станцию", st.ya)
            assertEquals("$id тянет чужой чарт", "", st.scSlug)
        }
    }

    @Test
    fun deepHouseKeepsItsOwnChart() {
        """У SoundCloud слаг `deephouse` — это именно deep house, а не house."""
        val st = WAVE_STATIONS.first { it.id == "deephouse" }
        assertEquals("deephouse", st.scSlug)
        assertNull(st.ya)
    }

    @Test
    fun moodTilesMayUseMoodStations() {
        """«Фокус» и «Сон» обещают настроение, а не жанр — им `mood:`/`activity:`
        как раз по смыслу."""
        val moods = WAVE_STATIONS.filter { it.nameKey != null }
        assertTrue(moods.isNotEmpty())
        assertTrue(moods.all { it.ya == null || it.ya.startsWith("mood:") || it.ya.startsWith("activity:") })
    }

    @Test
    fun everyTileCanStillBuildSomething() {
        """Убрав врущий источник, нельзя оставить плитку вообще без опоры:
        точный запрос обязан быть у каждой."""
        assertTrue(WAVE_STATIONS.all { it.query.isNotBlank() })
    }

    @Test
    fun idsAreUnique() {
        assertEquals(WAVE_STATIONS.size, WAVE_STATIONS.map { it.id }.toSet().size)
    }

    @Test
    fun everyTileHasAName() {
        assertTrue(WAVE_STATIONS.all { it.display.isNotBlank() || it.nameKey != null })
    }
}
