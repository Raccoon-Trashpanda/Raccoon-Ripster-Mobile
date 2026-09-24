package net.ripster.mobile.core.service

import net.ripster.mobile.core.errors.attempt

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
/** Один опрошенный источник станции — см. [StationAssembly.Source]. */
private typealias Pool = StationAssembly.Source

object StationBuilder {

    /**
     * Дирижёр жанров. Ставится один раз из RipsterApp; до этого работает
     * пустой, то есть ровно как посевная таблица — станция обязана строиться
     * и без него.
     */
    var genres: GenreLearner = GenreLearner()

    /** Почему станция не собралась — чтобы экран сказал правду, а не общее
     *  «проверьте связь». Тот же приём, что у `StreamResolver.lastStreamError`. */
    enum class Outcome { OK, NOTHING_FOUND, OFF_GENRE }

    @Volatile
    var lastOutcome: Outcome = Outcome.OK
        private set

    /**
     * Треки артистов, которые этот жанр и делают.
     *
     * Канон берётся из MusicBrainz ([GenreCanon]) — там артистов размечают
     * тегами с весом, и по тегу видно, кто в жанре свой. Играем их топ-треки
     * из Deezer: он открыт, без ключа, и отдаёт то, что у артиста РЕАЛЬНО
     * слушают, а не то, что подошло под запрос.
     *
     * Список считается курируемым: жанр здесь гарантирован не тегом трека, а
     * самим артистом, и сверять его ещё раз незачем — у Deezer в топе трека
     * жанра нет, и проверка выбросила бы весь канон.
     */
    private suspend fun canonPool(
        genre: String,
        clients: List<ServiceClient>,
        artists: Int = 10,
        perArtist: Int = 3,
    ): List<Track> {
        val canon = GenreCanon.artists(genre).take(artists)
        if (canon.isEmpty() || clients.isEmpty()) return emptyList()

        // У Deezer есть готовый «топ артиста» — один запрос вместо поиска.
        // Но опираться ТОЛЬКО на него нельзя: у человека может не быть ничего,
        // кроме токена Qobuz или Tidal, а жанровая волна обязана работать при
        // любом наборе подключённого (замечание владельца 05.09.2026).
        val dz = clients.filterIsInstance<net.ripster.mobile.service.deezer.DeezerClient>().firstOrNull()

        val byArtist = coroutineScope {
            canon.map { a ->
                async {
                    val viaDeezer = dz?.let {
                        attempt { it.artistTopTracks(a.name, perArtist) }.getOrDefault(emptyList())
                    }.orEmpty()
                    if (viaDeezer.isNotEmpty()) viaDeezer else anyServiceTracks(clients, a.name, perArtist)
                }
            }.awaitAll()
        }

        // ПО ОДНОМУ от каждого артиста, а не подряд всё его.
        // Сложенные встык списки давали четыре трека Scandroid друг за другом —
        // это не волна жанра, а мини-альбом одного артиста.
        val out = ArrayList<Track>()
        var i = 0
        while (byArtist.any { i < it.size }) {
            for (list in byArtist) list.getOrNull(i)?.let { out.add(it) }
            i++
        }
        return out
    }

    /**
     * Вещи этого артиста у любого подключённого сервиса.
     *
     * Готового «топа артиста» у остальных нет, поэтому спрашиваем поиском по
     * имени и оставляем только то, где ЭТОТ артист действительно указан:
     * поиск охотно отдаёт однофамильцев и каверы, а в станции жанра они
     * выглядят ровно как та «шляпа», от которой всё и затевалось.
     */
    private suspend fun anyServiceTracks(
        clients: List<ServiceClient>,
        name: String,
        limit: Int,
    ): List<Track> = coroutineScope {
        clients.map { c -> async { attempt { c.search(name).tracks }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .filter { ChartBoost.isSameArtist(it.artist, name) }
            .distinctBy { (it.isrc ?: (it.title + "|" + it.artist)).lowercase() }
            .take(limit)
    }

    /**
     * Собрать эфир. Источники опрашиваются ВСЕ разом, дальше решает
     * [StationAssembly] (сверка жанра и очередь); здесь — только сеть, кэш
     * дирижёра и честный ответ «почему не собралось».
     *
     * Про `vetted`/`lead` у пулов: чарт SoundCloud по жанру верен жанрово, но
     * по качеству неровен — в «Техно» из него приезжали «epileptic techno» и
     * «dddance perfect slowed» рядом с Nina Kraviz и Aphex Twin. Такое
     * допустимо на доборе, но не во главе станции.
     */
    suspend fun build(
        scGenreSlug: String,
        fallbackQuery: String,
        yandexStationId: String? = null,
        size: Int = 30,
        /**
         * Жанр в чарте Apple. По нему станция узнаёт, кто в этом жанре сейчас
         * на слуху, и поднимает таких артистов — играем при этом оттуда,
         * откуда умеем. `null` — у Apple подходящего жанра нет.
         */
        appleGenreId: Int? = null,
        /**
         * Чем отличается ЭТОТ заход от прошлого. Один и тот же seed даёт один и
         * тот же эфир (иначе список дёргался бы на каждой перерисовке), новый —
         * другой. Ноль означает «без ротации»: удобно в тестах.
         */
        rotationSeed: Long = 0L,
        /**
         * Мост к ПК. Если пара есть, ПК отдаёт редакторскую подборку Apple по
         * этому жанру — самый качественный из доступных источников (её собирал
         * редактор, а не размечал кто попало). `null` — пары нет, станция
         * строится по-старому.
         */
        pc: net.ripster.mobile.core.pair.PcBridge? = null,
        /**
         * Что этот человек уже слушает (из play_history). Пустой вкус —
         * законное «не знаю»: станция просто строится без этого признака.
         */
        taste: StationRanker.Taste = StationRanker.Taste.EMPTY,
    ): List<Track> {
        // Вкус и жанровые подсказки с ПК. Дирижёр учится на них ровно так же,
        // как на пулах: это те же пары «артист — ярлык», просто ПК знает их
        // больше, чем телефон когда-либо увидит сам.
        val pcTaste = if (pc?.paired == true) pc.taste() else null
        pcTaste?.genreHints?.forEach { (artist, raw) -> genres.observe(artist, raw) }
        val effectiveTaste = if (pcTaste == null || pcTaste.weights.isEmpty()) taste
            else taste.plus(StationRanker.Taste.fromWeights(pcTaste.weights))

        val clients = ServiceRegistry.configured()
        val ya = ServiceRegistry.get(Service.YANDEX) as? YandexMusicClient
        val sc = ServiceRegistry.get(Service.SOUNDCLOUD) as? SoundCloudClient

        val pools: List<Pool> = coroutineScope {
            buildList {
                // Курируемые списки: жанр гарантирован сервисом.
                if (!yandexStationId.isNullOrBlank() && ya != null) {
                    add(async { Pool(attempt { ya.station(yandexStationId, size) }.getOrDefault(emptyList()), true) })
                }
                if (scGenreSlug.isNotBlank() && sc != null) {
                    // Жанрово верен, но во главу не ставим — см. Pool.lead.
                    add(
                        async {
                            Pool(
                                attempt { sc.station(scGenreSlug, size) }.getOrDefault(emptyList()),
                                vetted = true, lead = false,
                            )
                        },
                    )
                }
                // Редакторская подборка Apple с ПК — если пара есть.
                // Ставим в один ряд с каноном: и то и другое собрано людьми,
                // а не сведено поиском по слову. Apple сам телефон не стримит,
                // поэтому вещи отсюда разрешаются в играбельную копию по ISRC
                // тем же путём, что и всё остальное.
                if (pc?.paired == true) {
                    add(
                        async {
                            Pool(
                                attempt { pc.appleStation(fallbackQuery, size) }
                                    .getOrDefault(emptyList()),
                                vetted = true,
                            )
                        },
                    )
                }
                // ГЛАВНЫЙ источник для жанров — канон: кто этот жанр ДЕЛАЕТ.
                //
                // Поиск по названию жанра находит вещи, у которых это слово в
                // заголовке или тегах: любительские загрузки и часовые миксы
                // «synthwave mix». Артистов жанра он не находит вовсе — у
                // Timecop1983 слова «synthwave» в названиях треков нет. Отсюда
                // и была «шляпа, которую никто не стал бы слушать».
                add(
                    async {
                        Pool(runCatching { canonPool(fallbackQuery, clients) }.getOrDefault(emptyList()), true)
                    },
                )
                // Поиск у каждого сервиса остаётся ПОДСПОРЬЕМ: он добирает то,
                // чего канон не покрыл, и обязан пройти сверку жанра.
                clients.forEach { c ->
                    add(async { Pool(attempt { c.search(fallbackQuery).tracks.take(40) }.getOrDefault(emptyList()), false) })
                }
            }.awaitAll()
        }
        if (pools.isEmpty()) { lastOutcome = Outcome.NOTHING_FOUND; return emptyList() }

        // Кто в этом жанре на слуху по чарту Apple. Отказ чарта ничего не
        // ломает: пустое множество просто ничего не поднимает.
        val chart = if (appleGenreId != null) {
            attempt { AppleChart.topArtists(appleGenreId) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

        // Сверка жанра, отсев диджейских сборок и очередь — в [StationAssembly],
        // тем же вызовом, каким это меряет стенд на фейковых пулах.
        val nowYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val air = StationAssembly.assemble(
            station = fallbackQuery,
            sources = pools,
            genres = genres,
            chart = chart,
            taste = effectiveTaste,
            seed = rotationSeed,
            size = size,
            nowYear = nowYear,
        )
        val out = air.tracks

        android.util.Log.i(
            "RipsterStation",
            "«$fallbackQuery»: " + pools.indices.joinToString(", ") { i ->
                val tag = if (pools[i].lead) "lead" else "fill"
                val vet = if (pools[i].vetted) "vetted" else "sifted"
                "#$i $tag/$vet ${pools[i].tracks.size}→${air.kept[i]} (−${air.dropped[i]})"
            } + " | chart: ${chart.size}",
        )

        if (out.size >= 5) {
            lastOutcome = Outcome.OK
            android.util.Log.i(
                "RipsterStation",
                "air «$fallbackQuery»: " + out.take(12).joinToString(" · ") { "${it.artist} — ${it.title}" },
            )
            return out
        }

        // Своего набралось мало. Либо искать было негде, либо всё найденное
        // оказалось чужого жанра — это разные новости, и совет разный.
        val anyFound = pools.any { it.tracks.isNotEmpty() }
        val anyDeclared = pools.any { p -> p.tracks.any { StationAssembly.declaredGenre(it) != null } }
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
                .firstNotNullOfOrNull { attempt { it.resolve(url) }.getOrNull() }
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
                async { attempt { c.search(query).albums }.getOrDefault(emptyList()) }
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
                async { attempt { c.search(query).tracks.take(20) }.getOrDefault(emptyList()) }
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
            async { resolveOne(tr, quality, fallbackArtwork) }
        }.awaitAll().filterNotNull()
    }

    /** Один трек → поток. Сперва родной сервис; не смог — ищем ТОТ ЖЕ трек в
     *  стримабельных сервисах и играем оттуда.
     *
     *  Зачем фолбэк: станция собирает треки из разных источников, и часть их
     *  приходит из сервиса, который на телефоне не отдаёт поток (Apple без
     *  сопряжения, гео-блок и т.п.). Раньше `toStreamItems` пробовал ТОЛЬКО
     *  родной сервис — и станция целиком падала в «Couldn't build the stream»,
     *  хотя тот же трек лежит в Deezer/Tidal/Yandex (жалоба владельца 13.09.2026:
     *  «нажал на электронику — играет 1 трек, трек-лист пуст»). */
    private suspend fun resolveOne(
        tr: Track, quality: List<String>, fallbackArtwork: String?,
    ): PlayerController.StreamItem? {
        // 1) родной сервис трека
        attempt {
            ServiceRegistry.get(tr.service)?.let { c ->
                val info = c.streamInfo(tr, quality)
                if (info.url.isNotBlank()) return streamItem(tr, info, fallbackArtwork)
            }
        }.onFailure { lastStreamError = it }

        // 2) кросс-сервис: ищем «artist title» в стримабельных и играем найденное
        val order = listOf(Service.DEEZER, Service.TIDAL, Service.QOBUZ,
                           Service.YANDEX, Service.SOUNDCLOUD)
        val q = "${tr.artist} ${tr.title}".trim()
        for (svc in order) {
            if (svc == tr.service) continue
            val c = ServiceRegistry.get(svc) ?: continue
            if (!attempt { c.isConfigured() }.getOrDefault(false)) continue
            val alt = attempt {
                val hit = c.search(q).tracks.firstOrNull { m ->
                    val t = m.title.lowercase(); val want = tr.title.lowercase()
                    (t.contains(want) || want.contains(t)) &&
                        m.artist.lowercase().split(",", "&", " x ", " feat")
                            .any { it.isNotBlank() && tr.artist.lowercase().contains(it.trim().take(6)) }
                } ?: return@attempt null
                val info = c.streamInfo(hit, quality)
                if (info.url.isBlank()) null
                else {
                    // Название — от исходного трека станции, но артистов берём
                    // БОГАЧЕ: если стриминговый источник джойнит коллаб («A, B»),
                    // а исходник дал одного, показываем полный состав (владелец
                    // 13.09.2026: «вижу 1 артиста, а их несколько»). Сюда же
                    // просим то, что о треке уже знает кэш глубокого состава
                    // ([ArtistDepth]) — это настоящий список участников сервиса,
                    // а не догадка по длине строки. Новых запросов ради него не
                    // делаем: станция и так платит поиском по каждому сервису.
                    val known = listOfNotNull(tr.artist, hit.artist,
                        ArtistDepth.cached(tr), ArtistDepth.cached(hit))
                    val richer = known.maxByOrNull { ChartBoost.namesOf(it).size }
                        ?.takeIf { it.contains(tr.artist.take(4), ignoreCase = true) }
                        ?: tr.artist
                    streamItem(tr.copy(service = svc, artist = richer), info, fallbackArtwork)
                }
            }.onFailure { lastStreamError = it }.getOrNull()
            if (alt != null) return alt
        }
        return null
    }

    private fun streamItem(
        tr: Track, info: net.ripster.mobile.core.model.StreamInfo, fallbackArtwork: String?,
    ): PlayerController.StreamItem = PlayerController.StreamItem(
        // Зашифрованный Deezer-поток метим для DataSource плеера, иначе ExoPlayer
        // играет шифр-байты (тишина).
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
