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
    WaveStation("techno", "techno", "techno", "genre:techno", "Techno"),
    WaveStation("trance", "trance", "trance", "genre:trance", "Trance"),
    WaveStation("ambient", "ambient", "ambient", "genre:ambient", "Ambient"),
    WaveStation("dnb", "drumbass", "drum and bass", "genre:dnb", "Drum & Bass"),
    WaveStation("jazz", "jazzblues", "jazz", "genre:jazz", "Jazz"),
    WaveStation("classical", "classical", "classical", "genre:classical", "Classical"),

    // Поджанры: у Яндекса подходящей станции НЕТ (была бы «электроника вообще»
    // или «техно вообще»). Чарт SoundCloud берём только там, где слаг называет
    // именно этот поджанр.
    WaveStation("deephouse", "deephouse", "deep house", null, "Deep House"),
    WaveStation("proghouse", "", "progressive house", null, "Progressive House"),
    WaveStation("meltech", "", "melodic techno", null, "Melodic Techno"),
    WaveStation("dubtechno", "", "dub techno", null, "Dub Techno"),
    WaveStation("downtempo", "", "downtempo", null, "Downtempo"),
    WaveStation("synthwave", "", "synthwave", null, "Synthwave"),
    WaveStation("idm", "", "idm", null, "IDM"),
    WaveStation("lofi", "", "lofi hip hop", null, "Lo-Fi"),

    // Настроения и занятия: `mood:`/`activity:` — это ровно то, что обещано.
    WaveStation("focus", "", "focus concentration music", "activity:study", nameKey = "wave.focus"),
    WaveStation("workout", "", "workout energy mix", "activity:sport", nameKey = "wave.workout"),
    WaveStation("party", "danceedm", "party dance mix", "activity:party", nameKey = "wave.party"),
    WaveStation("sleep", "ambient", "sleep calm ambient", "mood:calm", nameKey = "wave.sleep"),
    WaveStation("sunset", "deephouse", "sunset chill balearic", "mood:romantic", nameKey = "wave.sunset"),
    WaveStation("rain", "", "rainy day mellow lofi", "mood:sentimental", nameKey = "wave.rain"),
)
