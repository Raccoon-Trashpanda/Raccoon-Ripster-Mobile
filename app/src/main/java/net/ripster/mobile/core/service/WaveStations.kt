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

    // Большие жанры. Владелец 05.09.2026: «жанров и настроений должно быть
    // больше, инди, соул, диско и так далее».
    //
    // Номера жанров Apple взяты живым запросом к `catalog/us/genres`, а не из
    // головы: 20 Alternative, 21 Rock, 15 R&B/Soul, 18 Hip-Hop/Rap, 14 Pop,
    // 24 Reggae, 12 Latin, 2 Blues, 6 Country, 1153 Metal, 19 Worldwide.
    // Там, где у Apple отдельного жанра нет (диско, фанк, соул-фанк живут
    // внутри R&B/Soul и Dance), ставим ближайший НАСТОЯЩИЙ, а не выдумываем
    // номер: подборку всё равно ищем по названию жанра, чарт лишь подсказывает.
    WaveStation("indie", "indie", "indie rock", "genre:indie", "Indie", appleGenre = 20),
    WaveStation("indiepop", "", "indie pop", null, "Indie Pop", appleGenre = 20),
    WaveStation("soul", "soul", "soul", "genre:soul", "Soul", appleGenre = 15),
    WaveStation("rnb", "rbsoul", "r&b", "genre:rnb", "R&B", appleGenre = 15),
    WaveStation("disco", "disco", "disco", "genre:disco", "Disco", appleGenre = 17),
    WaveStation("funk", "funk", "funk", "genre:funk", "Funk", appleGenre = 15),
    WaveStation("hiphop", "hiphoprap", "hip hop", "genre:rap", "Hip-Hop", appleGenre = 18),
    WaveStation("rock", "rock", "rock", "genre:rock", "Rock", appleGenre = 21),
    WaveStation("postpunk", "", "post-punk", "genre:postpunk", "Post-Punk", appleGenre = 20),
    WaveStation("shoegaze", "", "shoegaze", null, "Shoegaze", appleGenre = 20),
    WaveStation("metal", "metal", "metal", "genre:metal", "Metal", appleGenre = 1153),
    WaveStation("blues", "jazzblues", "blues", "genre:blues", "Blues", appleGenre = 2),
    WaveStation("reggae", "reggae", "reggae", "genre:reggae", "Reggae", appleGenre = 24),
    WaveStation("dub", "", "dub", "genre:dub", "Dub", appleGenre = 24),
    WaveStation("afrobeat", "", "afrobeat", null, "Afrobeat", appleGenre = 19),
    WaveStation("latin", "latin", "latin", "genre:latinfolk", "Latin", appleGenre = 12),
    WaveStation("country", "country", "country", "genre:country", "Country", appleGenre = 6),
    WaveStation("pop", "pop", "pop", "genre:pop", "Pop", appleGenre = 14),
    WaveStation("garage", "", "uk garage", null, "UK Garage", appleGenre = 17),
    WaveStation("breakbeat", "", "breakbeat", null, "Breakbeat", appleGenre = 7),
    WaveStation("hardtechno", "", "hard techno", null, "Hard Techno", appleGenre = 7),
    WaveStation("minimal", "", "minimal techno", null, "Minimal", appleGenre = 7),
    WaveStation("psytrance", "", "psytrance", null, "Psytrance", appleGenre = 7),
    WaveStation("jungle", "", "jungle", null, "Jungle", appleGenre = 7),
    WaveStation("liquid", "", "liquid drum and bass", null, "Liquid DnB", appleGenre = 7),
    WaveStation("electronica", "", "electronica", null, "Electronica", appleGenre = 7),
    WaveStation("triphop", "", "trip hop", null, "Trip-Hop", appleGenre = 7),
    WaveStation("newwave", "", "new wave", "genre:newwave", "New Wave", appleGenre = 20),
    WaveStation("bossa", "", "bossa nova", null, "Bossa Nova", appleGenre = 11),
    WaveStation("nujazz", "", "nu jazz", null, "Nu Jazz", appleGenre = 11),

    // Настроения и занятия: `mood:`/`activity:` — это ровно то, что обещано.
    WaveStation("focus", "", "focus concentration music", "activity:study", nameKey = "wave.focus"),
    WaveStation("workout", "", "workout energy mix", "activity:sport", nameKey = "wave.workout"),
    WaveStation("party", "danceedm", "party dance mix", "activity:party", nameKey = "wave.party"),
    WaveStation("sleep", "ambient", "sleep calm ambient", "mood:calm", nameKey = "wave.sleep"),
    WaveStation("sunset", "deephouse", "sunset chill balearic", "mood:romantic", nameKey = "wave.sunset"),
    WaveStation("rain", "", "rainy day mellow lofi", "mood:sentimental", nameKey = "wave.rain"),
    WaveStation("morning", "", "morning coffee mellow", "mood:happy", nameKey = "wave.morning"),
    WaveStation("night", "", "late night drive", "mood:sad", nameKey = "wave.night"),
    WaveStation("road", "", "road trip driving", "activity:driving", nameKey = "wave.road"),
    WaveStation("cook", "", "dinner background jazz", "activity:cooking", nameKey = "wave.cook"),
)
