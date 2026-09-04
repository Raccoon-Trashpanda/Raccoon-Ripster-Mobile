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
 * Автосборка жанровой станции — НЕ поиск, а конкретный курируемый плейлист.
 *
 * Источник по приоритету:
 *  1. Чарт SoundCloud по жанру (`charts?kind=top&genre=…`) — работает без ПК,
 *     это уже готовый «топ по жанру», не выдача поиска.
 *  2. Если SoundCloud недоступен — слияние топ-N результатов по жанру из всех
 *     настроенных сервисов (свой простой алгоритм round-robin + дедуп по ISRC).
 *
 * Дальше [StreamResolver] превращает треки в прямые стрим-URL — станция
 * играется потоком, без скачивания.
 */
object StationBuilder {

    suspend fun build(
        scGenreSlug: String,
        fallbackQuery: String,
        yandexStationId: String? = null,
        size: Int = 30,
    ): List<Track> {
        // 1. Яндекс rotor — родная «Моя волна» по жанру/настроению/активности.
        if (!yandexStationId.isNullOrBlank()) {
            (ServiceRegistry.get(Service.YANDEX) as? YandexMusicClient)?.let { ya ->
                val t = runCatching { ya.station(yandexStationId, size) }.getOrDefault(emptyList())
                if (t.size >= 5) return t
            }
        }
        // 2. Чарт SoundCloud по жанру.
        if (scGenreSlug.isNotBlank()) {
            (ServiceRegistry.get(Service.SOUNDCLOUD) as? SoundCloudClient)?.let { sc ->
                val t = runCatching { sc.station(scGenreSlug, size) }.getOrDefault(emptyList())
                if (t.size >= 5) return t
            }
        }
        val clients = ServiceRegistry.configured()
        if (clients.isEmpty()) return emptyList()
        val perClient: List<List<Track>> = coroutineScope {
            clients.map { c ->
                async { runCatching { c.search(fallbackQuery).tracks.take(12) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }
        val seen = HashSet<String>()
        val out = ArrayList<Track>()
        var i = 0
        while (out.size < size && perClient.any { i < it.size }) {
            for (list in perClient) {
                val tr = list.getOrNull(i) ?: continue
                val key = (tr.isrc ?: (tr.title + "|" + tr.artist)).lowercase()
                if (seen.add(key)) out.add(tr)
                if (out.size >= size) break
            }
            i++
        }
        return out
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
            !sel?.containerTitle.isNullOrBlank() -> sel!!.containerTitle!!
            else -> return false
        }
        return playSearch(player, query, quality, fallbackArtwork)
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
    ): Boolean {
        if (query.isBlank()) return false

        // 1) пробуем найти САМ релиз (поиск теперь отдаёт и альбомы) и заиграть
        //    его целиком — это и есть «открыть весь сборник», а не трек из него.
        val albums = withContext(Dispatchers.IO) {
            STREAMABLE.mapNotNull { ServiceRegistry.get(it) }.map { c ->
                async { runCatching { c.search(query).albums }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.flatten()
        val album = albums.firstOrNull { it.title.length >= 4 && query.contains(it.title, true) }
            ?: albums.firstOrNull()
        if (album != null) {
            val u = releaseUrl(album.service, album.id)
            if (u.isNotBlank() && play(player, u, quality, fallbackArtwork)) return true
        }

        // 2) альбома нет — играем найденные треки списком
        val fromSearch: List<Track> = coroutineScope {
            STREAMABLE.mapNotNull { ServiceRegistry.get(it) }.map { c ->
                async { runCatching { c.search(query).tracks.take(20) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }.firstOrNull { it.isNotEmpty() } ?: return false

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
