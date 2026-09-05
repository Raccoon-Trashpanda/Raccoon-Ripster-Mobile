package net.ripster.mobile.core.service

/**
 * Станции «Волны» — что играет за плиткой на Главной.
 *
 * Правило одно, и оно важнее полноты набора: **плитка обязана играть то, что
 * написано на ней**. Источник шире названия — это не «лучше, чем ничего», это
 * подмена: человек жмёт «Синтвейв», а получает случайную электронику.
 *
 * Ровно так и случилось (жалоба владельца 05.09.2026): «синтвейв нажал…
 * открыл ликвид фанк». У плитки «Synthwave» в станциях Яндекса стояло
 * `genre:electronics` — там нет синтвейва, есть «электроника вообще», и
 * [StationBuilder] честно отдавал ПЕРВЫЙ источник, каким бы широким он ни был.
 *
 * Отсюда поля [ya] и [scSlug] заполняются ТОЛЬКО когда источник называет то же
 * самое, что и плитка. Для поджанров (deep house, melodic techno, synthwave,
 * IDM, dub techno, downtempo, lo-fi) у Яндекс-ротора подходящей станции нет —
 * значит, оставляем пусто и собираем поиском по точному [query]. Пустой
 * источник лучше широкого: он не врёт.
 *
 * Таблица лежит в ядре, а не в экране, чтобы это правило проверялось тестом
 * ([net.ripster.mobile.core.service.WaveStationsTest]), а не глазами.
 */
data class WaveStation(
    val id: String,
    /** Жанр чартов SoundCloud. Пусто — только поиск по [query]. */
    val scSlug: String,
    /** Точный запрос для сборки из всех сервисов. */
    val query: String,
    /** Станция Яндекс-ротора (`genre:` / `mood:` / `activity:`). Пусто —
     *  подходящей станции нет; выдумывать похожую нельзя. */
    val ya: String? = null,
    /** Готовое имя (жанры — латиницей, как принято). */
    val display: String = "",
    /** Либо ключ перевода (настроения и занятия). */
    val nameKey: String? = null,
    /**
     * Жанр в чарте Apple — по нему станция узнаёт, кто в этом жанре сейчас на
     * слуху, и поднимает таких артистов в эфире. Идентификаторы ЗАМЕРЕНЫ
     * живыми запросами 05.09.2026, а не взяты из головы: 5 — Classical,
     * 7 — Electronic, 11 — Jazz, 17 — Dance/House.
     *
     * Пусто там, где у Apple подходящего жанра нет: у настроений («Фокус»,
     * «Сон») жанрового чарта не бывает, и подставлять соседний — та же
     * подмена, из-за которой синтвейв играл ликвид-фанк.
     */
    val appleGenre: Int? = null,
)

/**
 * Плитки, названные ЖАНРОМ: источник обязан называть тот же жанр.
 *
 * Плитки-настроения («Фокус», «Сон», «Вечеринка») — другое дело: они и обещают
 * настроение, поэтому `mood:`/`activity:` для них точны по смыслу.
 */
val WAVE_STATIONS = listOf(
    // Точные совпадения: чарт SoundCloud и/или станция Яндекса называют ровно
    // этот жанр.
    WaveStation("techno", "techno", "techno", "genre:techno", "Techno", appleGenre = 7),
    WaveStation("trance", "trance", "trance", "genre:trance", "Trance", appleGenre = 7),
    WaveStation("ambient", "ambient", "ambient", "genre:ambient", "Ambient", appleGenre = 7),
    WaveStation("dnb", "drumbass", "drum and bass", "genre:dnb", "Drum & Bass", appleGenre = 7),
    WaveStation("jazz", "jazzblues", "jazz", "genre:jazz", "Jazz", appleGenre = 11),
    WaveStation("classical", "classical", "classical", "genre:classical", "Classical", appleGenre = 5),

    // Поджанры: у Яндекса подходящей станции НЕТ (была бы «электроника вообще»
    // или «техно вообще»). Чарт SoundCloud берём только там, где слаг называет
    // именно этот поджанр.
    WaveStation("deephouse", "deephouse", "deep house", null, "Deep House", appleGenre = 17),
    WaveStation("proghouse", "", "progressive house", null, "Progressive House", appleGenre = 17),
    WaveStation("meltech", "", "melodic techno", null, "Melodic Techno", appleGenre = 7),
    WaveStation("dubtechno", "", "dub techno", null, "Dub Techno", appleGenre = 7),
    WaveStation("downtempo", "", "downtempo", null, "Downtempo", appleGenre = 7),
    WaveStation("synthwave", "", "synthwave", null, "Synthwave", appleGenre = 7),
    WaveStation("idm", "", "idm", null, "IDM", appleGenre = 7),
    WaveStation("lofi", "", "lofi hip hop", null, "Lo-Fi", appleGenre = 7),

    // Настроения и занятия: `mood:`/`activity:` — это ровно то, что обещано.
    WaveStation("focus", "", "focus concentration music", "activity:study", nameKey = "wave.focus"),
    WaveStation("workout", "", "workout energy mix", "activity:sport", nameKey = "wave.workout"),
    WaveStation("party", "danceedm", "party dance mix", "activity:party", nameKey = "wave.party"),
    WaveStation("sleep", "ambient", "sleep calm ambient", "mood:calm", nameKey = "wave.sleep"),
    WaveStation("sunset", "deephouse", "sunset chill balearic", "mood:romantic", nameKey = "wave.sunset"),
    WaveStation("rain", "", "rainy day mellow lofi", "mood:sentimental", nameKey = "wave.rain"),
)
