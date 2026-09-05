package net.ripster.mobile.core.service

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.ripster.mobile.core.model.Service
import net.ripster.mobile.core.model.Track
import net.ripster.mobile.player.PlayerController
import net.ripster.mobile.service.soundcloud.SoundCloudClient
import net.ripster.mobile.service.yandex.YandexMusicClient

/**
 * Автосборка жанровой станции — НЕ поиск, а собранный по жанру плейлист.
 *
 * Владелец 05.09.2026: «внедрены все сервисы, пусть строит через все сразу,
 * просто использует алгоритм под все. Обращение идёт всегда через модуль
 * определения жанра, но стучит во все сервисы, безошибочно».
 *
 * Поэтому источники НЕ перебираются по очереди с выходом на первом удачном —
 * опрашиваются ВСЕ разом и складываются:
 *
 *  * курируемые списки (станция Яндекс-ротора, чарт SoundCloud по жанру) —
 *    их жанр задан самим сервисом, проверять его нечем и незачем;
 *  * поиск по точному запросу у КАЖДОГО настроенного сервиса — его результат
 *    обязан пройти через сверку жанра, иначе в станцию попадает что угодно.
 *
 * Отказ любого источника не мешает остальным: каждый обёрнут отдельно.
 */
object StationBuilder {

    /** Почему станция не собралась — чтобы экран сказал правду, а не общее
     *  «проверьте связь». Тот же приём, что у `StreamResolver.lastStreamError`. */
    enum class Outcome { OK, NOTHING_FOUND, OFF_GENRE }

    @Volatile
    var lastOutcome: Outcome = Outcome.OK
        private set


    /**
     * Жанр, приведённый к сравнимому виду: только буквы и цифры в нижнем
     * регистре.
     *
     * Одно и то же пишут по-разному: «Synth Wave» и «Synthwave», «Drum & Bass»
     * и «drumbass», «Lo-Fi» и «lofi». Сравнение строк как есть уже стоило нам
     * рабочей плитки: «Синтвейв» отказывался собираться, хотя треки с нужным
     * жанром были — не совпал пробел.
     */
    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Слова, по которым трек считается принадлежащим жанру станции.
     * Сравнивается с жанром, объявленным сервисом.
     */
    private fun genreWords(query: String): List<String> =
        query.lowercase().split(' ', '-', '/').filter { it.length >= 3 }

    /**
     * Жанр, объявленный САМИМ сервисом. Одно поле на всех: Qobuz и Apple
     * заполняли его и раньше, SoundCloud и Яндекс — теперь тоже. Deezer в
     * поиске жанр не отдаёт (только отдельным запросом альбома), поэтому его
     * треки идут как «жанр неизвестен», а не как «жанр не тот».
     */
    private fun declaredGenre(t: Track): String? =
        (t.genre ?: t.raw["genre"])?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Совпадает ли объявленный жанр трека с тем, что обещает станция.
     *
     * Проверяем в обе стороны. «Synth Wave» у трека и «synthwave» у станции —
     * одно и то же. Трек, помеченный просто «Techno», станции «Dub Techno»
     * подходит: точность даёт сам запрос, а жанр отсекает ЧУЖОЕ — поп и рэп,
     * которые и приезжали вместо музыки.
     */
    private fun onGenre(t: Track, station: String, words: List<String>): Boolean {
        val g = norm(declaredGenre(t) ?: return false)
        if (g.isEmpty()) return false
        val q = norm(station)
        if (q.isNotEmpty() && (g.contains(q) || q.contains(g))) return true
        return words.any { w -> norm(w).length >= 4 && g.contains(norm(w)) }
    }

    /** Один опрошенный источник. [vetted] — список уже курируемый по жанру. */
    private data class Pool(val tracks: List<Track>, val vetted: Boolean)

    suspend fun build(
        scGenreSlug: String,
        fallbackQuery: String,
        yandexStationId: String? = null,
        size: Int = 30,
    ): List<Track> {
        val clients = ServiceRegistry.configured()
        val ya = ServiceRegistry.get(Service.YANDEX) as? YandexMusicClient
        val sc = ServiceRegistry.get(Service.SOUNDCLOUD) as? SoundCloudClient

        val pools: List<Pool> = coroutineScope {
            buildList {
                // Курируемые списки: жанр гарантирован сервисом.
                if (!yandexStationId.isNullOrBlank() && ya != null) {
                    add(async { Pool(runCatching { ya.station(yandexStationId, size) }.getOrDefault(emptyList()), true) })
                }
                if (scGenreSlug.isNotBlank() && sc != null) {
                    add(async { Pool(runCatching { sc.station(scGenreSlug, size) }.getOrDefault(emptyList()), true) })
                }
                // Поиск у каждого сервиса. Берём широко: дальше отсев по жанру,
                // и из десятка результатов своих может не остаться совсем.
                clients.forEach { c ->
                    add(async { Pool(runCatching { c.search(fallbackQuery).tracks.take(40) }.getOrDefault(emptyList()), false) })
                }
            }.awaitAll()
        }
        if (pools.isEmpty()) { lastOutcome = Outcome.NOTHING_FOUND; return emptyList() }

        // Сверка жанра — общая для всех сервисов, ровно один модуль на всех.
        val words = genreWords(fallbackQuery)
        val kept = pools.map { pool ->
            if (pool.vetted) pool.tracks else pool.tracks.filter { onGenre(it, fallbackQuery, words) }
        }

        // Слияние по кругу: станция получается из всех сервисов сразу, а не из
        // того, кто первым ответил.
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        var i = 0
        while (out.size < size && kept.any { i < it.size }) {
            for (list in kept) {
                val tr = list.getOrNull(i) ?: continue
                val key = (tr.isrc ?: (tr.title + "|" + tr.artist)).lowercase()
                if (seen.add(key)) out.add(tr)
                if (out.size >= size) break
            }
            i++
        }

        if (out.size >= 5) { lastOutcome = Outcome.OK; return out }

        // Своего набралось мало. Либо искать было негде, либо всё найденное
        // оказалось чужого жанра — это разные новости, и совет разный.
        val anyFound = pools.any { it.tracks.isNotEmpty() }
        val anyDeclared = pools.any { p -> p.tracks.any { declaredGenre(it) != null } }
        return when {
            !anyFound -> { lastOutcome = Outcome.NOTHING_FOUND; emptyList() }
            // Никто жанр не объявил — судить не по чему. Это отсутствие
            // сведений, а не подмена: отдаём найденное как есть.
            !anyDeclared -> {
                lastOutcome = Outcome.OK
                pools.flatMap { it.tracks }.distinctBy { (it.isrc ?: (it.title + "|" + it.artist)).lowercase() }.take(size)
            }
            else -> { lastOutcome = Outcome.OFF_GENRE; emptyList() }
        }
    }

}

/**
 * Разобрать ссылку релиза/трека любым настроенным клиентом и заиграть её
 * потоком: первые треки резолвятся сразу (чтобы заиграло без задержки),
 * остальное дорезолвится и дописывается в очередь фоном.
 */
/** Ссылка релиза из сервиса + id — для `resolve()` / [ReleasePlayback]. Пусто →
 *  плеер по этому релизу не собрать (Apple/SoundCloud на мобиле не стримятся). */
fun releaseUrl(service: Service, id: String): String {
    if (id.isBlank() || id == "0") return id.takeIf { it.startsWith("http") }.orEmpty()
    return when (service) {
        Service.DEEZER -> "https://www.deezer.com/album/$id"
        Service.QOBUZ -> "https://open.qobuz.com/album/$id"
        Service.TIDAL -> "https://listen.tidal.com/album/$id"
        Service.YANDEX -> "https://music.yandex.ru/album/$id"
        else -> id.takeIf { it.startsWith("http") }.orEmpty()
    }
}

object ReleasePlayback {

    /** Сервисы, которые реально отдают поток (для конверсии Spotify/Apple). */
    private val STREAMABLE = listOf(Service.DEEZER, Service.QOBUZ, Service.TIDAL, Service.SOUNDCLOUD)

    /**
     * @param fallbackArtwork обложка карточки релиза — подставляется трекам,
     *        у которых своей обложки нет (напр. Spotify-ссылку резолвим поиском
     *        в Deezer/Tidal, а те не всегда отдают арт → плеер был пустой).
     * @return true, если что-то удалось поставить на воспроизведение.
     */
    suspend fun play(
        player: PlayerController,
        url: String,
        quality: List<String>,
        fallbackArtwork: String? = null,
        /**
         * Кого мы открываем, если это известно вызывающему.
         *
         * Карточка радара знает артиста всегда, а вот `resolve()` по
         * apple-ссылке возвращает треки без альбома — артиста взять было
         * неоткуда, подмена отключалась, и релиз нельзя было послушать, не
         * открыв его карточку (жалоба владельца 04.09.2026).
         */
        expectArtist: String? = null,
    ): Boolean {
        val sel = withContext(Dispatchers.IO) {
            ServiceRegistry.all()
                .firstNotNullOfOrNull { runCatching { it.resolve(url) }.getOrNull() }
        }

        // 1) есть треклист от resolve() — резолвим потоки как есть
        val tracks = sel?.tracks.orEmpty()
        if (tracks.isNotEmpty()) {
            val head = StreamResolver.toStreamItems(tracks.take(4), quality, limit = 4, fallbackArtwork = fallbackArtwork)
            if (head.isNotEmpty()) {
                player.playStream(head)
                if (tracks.size > 4) {
                    player.appendStream(StreamResolver.toStreamItems(tracks.drop(4), quality, limit = 40, fallbackArtwork = fallbackArtwork))
                }
                return true
            }
        }

        // 2) треклиста нет (Spotify/Apple-ссылка отдала только альбом) — ищем
        //    релиз в «простых» сервисах (Deezer/Qobuz/Tidal) по «артист альбом»,
        //    как это делает ПК-версия, и играем их поток.
        val album = sel?.albums?.firstOrNull()
        val query = when {
            album != null -> "${album.artist} ${album.title}".trim()
            !expectArtist.isNullOrBlank() && !sel?.containerTitle.isNullOrBlank() ->
                "$expectArtist ${sel!!.containerTitle}".trim()
            !sel?.containerTitle.isNullOrBlank() -> sel!!.containerTitle!!
            else -> return false
        }
        return playSearch(
            player, query, quality, fallbackArtwork,
            // Артист вызывающего важнее: у него он точный, а `album` может быть
            // заглушкой сервиса, который ссылку лишь опознал.
            expectArtist = expectArtist?.takeIf { it.isNotBlank() } ?: album?.artist,
        )
    }

    /**
     * Заиграть релиз, известный только по строке «артист альбом» — без ссылки.
     * Нужно там, где у релиза нет id (участие артиста в сборнике/миксе, у сервиса
     * в выдаче не было album-id): тап по такой карточке должен открывать ВЕСЬ
     * релиз, а не молчать.
     */
    suspend fun playSearch(
        player: PlayerController,
        query: String,
        quality: List<String>,
        fallbackArtwork: String? = null,
        /** Кого ждём. Задан — чужие исполнители в выдаче отбрасываются. */
        expectArtist: String? = null,
    ): Boolean {
        if (query.isBlank()) return false
        // Подмена БЕЗ известного артиста запрещена.
        //
        // Совпадения по одному названию достаточно, чтобы включить чужую песню:
        // 04.09.2026 тап по релизу Etherwood заиграл Gonzalez. Раньше здесь
        // оставался «мягкий» путь — если артист неизвестен, сверять только
        // название, — и он же оказался единственным, которым чужие треки и
        // проходили. Лучше честно не сыграть, чем сыграть не то: вызывающий
        // покажет «скачай, чтобы послушать», а карточка откроет релиз.
        if (expectArtist.isNullOrBlank()) return false

        // 1) пробуем найти САМ релиз (поиск теперь отдаёт и альбомы) и заиграть
        //    его целиком — это и есть «открыть весь сборник», а не трек из него.
        val albums = withContext(Dispatchers.IO) {
            STREAMABLE.mapNotNull { ServiceRegistry.get(it) }.map { c ->
                async { runCatching { c.search(query).albums }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten()
        // Слепого «первый из выдачи» здесь БЫТЬ НЕ ДОЛЖНО. Именно он подсовывал
        // чужой релиз: по запросу «Ewterwood <трек>» сервис возвращал что-то своё,
        // и Ripster бодро играл «Heaven & Earth — I Really Love You» (жалоба
        // владельца 04.09.2026). Берём альбом, только если он ОТНОСИТСЯ к запросу:
        // совпал исполнитель либо название встречается в самом запросе.
        // Артист известен всегда (выше стоит запрет) — он и решает.
        val wantArtist = expectArtist
        val album = albums.firstOrNull { a -> TrackMatch.sameArtist(wantArtist, a.artist) }
        if (album != null) {
            val u = releaseUrl(album.service, album.id)
            if (u.isNotBlank() && play(player, u, quality, fallbackArtwork)) return true
        }

        // 2) альбома нет — играем найденные треки списком
        val fromSearch: List<Track> = coroutineScope {
            STREAMABLE.mapNotNull { ServiceRegistry.get(it) }.map { c ->
                async { runCatching { c.search(query).tracks.take(20) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten()
            // Чужого исполнителя не играем: список «что нашлось» без проверки —
            // тот же способ подсунуть не тот релиз, только треками.
            .filter { expectArtist == null || TrackMatch.sameArtist(expectArtist, it.artist) }
            .ifEmpty { return false }

        val head = StreamResolver.toStreamItems(fromSearch.take(4), quality, limit = 4, fallbackArtwork = fallbackArtwork)
        if (head.isEmpty()) return false
        player.playStream(head)
        if (fromSearch.size > 4) {
            player.appendStream(StreamResolver.toStreamItems(fromSearch.drop(4), quality, limit = 40, fallbackArtwork = fallbackArtwork))
        }
        return true
    }
}

/** Превращает треки в прямые стрим-URL для потокового воспроизведения. */
object StreamResolver {

    suspend fun toStreamItems(
        tracks: List<Track>,
        quality: List<String>,
        limit: Int = 40,
        fallbackArtwork: String? = null,
    ): List<PlayerController.StreamItem> = withContext(Dispatchers.IO) {
        // Причина прошлого отказа не должна пережить новый вызов: иначе экран
        // покажет объяснение к запросу, которого больше нет.
        lastStreamError = null
        // Dispatchers.IO здесь обязателен, а не «на всякий случай».
        //
        // `coroutineScope` наследует диспетчер вызывающего, а зовут отсюда из
        // `rememberCoroutineScope().launch` — то есть с ГЛАВНОГО потока. Внутри
        // идёт `streamInfo()`, и ни один из девяти сервисов не уходит на IO сам
        // (проверено 04.09.2026). Итог: сеть выполнялась на UI-потоке, и Android
        // показывал «Ripster isn't responding» при нажатии ▶ — жалоба тестера с
        // ANR-диалогом на треке Tidal. То же касается resolve() и search() ниже.
        tracks.take(limit).map { tr ->
            async {
                runCatching {
                    val client = ServiceRegistry.get(tr.service) ?: return@runCatching null
                    val info = client.streamInfo(tr, quality)
                    if (info.url.isBlank()) null
                    else PlayerController.StreamItem(
                        // Зашифрованный Deezer-поток метим для DataSource плеера,
                        // иначе ExoPlayer играет шифр-байты (тишина).
                        url = when (val d = info.decryption) {
                            is net.ripster.mobile.core.model.Decryption.DeezerBlowfish ->
                                net.ripster.mobile.player.tagDeezerBlowfish(info.url, d.trackId)
                            is net.ripster.mobile.core.model.Decryption.YandexAesCtr ->
                                net.ripster.mobile.player.tagYandexAesCtr(info.url, d.keyHex)
                            else -> info.url
                        },
                        title = tr.title,
                        artist = tr.artist,
                        artworkUrl = tr.artworkUrl?.takeIf { it.isNotBlank() } ?: fallbackArtwork,
                        lossless = info.quality.lossless,
                        container = info.quality.container,
                    )
                }.onFailure { lastStreamError = it }.getOrNull()
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Почему не удалось собрать ни одного потока.
     *
     * `toStreamItems` глушит отказ каждого трека намеренно: один недоступный
     * трек не должен ронять всю очередь. Но когда список вернулся ПУСТЫМ,
     * проглоченная причина — единственное, что объясняет происходящее, и без неё
     * экран показывает общее «не удалось включить». Так у Tidal терялось
     * «прямой поток недоступен — скачай трек»: человек жал ▶ и не получал
     * ничего (04.09.2026).
     *
     * Поле хуже возвращаемого значения, но менять сигнатуру ради одного экрана
     * дороже: читают его сразу после пустого результата, в той же корутине.
     */
    @Volatile
    var lastStreamError: Throwable? = null
        private set
}
